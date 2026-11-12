package com.wenjie.studio.material.parse;

import com.wenjie.studio.StudioApplication;
import org.springframework.boot.system.ApplicationHome;
import org.springframework.stereotype.Component;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarFile;

/** 解析器只接收已核验的文件路径，输出仍须按不可信数据重新校验。 */
@Component
public final class MaterialParserProcess {
    public static final int MAX_RESULT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_PREVIEW_BYTES = 20 * 1024 * 1024;
    public static final int MAX_CHARACTERS = 250_000;
    public static final int MAX_SEGMENTS = 1000;
    public static final int MAX_SEGMENT_CHARACTERS = 10_000;
    static final int MAX_RUNTIME_SECONDS = 45;
    static final int EXIT_TIMEOUT = 124;
    static final int EXIT_PARENT_GONE = 125;
    private static final Set<String> KINDS = Set.of("PDF", "DOCX", "PNG", "JPEG");
    private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
    private final JsonMapper json;

    public record ParsedSegment(String locator, String text) {}
    public record Result(String status, String parserVersion, boolean truncated, List<String> warnings,
                         String errorCode, List<ParsedSegment> segments, boolean hasPreview) {}

    public MaterialParserProcess(JsonMapper json) {
        this.json = json.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    }

    public synchronized Result parse(Path input, Path runDirectory, String kind) {
        if (kind == null || !KINDS.contains(kind)) return failure(kind, "UNSUPPORTED", "UNSUPPORTED_FILE_TYPE");
        Process process = null;
        boolean interrupted = false;
        try {
            requirePath(input, false);
            requirePath(runDirectory, true);
            if (Files.size(input) < 1 || Files.size(input) > 20L * 1024 * 1024) {
                return failure(kind, "FAILED", "INVALID_FILE_SIZE");
            }
            Path output = runDirectory.resolve("result.json");
            Path preview = runDirectory.resolve("preview.png");
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(preview, LinkOption.NOFOLLOW_LINKS)) {
                return failure(kind, "FAILED", "PARSER_OUTPUT_ALREADY_EXISTS");
            }
            Path archive = applicationArchive();
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java");
            if (!Files.isRegularFile(java) || !java.isAbsolute()) {
                return failure(kind, "FAILED", "PARSER_RUNTIME_UNAVAILABLE");
            }
            // 固定空启动配置，防止 PropertiesLauncher 在运行目录或类路径中拾取额外配置。
            Path launcher = runDirectory.resolve("parser-launcher.properties");
            Files.writeString(launcher, "", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            List<String> command = new ArrayList<>(List.of(java.toString(), "-Xmx256m", "-XX:MaxMetaspaceSize=128m",
                    "-Djava.awt.headless=true", "-Djava.io.tmpdir=" + runDirectory, "-Duser.home=" + runDirectory,
                    "-XX:-CreateCoredumpOnCrash",
                    "-Dloader.main=" + ParserMain.class.getName(), "-Dloader.config.location=" + launcher.toUri(),
                    "-Dloader.home=" + runDirectory, "-Dloader.path=BOOT-INF/lib", "-cp", archive.toString(),
                    "org.springframework.boot.loader.launch.PropertiesLauncher", input.toString(), runDirectory.toString(), kind));
            ProcessBuilder builder = new ProcessBuilder(command).directory(runDirectory.toFile())
                    .redirectInput(ProcessBuilder.Redirect.PIPE)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            String systemRoot = builder.environment().get("SystemRoot");
            builder.environment().clear();
            if (systemRoot != null) builder.environment().put("SystemRoot", systemRoot);
            process = builder.start();
            // 写端只作父存活凭据，必须保持到子进程退出；父异常退出时子进程会读到 EOF。
            if (!process.waitFor(MAX_RUNTIME_SECONDS, TimeUnit.SECONDS)) {
                interrupted = stopAndWait(process);
                return failure(kind, "FAILED", "PARSER_TIMEOUT");
            }
            if (process.exitValue() == EXIT_TIMEOUT) return failure(kind, "FAILED", "PARSER_TIMEOUT");
            if (process.exitValue() == EXIT_PARENT_GONE) return failure(kind, "FAILED", "PARSER_PARENT_GONE");
            if (process.exitValue() != 0) return failure(kind, "FAILED", "PARSER_PROCESS_FAILED");
            requirePath(output, false);
            long outputSize = Files.size(output);
            if (outputSize < 1 || outputSize > MAX_RESULT_BYTES) return failure(kind, "FAILED", "PARSER_RESULT_INVALID");
            Result result;
            try (var channel = Files.newByteChannel(output, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream stream = Channels.newInputStream(channel)) {
                byte[] bytes = stream.readNBytes(MAX_RESULT_BYTES + 1);
                if (bytes.length > MAX_RESULT_BYTES) return failure(kind, "FAILED", "PARSER_RESULT_INVALID");
                result = json.readValue(bytes, Result.class);
            }
            return validate(result, kind, preview) ? result : failure(kind, "FAILED", "PARSER_RESULT_INVALID");
        } catch (InterruptedException exception) {
            interrupted = true;
            if (process != null) interrupted |= stopAndWait(process);
            return failure(kind, "FAILED", "PARSER_INTERRUPTED");
        } catch (IOException | RuntimeException exception) {
            return failure(kind, "FAILED", "PARSER_PROCESS_UNAVAILABLE");
        } finally {
            // 不能仅因 waitFor 被中断就释放槽位，否则旧解析进程仍可能继续占用资源。
            if (process != null && process.isAlive()) interrupted |= stopAndWait(process);
            if (process != null) {
                try { process.getOutputStream().close(); }
                catch (IOException ignored) { /* 已确认退出，管道可能已被 JVM 回收。 */ }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private static boolean stopAndWait(Process process) {
        process.destroyForcibly();
        boolean interrupted = false;
        while (process.isAlive()) {
            try { process.waitFor(); }
            catch (InterruptedException exception) { interrupted = true; }
        }
        return interrupted;
    }

    private static Path applicationArchive() throws IOException {
        var source = new ApplicationHome(StudioApplication.class).getSource();
        if (source == null) throw new IOException("PARSER_ARCHIVE_UNAVAILABLE");
        Path archive = source.toPath().toAbsolutePath().normalize();
        requirePath(archive, false);
        try (JarFile jar = new JarFile(archive.toFile())) {
            if (jar.getEntry("org/springframework/boot/loader/launch/PropertiesLauncher.class") == null
                    || jar.getEntry("BOOT-INF/classes/com/wenjie/studio/material/parse/ParserMain.class") == null) {
                throw new IOException("PARSER_ARCHIVE_UNAVAILABLE");
            }
        }
        return archive;
    }

    static void requirePath(Path path, boolean directory) throws IOException {
        if (path == null || !path.isAbsolute() || !path.normalize().equals(path)) throw new IOException("INVALID_PATH");
        Path cursor = path.getRoot();
        for (Path component : path) {
            cursor = cursor.resolve(component);
            if (Files.isSymbolicLink(cursor)) throw new IOException("SYMLINK_PATH");
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (directory ? !attributes.isDirectory() : !attributes.isRegularFile()) throw new IOException("INVALID_FILE_TYPE");
    }

    private static boolean validate(Result result, String kind, Path preview) throws IOException {
        if (result == null || !Set.of("SUCCEEDED", "FAILED", "UNSUPPORTED").contains(result.status())
                || !version(kind).equals(result.parserVersion()) || result.warnings() == null
                || result.warnings().size() > 20 || result.segments() == null || result.segments().size() > MAX_SEGMENTS) return false;
        for (String warning : result.warnings()) {
            if (warning == null || warning.length() > 500 || warning.isBlank()) return false;
        }
        if (!"SUCCEEDED".equals(result.status())) {
            return result.errorCode() != null && result.errorCode().matches("[A-Z0-9_]{1,64}")
                    && result.segments().isEmpty() && !result.hasPreview() && !result.truncated();
        }
        if (result.errorCode() != null) return false;
        int characters = 0;
        for (ParsedSegment segment : result.segments()) {
            if (segment == null || segment.locator() == null || segment.locator().isBlank() || segment.locator().length() > 500
                    || segment.text() == null || segment.text().isBlank() || segment.text().length() > MAX_SEGMENT_CHARACTERS
                    || !validUnicode(segment.text())) return false;
            characters += segment.text().length();
        }
        if (characters > MAX_CHARACTERS) return false;
        boolean image = "PNG".equals(kind) || "JPEG".equals(kind);
        if (image != result.hasPreview() || (image && !result.segments().isEmpty())) return false;
        if (result.hasPreview()) {
            requirePath(preview, false);
            if (Files.size(preview) < PNG_SIGNATURE.length || Files.size(preview) > MAX_PREVIEW_BYTES) return false;
            try (var channel = Files.newByteChannel(preview, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
                 InputStream stream = Channels.newInputStream(channel)) {
                if (!java.util.Arrays.equals(stream.readNBytes(PNG_SIGNATURE.length), PNG_SIGNATURE)) return false;
            }
        }
        return true;
    }

    static boolean validUnicode(String value) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) return false;
            } else if (Character.isLowSurrogate(character)) return false;
        }
        return true;
    }

    static String version(String kind) {
        return switch (kind == null ? "" : kind) {
            case "PDF" -> "PDFBox 3.0.8";
            case "DOCX" -> "POI XWPF 6.0.0";
            case "PNG", "JPEG" -> "JDK ImageIO 21";
            default -> "UNSUPPORTED";
        };
    }

    static Result failure(String kind, String status, String code) {
        return new Result(status, version(kind), false, List.of(), code, List.of(), false);
    }
}
