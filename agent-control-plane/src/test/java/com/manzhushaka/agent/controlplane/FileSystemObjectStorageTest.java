package com.manzhushaka.agent.controlplane;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FileSystemObjectStorageTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void rejectsSymlinkedObjectPath() throws Exception {
        Path root = temporaryDirectory.resolve("objects");
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(root);
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("secret.txt"), "outside", StandardCharsets.UTF_8);
        Files.createSymbolicLink(root.resolve("knowledge"), outside);
        FileSystemObjectStorage storage = new FileSystemObjectStorage(root);

        assertThrows(IllegalStateException.class, () -> storage.get("knowledge/secret.txt", 64));
        assertThrows(IllegalStateException.class, () -> storage.put("knowledge/new.txt", "text/plain", "new".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void enforcesVerifiedReadBoundBeforeMaterialisingFile() throws Exception {
        FileSystemObjectStorage storage = new FileSystemObjectStorage(temporaryDirectory.resolve("objects"));
        storage.put("knowledge/bounded.txt", "text/plain", "small".getBytes(StandardCharsets.UTF_8));
        assertArrayEquals("small".getBytes(StandardCharsets.UTF_8), storage.get("knowledge/bounded.txt", 5));
        Files.writeString(temporaryDirectory.resolve("objects/knowledge/bounded.txt"), "too-large", StandardCharsets.UTF_8);

        assertThrows(IllegalStateException.class, () -> storage.get("knowledge/bounded.txt", 5));
    }
}
