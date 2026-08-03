package com.manzhushaka.agent.controlplane;

/**
 * Converts a stored, allowlisted document into bounded text for the indexing worker. The parser
 * receives bytes from ObjectStoragePort only after the worker has acquired a durable job lease.
 */
public interface DocumentTextParser {
    ParsedDocument parse(String contentType, byte[] content);

    record ParsedDocument(String text, int pageCount) {
    }
}
