package com.manzhushaka.agent.controlplane;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class S3CompatibleObjectStorageTest {
    @Test
    void rejectsEndpointsThatCouldBypassDeploymentAllowlist() {
        assertThrows(IllegalArgumentException.class, () -> new S3CompatibleObjectStorage(
                URI.create("http://storage.example"), "knowledge-docs", "cn-hangzhou", "key", "secret", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new S3CompatibleObjectStorage(
                URI.create("https://key:secret@storage.example"), "knowledge-docs", "cn-hangzhou", "key", "secret", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new S3CompatibleObjectStorage(
                URI.create("https://storage.example/prefix"), "knowledge-docs", "cn-hangzhou", "key", "secret", Duration.ofSeconds(5)));
        assertThrows(IllegalArgumentException.class, () -> new S3CompatibleObjectStorage(
                URI.create("https://127.0.0.1"), "knowledge-docs", "cn-hangzhou", "key", "secret", Duration.ofSeconds(5), Set.of("127.0.0.1")));
    }

    @Test
    void signsEscapedServerGeneratedKeyAndReadsBoundedObject() throws Exception {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> path = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<byte[]> body = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            method.set(exchange.getRequestMethod());
            path.set(exchange.getRequestURI().getRawPath());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            exchange.getRequestBody().transferTo(output);
            body.set(output.toByteArray());
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] response = "stored".getBytes();
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } else {
                exchange.sendResponseHeaders(200, -1);
            }
            exchange.close();
        });
        server.start();
        try {
            S3CompatibleObjectStorage storage = storage(server);
            storage.put("knowledge/base-1/doc_1/v.1", "text/plain", "payload".getBytes());

            assertEquals("PUT", method.get());
            assertEquals("/knowledge-docs/knowledge/base-1/doc_1/v.1", path.get());
            assertArrayEquals("payload".getBytes(), body.get());
            assertTrue(authorization.get().startsWith("AWS4-HMAC-SHA256 Credential=access-id/20260803/cn-hangzhou/s3/aws4_request"));
            assertTrue(authorization.get().contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date"));

            assertArrayEquals("stored".getBytes(), storage.get("knowledge/base-1/doc_1/v.1"));
            assertEquals("GET", method.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsTooLargeDownloadBeforePassingItToParser() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] oversized = new byte[10 * 1024 * 1024 + 1];
            exchange.sendResponseHeaders(200, oversized.length);
            exchange.getResponseBody().write(oversized);
            exchange.close();
        });
        server.start();
        try {
            assertThrows(IllegalStateException.class, () -> storage(server).get("knowledge/base/doc/version"));
        } finally {
            server.stop(0);
        }
    }

    private S3CompatibleObjectStorage storage(HttpServer server) {
        return new S3CompatibleObjectStorage(
                HttpClient.newHttpClient(),
                URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                "knowledge-docs",
                "cn-hangzhou",
                "access-id",
                "secret-value",
                Duration.ofSeconds(5),
                Clock.fixed(Instant.parse("2026-08-03T01:02:03Z"), ZoneOffset.UTC),
                true
        );
    }
}
