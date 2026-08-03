package com.manzhushaka.agent.controlplane;

/**
 * Stores source files outside control-plane metadata. Implementations must never treat an object
 * key supplied by a caller as a local path.
 */
public interface ObjectStoragePort {
    StoredObject put(String objectKey, String contentType, byte[] content);

    byte[] get(String objectKey);

    /**
     * Reads an object with the caller's verified size as a hard upper bound. Adapters that can
     * stream should enforce the bound while reading rather than materialising an arbitrary body.
     */
    default byte[] get(String objectKey, long maxBytes) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("对象读取大小上限无效。");
        }
        byte[] content = get(objectKey);
        if (content.length > maxBytes) {
            throw new IllegalStateException("OBJECT_SIZE_LIMIT_EXCEEDED");
        }
        return content;
    }

    void delete(String objectKey);

    record StoredObject(String objectKey, String contentType, long size, String sha256) {
    }
}
