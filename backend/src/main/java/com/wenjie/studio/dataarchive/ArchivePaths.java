package com.wenjie.studio.dataarchive;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class ArchivePaths {
    private ArchivePaths() {}

    static String uuid(String value) {
        if (value == null || !UUID.fromString(value).toString().equals(value))
            throw new IllegalArgumentException("标识必须是标准小写 UUID");
        return value;
    }

    static Path root(String configured) throws IOException {
        if (configured == null || configured.isBlank()) throw new IOException("ARCHIVE_DIRECTORY_UNSET");
        Path path = Path.of(configured);
        if (!path.isAbsolute() || !path.equals(path.normalize())) throw new IOException("ARCHIVE_DIRECTORY_INVALID");
        directories(path);
        return path;
    }

    static void directories(Path path) throws IOException {
        if (path == null || !path.isAbsolute()) throw new IOException("ARCHIVE_DIRECTORY_INVALID");
        Path cursor = path.getRoot();
        directory(cursor);
        for (Path part : path) {
            cursor = cursor.resolve(part);
            directory(cursor);
        }
    }

    static void directory(Path path) throws IOException {
        BasicFileAttributes value = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!value.isDirectory() || value.isSymbolicLink() || value.isOther()
                || !path.toRealPath().equals(path.toAbsolutePath().normalize()))
            throw new IOException("ARCHIVE_DIRECTORY_UNSAFE");
    }

    static Path childDirectory(Path parent, String child, boolean create) throws IOException {
        if (!child.matches("[a-z0-9][a-z0-9-]{0,63}")) throw new IOException("ARCHIVE_PATH_INVALID");
        directories(parent);
        Path path = parent.resolve(child);
        if (create && !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null)
                    Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                else Files.createDirectory(path);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // 并发创建后仍核对真实目录，不能跟随替换成的链接。
            }
        }
        directory(path);
        return path;
    }

    static long regular(Path path, long maximum, boolean allowEmpty) throws IOException {
        directories(path.getParent());
        var value = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!value.isRegularFile() || value.isSymbolicLink() || value.isOther()
                || value.size() > maximum || value.size() < (allowEmpty ? 0 : 1))
            throw new IOException("ARCHIVE_FILE_UNSAFE");
        return value.size();
    }

    static InputStream open(Path path, long maximum) throws IOException {
        regular(path, maximum, false);
        return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    static OutputStream create(Path path) throws IOException {
        directories(path.getParent());
        var options = java.util.Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        if (Files.getFileAttributeView(path.getParent(), PosixFileAttributeView.class) != null)
            return java.nio.channels.Channels.newOutputStream(Files.newByteChannel(path, options,
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))));
        return Files.newOutputStream(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
    }

    static void empty(Path path) throws IOException {
        directories(path);
        try (var children = Files.newDirectoryStream(path)) {
            if (children.iterator().hasNext()) throw new IOException("RESTORE_DIRECTORY_NOT_EMPTY");
        }
    }

    static void delete(Path path) throws IOException {
        directories(path.getParent());
        // 只删除精确目录项，不跟随链接，也不递归清理未知文件。
        Files.deleteIfExists(path);
    }

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }

    static String sha256(Path path, long maximum) throws IOException {
        MessageDigest digest = digest();
        try (InputStream input = open(path, maximum)) {
            byte[] buffer = new byte[65_536];
            long total = 0;
            for (int read; (read = input.read(buffer)) != -1;) {
                total += read;
                if (total > maximum) throw new IOException("ARCHIVE_SIZE_LIMIT");
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static String sha256(byte[] bytes) { return HexFormat.of().formatHex(digest().digest(bytes)); }
}
