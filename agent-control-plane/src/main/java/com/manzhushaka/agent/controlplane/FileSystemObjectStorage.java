package com.manzhushaka.agent.controlplane;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Development object storage rooted at one configured directory. */
public final class FileSystemObjectStorage implements ObjectStoragePort {
    private static final long MAX_OBJECT_BYTES = 10L * 1024 * 1024;
    private final Path root;

    public FileSystemObjectStorage(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("对象存储根目录不能为空。");
        }
        Path configured = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(configured);
            if (Files.isSymbolicLink(configured)) {
                throw new IllegalArgumentException("对象存储根目录不能是符号链接。");
            }
            this.root = configured.toRealPath(LinkOption.NOFOLLOW_LINKS);
        } catch (IOException exception) {
            throw new IllegalStateException("对象存储根目录不可用。", exception);
        }
    }

    @Override
    public StoredObject put(String objectKey, String contentType, byte[] content) {
        Path target = resolve(objectKey);
        try {
            if (content == null || content.length > MAX_OBJECT_BYTES) {
                throw new IllegalArgumentException("对象超过大小限制。");
            }
            createParents(target);
            Files.write(target, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            return new StoredObject(objectKey, contentType, content.length, sha256(content));
        } catch (IOException exception) {
            throw new IllegalStateException("知识文档对象写入失败。", exception);
        }
    }

    @Override
    public byte[] get(String objectKey) {
        return get(objectKey, MAX_OBJECT_BYTES);
    }

    @Override
    public byte[] get(String objectKey, long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("对象读取大小上限无效。");
        }
        long limit = Math.min(maxBytes, MAX_OBJECT_BYTES);
        Path target = resolve(objectKey);
        try {
            verifyParents(target);
            BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.size() > limit) {
                throw new IllegalStateException("OBJECT_SIZE_LIMIT_EXCEEDED");
            }
            try (InputStream input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS);
                    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.toIntExact(attributes.size()))) {
                byte[] buffer = new byte[8192];
                long total = 0;
                for (int read; (read = input.read(buffer)) != -1;) {
                    total += read;
                    if (total > limit) {
                        throw new IllegalStateException("OBJECT_SIZE_LIMIT_EXCEEDED");
                    }
                    output.write(buffer, 0, read);
                }
                return output.toByteArray();
            }
        } catch (IOException exception) {
            throw new IllegalStateException("知识文档对象不可读取。", exception);
        }
    }

    @Override
    public void delete(String objectKey) {
        try {
            Path target = resolve(objectKey);
            verifyParents(target);
            Files.deleteIfExists(target);
        } catch (IOException exception) {
            throw new IllegalStateException("知识文档对象删除失败。", exception);
        }
    }

    private Path resolve(String objectKey) {
        if (objectKey == null || !objectKey.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}") || objectKey.contains("..") || objectKey.contains("//")) {
            throw new IllegalArgumentException("无效的对象键。");
        }
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw new IllegalArgumentException("对象键越过存储根目录。");
        }
        return target;
    }

    private void createParents(Path target) throws IOException {
        Path relative = root.relativize(target.getParent());
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            try {
                Files.createDirectory(current);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Verify an existing component below.
            }
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("对象路径包含不安全目录。");
            }
        }
    }

    private void verifyParents(Path target) throws IOException {
        Path relative = root.relativize(target.getParent());
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("对象路径包含不安全目录。");
            }
        }
    }

    private String sha256(byte[] content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content);
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
