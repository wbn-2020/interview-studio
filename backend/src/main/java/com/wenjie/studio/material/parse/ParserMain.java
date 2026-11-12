package com.wenjie.studio.material.parse;

import org.apache.fontbox.FontBoxFont;
import org.apache.fontbox.ttf.TTFParser;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.RandomAccessReadBuffer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.pdmodel.font.CIDFontMapping;
import org.apache.pdfbox.pdmodel.font.FontMapper;
import org.apache.pdfbox.pdmodel.font.FontMappers;
import org.apache.pdfbox.pdmodel.font.FontMapping;
import org.apache.pdfbox.pdmodel.font.PDCIDSystemInfo;
import org.apache.pdfbox.pdmodel.font.PDFontDescriptor;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import tools.jackson.databind.json.JsonMapper;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.wenjie.studio.material.parse.MaterialParserProcess.MAX_CHARACTERS;
import static com.wenjie.studio.material.parse.MaterialParserProcess.MAX_PREVIEW_BYTES;
import static com.wenjie.studio.material.parse.MaterialParserProcess.MAX_RESULT_BYTES;
import static com.wenjie.studio.material.parse.MaterialParserProcess.MAX_SEGMENTS;
import static com.wenjie.studio.material.parse.MaterialParserProcess.MAX_SEGMENT_CHARACTERS;
import static com.wenjie.studio.material.parse.MaterialParserProcess.ParsedSegment;
import static com.wenjie.studio.material.parse.MaterialParserProcess.Result;

/** 仅供受限子进程调用，不创建应用上下文、数据库连接或 HTTP 服务。 */
public final class ParserMain {
    private ParserMain() {}

    public static void main(String[] arguments) throws IOException {
        startLifetimeGuards();
        if (arguments.length != 3) throw new IllegalArgumentException("INVALID_PARSER_ARGUMENTS");
        Path input = Path.of(arguments[0]);
        Path outputDirectory = Path.of(arguments[1]);
        String kind = arguments[2];
        MaterialParserProcess.requirePath(input, false);
        MaterialParserProcess.requirePath(outputDirectory, true);
        Result result;
        try {
            result = switch (kind) {
                case "PDF" -> pdf(input);
                case "DOCX" -> docx(input);
                case "PNG", "JPEG" -> image(input, outputDirectory, kind);
                default -> MaterialParserProcess.failure(kind, "UNSUPPORTED", "UNSUPPORTED_FILE_TYPE");
            };
        } catch (Rejected exception) {
            result = MaterialParserProcess.failure(kind, "UNSUPPORTED", exception.code);
        } catch (OutOfMemoryError | StackOverflowError error) {
            result = MaterialParserProcess.failure(kind, "FAILED", "PARSER_RESOURCE_LIMIT");
        } catch (Exception exception) {
            // 库异常经常含原文、ZIP 条目或磁盘路径，结果只保存固定分类。
            result = MaterialParserProcess.failure(kind, "FAILED", "DOCUMENT_PARSE_FAILED");
        }
        JsonMapper json = JsonMapper.builder().build();
        byte[] bytes = json.writeValueAsBytes(result);
        if (bytes.length > MAX_RESULT_BYTES) {
            bytes = json.writeValueAsBytes(MaterialParserProcess.failure(kind, "FAILED", "PARSER_OUTPUT_LIMIT"));
        }
        Files.write(outputDirectory.resolve("result.json"), bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    private static void startLifetimeGuards() {
        // 启动耗时计入 45 秒；用单调时钟等待，避免系统时钟回拨延长解析寿命。
        long remainingMillis = Math.max(0, TimeUnit.SECONDS.toMillis(MaterialParserProcess.MAX_RUNTIME_SECONDS)
                - ManagementFactory.getRuntimeMXBean().getUptime());
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(remainingMillis);
        Thread.ofPlatform().daemon(true).name("material-parser-deadline").start(() -> {
            long remaining;
            while ((remaining = deadline - System.nanoTime()) > 0) {
                try { TimeUnit.NANOSECONDS.sleep(remaining); }
                catch (InterruptedException ignored) { /* 中断不能取消硬时限。 */ }
            }
            // 解析库可能卡在锁、原生调用或关闭钩子中，超限直接结束此专用 JVM。
            Runtime.getRuntime().halt(MaterialParserProcess.EXIT_TIMEOUT);
        });
        Thread.ofPlatform().daemon(true).name("material-parser-parent").start(() -> {
            try { System.in.read(); }
            catch (IOException ignored) { /* 断管同样表示父连接已失效。 */ }
            // 管道绑定实际启动者的句柄，不会因父 PID 被复用而延长孤儿进程寿命。
            Runtime.getRuntime().halt(MaterialParserProcess.EXIT_PARENT_GONE);
        });
    }

    private static Result pdf(Path input) throws IOException, Rejected {
        TextCollector collector = new TextCollector();
        try (EmbeddedFallbackFonts fonts = new EmbeddedFallbackFonts();
             PDDocument document = Loader.loadPDF(input.toFile())) {
            if (document.isEncrypted()) throw new Rejected("PDF_ENCRYPTED_UNSUPPORTED");
            if (!document.getCurrentAccessPermission().canExtractContent()) throw new Rejected("PDF_EXTRACTION_FORBIDDEN");
            int pages = Math.min(document.getNumberOfPages(), 200);
            if (document.getNumberOfPages() > pages) collector.truncate("仅处理前 200 页，后续页未提取。");
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= pages; page++) {
                if (collector.full()) { collector.truncate("文字或片段已达到上限，后续内容未提取。"); break; }
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                LimitedWriter writer = new LimitedWriter(collector.remaining());
                try { stripper.writeText(document, writer); }
                catch (TextLimitReached exception) { collector.truncate("文字已达到 250000 字符上限，当前页仅保留前段。"); }
                collector.add("PDF 第 " + page + " 页", writer.content());
            }
            if (fonts.used) collector.warn("部分字体未嵌入或无法读取，已使用内置字体映射；字符识别和顺序需要对照原件确认。");
        } catch (InvalidPasswordException exception) {
            throw new Rejected("PDF_ENCRYPTED_UNSUPPORTED");
        }
        collector.warn("提取的是 PDF 文字层，阅读顺序与复杂表格布局可能不完整。");
        if (collector.segments.isEmpty()) collector.warn("未提取到文字层，文件可能是扫描件；本次未执行 OCR。");
        return collector.result("PDF");
    }

    private static Result docx(Path input) throws Exception {
        DocxArchiveGuard.inspect(input);
        // 子进程独占这些库级限制，配置不会改变 Web 进程或其他文件处理任务。
        ZipSecureFile.setMinInflateRatio(0.01d);
        ZipSecureFile.setMaxEntrySize(16L * 1024 * 1024);
        ZipSecureFile.setMaxTextSize(64L * 1024 * 1024);
        TextCollector collector = new TextCollector();
        try (OPCPackage archive = OPCPackage.open(input.toFile(), PackageAccess.READ);
             XWPFDocument document = new XWPFDocument(archive)) {
            extractBody(document.getBodyElements(), "DOCX 正文", collector, 0);
        }
        collector.warn("仅提取正文段落和表格文字；页眉、页脚、批注、脚注及复杂排版未完整重建。");
        if (collector.segments.isEmpty()) collector.warn("正文中未提取到可用文字，可以保留原件并手工摘录。");
        return collector.result("DOCX");
    }

    private static void extractBody(List<IBodyElement> elements, String parent, TextCollector collector, int depth) throws Rejected {
        if (depth > 8) { collector.truncate("嵌套表格层级超过支持范围，深层内容未提取。"); return; }
        for (int index = 0; index < elements.size(); index++) {
            if (collector.full() || ++collector.visitedElements > 10_000) {
                collector.truncate("文字、片段或结构数量已达到上限，后续内容未提取。");
                return;
            }
            IBodyElement element = elements.get(index);
            String location = parent + " / 块 " + (index + 1);
            if (element instanceof XWPFParagraph paragraph) {
                collector.add(location + " 段落", paragraph.getText());
            } else if (element instanceof XWPFTable table) {
                var rows = table.getRows();
                for (int row = 0; row < rows.size(); row++) {
                    var cells = rows.get(row).getTableCells();
                    for (int column = 0; column < cells.size(); column++) {
                        if (collector.full() || collector.visitedElements >= 10_000) {
                            collector.truncate("文字、片段或结构数量已达到上限，后续表格未提取。");
                            return;
                        }
                        extractBody(cells.get(column).getBodyElements(), location + " 表格 / 行 " + (row + 1)
                                + " 列 " + (column + 1), collector, depth + 1);
                    }
                }
            } else collector.warn("部分结构化内容不在当前段落／表格提取范围内，原件仍保留。");
        }
    }

    private static Result image(Path input, Path directory, String kind) throws IOException, Rejected {
        ImageIO.setUseCache(false);
        try (ImageInputStream imageInput = ImageIO.createImageInputStream(input.toFile())) {
            if (imageInput == null) throw new Rejected("IMAGE_FORMAT_UNSUPPORTED");
            var readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) throw new Rejected("IMAGE_FORMAT_UNSUPPORTED");
            ImageReader reader = readers.next();
            try {
                String detected = reader.getFormatName().toUpperCase(Locale.ROOT);
                if (!(kind.equals(detected) || kind.equals("JPEG") && detected.equals("JPG"))) {
                    throw new Rejected("IMAGE_FORMAT_MISMATCH");
                }
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 8_000_000) {
                    throw new Rejected("IMAGE_DIMENSION_LIMIT");
                }
                BufferedImage decoded = reader.read(0);
                if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height) {
                    throw new Rejected("IMAGE_CONTENT_INVALID");
                }
                BufferedImage normalized = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = normalized.createGraphics();
                try { graphics.drawImage(decoded, 0, 0, null); }
                finally { graphics.dispose(); decoded.flush(); }
                try (OutputStream output = new LimitedOutput(Files.newOutputStream(directory.resolve("preview.png"),
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), MAX_PREVIEW_BYTES)) {
                    if (!ImageIO.write(normalized, "PNG", output)) throw new Rejected("IMAGE_ENCODER_UNAVAILABLE");
                } finally { normalized.flush(); }
            } finally { reader.dispose(); }
        }
        return new Result("SUCCEEDED", MaterialParserProcess.version(kind), false,
                List.of("预览为重新编码的 PNG，仅保留图像像素；未执行 OCR 或多模态识别。"), null, List.of(), true);
    }

    static final class Rejected extends Exception {
        private final String code;
        Rejected(String code) { super(code); this.code = code; }
    }

    private static final class EmbeddedFallbackFonts implements FontMapper, AutoCloseable {
        private final TrueTypeFont fallback;
        private boolean used;

        EmbeddedFallbackFonts() throws IOException {
            try (InputStream input = FontMapper.class.getResourceAsStream(
                    "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf")) {
                if (input == null) throw new IOException("PARSER_FONT_UNAVAILABLE");
                fallback = new TTFParser().parse(RandomAccessReadBuffer.createBufferFromStream(input));
            }
            // 在打开文档前替换默认映射，避免扫描服务器字体目录或创建磁盘字体缓存。
            FontMappers.set(this);
        }

        @Override public FontMapping<TrueTypeFont> getTrueTypeFont(String name, PDFontDescriptor descriptor) {
            used = true;
            return new FontMapping<>(fallback, true);
        }

        @Override public FontMapping<FontBoxFont> getFontBoxFont(String name, PDFontDescriptor descriptor) {
            used = true;
            return new FontMapping<>(fallback, true);
        }

        @Override public CIDFontMapping getCIDFont(String name, PDFontDescriptor descriptor, PDCIDSystemInfo info) {
            used = true;
            return new CIDFontMapping(null, fallback, true);
        }

        @Override public void close() throws IOException { fallback.close(); }
    }

    private static final class TextCollector {
        private final List<ParsedSegment> segments = new ArrayList<>();
        private final Set<String> warnings = new LinkedHashSet<>();
        private int characters;
        private int visitedElements;
        private boolean truncated;

        int remaining() { return MAX_CHARACTERS - characters; }
        boolean full() { return remaining() == 0 || segments.size() >= MAX_SEGMENTS; }
        void warn(String warning) { if (warnings.size() < 20) warnings.add(warning); }
        void truncate(String warning) { truncated = true; warn(warning); }

        void add(String location, String text) throws Rejected {
            if (text == null || text.isBlank()) return;
            if (!MaterialParserProcess.validUnicode(text)) throw new Rejected("DOCUMENT_TEXT_INVALID");
            int start = 0;
            while (start < text.length()) {
                if (full()) { truncate("文字或片段已达到上限，后续内容未提取。"); return; }
                int end = Math.min(text.length(), start + Math.min(MAX_SEGMENT_CHARACTERS, remaining()));
                if (end < text.length() && end > start && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                if (end == start) { truncate("文字已达到字符上限，末尾未完整字符未提取。"); return; }
                String part = text.substring(start, end);
                if (!part.isBlank()) {
                    String locator = location + " / 字符 [" + start + "," + end + ")";
                    if (locator.length() > 500) { truncate("结构定位超过支持长度，该部分未提取。"); return; }
                    segments.add(new ParsedSegment(locator, part));
                    characters += part.length();
                }
                start = end;
            }
        }

        Result result(String kind) {
            return new Result("SUCCEEDED", MaterialParserProcess.version(kind), truncated,
                    List.copyOf(warnings), null, List.copyOf(segments), false);
        }
    }

    private static final class TextLimitReached extends IOException {}

    private static final class LimitedWriter extends Writer {
        private final StringBuilder content = new StringBuilder();
        private final int limit;
        LimitedWriter(int limit) { this.limit = limit; }
        @Override public void write(char[] characters, int offset, int length) throws IOException {
            int available = limit - content.length();
            int retained = Math.min(available, length);
            if (retained > 0) content.append(characters, offset, retained);
            if (length > retained) throw new TextLimitReached();
        }
        String content() {
            if (!content.isEmpty() && Character.isHighSurrogate(content.charAt(content.length() - 1))) {
                content.setLength(content.length() - 1);
            }
            return content.toString();
        }
        @Override public void flush() {}
        @Override public void close() {}
    }

    private static final class LimitedOutput extends OutputStream {
        private final OutputStream delegate;
        private final long limit;
        private long count;
        LimitedOutput(OutputStream delegate, long limit) { this.delegate = delegate; this.limit = limit; }
        @Override public void write(int value) throws IOException {
            if (count == limit) throw new IOException("PREVIEW_SIZE_LIMIT");
            delegate.write(value);
            count++;
        }
        @Override public void write(byte[] bytes, int offset, int length) throws IOException {
            if (length > limit - count) throw new IOException("PREVIEW_SIZE_LIMIT");
            delegate.write(bytes, offset, length);
            count += length;
        }
        @Override public void flush() throws IOException { delegate.flush(); }
        @Override public void close() throws IOException { delegate.close(); }
    }
}
