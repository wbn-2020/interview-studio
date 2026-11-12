package com.wenjie.studio.material;

import com.wenjie.studio.common.ApiException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.FileImageInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
public class MaterialStorage {
    private final MaterialProperties properties;
    public MaterialStorage(MaterialProperties properties) { this.properties = properties; }

    public Path root() throws IOException {
        if (properties.directory() == null || properties.directory().isBlank()) throw new IOException("MATERIAL_DIRECTORY_UNSET");
        Path configured = Path.of(properties.directory());
        if (!configured.isAbsolute()) throw new IOException("MATERIAL_DIRECTORY_NOT_ABSOLUTE");
        Path root = configured.normalize();
        verifyDirectories(root);
        if (!root.toRealPath().equals(root)) throw new IOException("MATERIAL_DIRECTORY_REDIRECTED");
        return root;
    }

    public void verifyAvailable() {
        try { root(); }
        catch (IOException | RuntimeException exception) { throw new ApiException(503, "MATERIAL_STORAGE_UNAVAILABLE", "资料私有目录不可用，请由服务器管理员检查配置。"); }
    }

    public Path uploadPath(String owner, String attachment, String upload, boolean createParents) throws IOException {
        MaterialGuards.uuid(owner); MaterialGuards.uuid(attachment); MaterialGuards.uuid(upload);
        Path directory = directory(createParents, owner, attachment, "uploads");
        return directory.resolve(upload + ".bin");
    }

    public Path runDirectory(String owner, String attachment, String run, boolean create) throws IOException {
        MaterialGuards.uuid(owner); MaterialGuards.uuid(attachment); MaterialGuards.uuid(run);
        return directory(create, owner, attachment, "runs", run);
    }

    private Path directory(boolean create, String... segments) throws IOException {
        Path path = root();
        for (String segment : segments) {
            path = path.resolve(segment);
            if (create && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                try { Files.createDirectory(path); }
                catch (java.nio.file.FileAlreadyExistsException ignored) { /* 并发创建后仍须检查实际目录类型。 */ }
            }
            directoryIsSafe(path);
        }
        return path;
    }

    private static void verifyDirectories(Path path) throws IOException {
        Path current = path.getRoot();
        if (current == null) throw new IOException("MATERIAL_DIRECTORY_NOT_ABSOLUTE");
        directoryIsSafe(current);
        for (Path part : path) { current = current.resolve(part); directoryIsSafe(current); }
    }

    private static void directoryIsSafe(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) throw new IOException("MATERIAL_DIRECTORY_UNSAFE");
        if (!path.toRealPath().equals(path.toAbsolutePath().normalize())) throw new IOException("MATERIAL_DIRECTORY_REDIRECTED");
    }

    public long fileSize(Path path, long maximum) throws IOException {
        verifyDirectories(path.getParent());
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther()
                || attributes.size() < 1 || attributes.size() > maximum) throw new IOException("MATERIAL_FILE_UNSAFE");
        return attributes.size();
    }

    public InputStream open(Path path, long maximum) throws IOException {
        fileSize(path, maximum);
        return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    public long receive(Path path, InputStream input, MaterialRows.Attachment attachment) throws TransferFailure {
        long written = 0;
        long started = System.nanoTime();
        MessageDigest digest = sha256();
        try (OutputStream output = Files.newOutputStream(path, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[65_536];
            while (true) {
                if (System.nanoTime() - started > 120_000_000_000L) throw new TransferFailure("UPLOAD_TIMEOUT", written);
                int size = input.read(buffer, 0, (int) Math.min(buffer.length, attachment.byteSize() - written + 1));
                if (size < 0) break;
                if (size == 0) continue;
                if (written + size > attachment.byteSize()) throw new TransferFailure("UPLOAD_SIZE_MISMATCH", written);
                output.write(buffer, 0, size);
                digest.update(buffer, 0, size);
                written += size;
            }
        } catch (TransferFailure failure) { throw failure; }
        catch (IOException | RuntimeException exception) { throw new TransferFailure("UPLOAD_IO_FAILED", written); }
        if (written != attachment.byteSize()) throw new TransferFailure("UPLOAD_SIZE_MISMATCH", written);
        if (!HexFormat.of().formatHex(digest.digest()).equals(attachment.sha256())) throw new TransferFailure("UPLOAD_DIGEST_MISMATCH", written);
        try { validateFormat(path, attachment.kind()); }
        catch (IOException | RuntimeException exception) { throw new TransferFailure("UPLOAD_FORMAT_MISMATCH", written); }
        return written;
    }

    public void verifyDigest(Path path, MaterialRows.Attachment attachment) throws IOException {
        if (fileSize(path, MaterialGuards.MAX_FILE_BYTES) != attachment.byteSize()) throw new IOException("MATERIAL_FILE_CHANGED");
        MessageDigest digest = sha256();
        try (InputStream input = open(path, MaterialGuards.MAX_FILE_BYTES)) {
            byte[] buffer = new byte[65_536];
            for (int read; (read = input.read(buffer)) != -1;) digest.update(buffer, 0, read);
        }
        if (!HexFormat.of().formatHex(digest.digest()).equals(attachment.sha256())) throw new IOException("MATERIAL_FILE_CHANGED");
    }

    private void validateFormat(Path path, String kind) throws IOException {
        byte[] header;
        try (InputStream input = open(path, MaterialGuards.MAX_FILE_BYTES)) { header = input.readNBytes(8); }
        boolean valid = switch (kind) {
            case "PDF" -> starts(header, new byte[]{'%', 'P', 'D', 'F', '-'});
            case "DOCX" -> starts(header, new byte[]{'P', 'K', 3, 4});
            case "PNG" -> Arrays.equals(header, new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            case "JPEG" -> starts(header, new byte[]{(byte) 255, (byte) 216, (byte) 255});
            default -> false;
        };
        if (!valid) throw new IOException("MATERIAL_FORMAT_MISMATCH");
        if (kind.equals("DOCX")) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                if (zip.size() > 2000 || zip.getEntry("[Content_Types].xml") == null
                        || zip.getEntry("word/document.xml") == null) throw new IOException("DOCX_STRUCTURE_INVALID");
                long total = 0;
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.getSize() < 0 || entry.getSize() > 16L * 1024 * 1024
                            || (entry.getSize() > 0 && entry.getCompressedSize() * 100L < entry.getSize())) throw new IOException("DOCX_LIMIT");
                    total += entry.getSize();
                    if (total > 64L * 1024 * 1024) throw new IOException("DOCX_LIMIT");
                }
            }
        }
        if (kind.equals("PNG") || kind.equals("JPEG")) {
            try (FileImageInputStream input = new FileImageInputStream(path.toFile())) {
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new IOException("IMAGE_UNSUPPORTED");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    int width = reader.getWidth(0), height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 4096 || height > 4096 || (long) width * height > 8_000_000)
                        throw new IOException("IMAGE_DIMENSIONS_LIMIT");
                } finally { reader.dispose(); }
            }
        }
    }

    private static boolean starts(byte[] value, byte[] prefix) {
        return value.length >= prefix.length && Arrays.equals(Arrays.copyOf(value, prefix.length), prefix);
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }
    public static final class TransferFailure extends IOException {
        private final String code;
        private final long received;
        TransferFailure(String code, long received) { super(code); this.code = code; this.received = received; }
        public String code() { return code; }
        public long received() { return received; }
    }
}
