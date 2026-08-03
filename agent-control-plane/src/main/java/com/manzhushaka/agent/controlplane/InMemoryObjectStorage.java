package com.manzhushaka.agent.controlplane;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Test and no-infrastructure object storage. */
public final class InMemoryObjectStorage implements ObjectStoragePort {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public StoredObject put(String objectKey, String contentType, byte[] content) {
        if (objects.putIfAbsent(objectKey, Arrays.copyOf(content, content.length)) != null) {
            throw new IllegalStateException("对象键已存在。");
        }
        return new StoredObject(objectKey, contentType, content.length, sha256(content));
    }

    @Override
    public byte[] get(String objectKey) {
        byte[] content = objects.get(objectKey);
        if (content == null) throw new IllegalStateException("知识文档对象不可读取。");
        return Arrays.copyOf(content, content.length);
    }

    @Override
    public void delete(String objectKey) {
        objects.remove(objectKey);
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
