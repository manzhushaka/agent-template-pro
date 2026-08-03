package com.manzhushaka.agent.controlplane;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Parser for the small allowlist accepted by the knowledge API. Limits are enforced before text
 * enters chunks so malformed archives or unexpectedly large documents fail as retryable index jobs.
 */
public final class BoundedDocumentTextParser implements DocumentTextParser {
    public static final int MAX_SOURCE_BYTES = 10 * 1024 * 1024;
    public static final int MAX_PAGES = 200;
    public static final int MAX_EXTRACTED_CHARACTERS = 1_000_000;
    private static final long MAX_PDF_MAIN_MEMORY_BYTES = 8L * 1024 * 1024;
    private static final long MAX_PDF_STORAGE_BYTES = 64L * 1024 * 1024;
    private static final long MAX_DOCX_ENTRY_BYTES = 16L * 1024 * 1024;
    private static final long MAX_DOCX_UNCOMPRESSED_BYTES = 32L * 1024 * 1024;
    private static final int MAX_DOCX_ENTRIES = 2_048;

    static {
        configurePoiArchiveLimits();
    }

    @Override
    public ParsedDocument parse(String contentType, byte[] content) {
        validateSource(content);
        String normalized = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).trim();
        return switch (normalized) {
            case "text/plain", "text/markdown" -> boundedText(new String(content, StandardCharsets.UTF_8), 1);
            case "application/pdf" -> pdf(content);
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> docx(content);
            default -> throw new IllegalArgumentException("UNSUPPORTED_DOCUMENT_TYPE");
        };
    }

    private ParsedDocument pdf(byte[] content) {
        try (PDDocument document = PDDocument.load(
                new ByteArrayInputStream(content), MemoryUsageSetting.setupMixed(MAX_PDF_MAIN_MEMORY_BYTES, MAX_PDF_STORAGE_BYTES)
        )) {
            if (document.isEncrypted()) {
                throw new IllegalArgumentException("ENCRYPTED_DOCUMENT_UNSUPPORTED");
            }
            int pages = document.getNumberOfPages();
            if (pages > MAX_PAGES) {
                throw new IllegalArgumentException("DOCUMENT_PAGE_LIMIT_EXCEEDED");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(pages);
            BoundedTextWriter writer = new BoundedTextWriter(MAX_EXTRACTED_CHARACTERS);
            stripper.writeText(document, writer);
            return new ParsedDocument(writer.text(), pages);
        } catch (TextLimitExceededException exception) {
            throw new IllegalArgumentException("DOCUMENT_TEXT_LIMIT_EXCEEDED");
        } catch (IOException exception) {
            throw new IllegalArgumentException("PDF_PARSE_FAILED");
        }
    }

    private ParsedDocument docx(byte[] content) {
        validateDocxArchive(content);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(content))) {
            int pages = Math.max(1, document.getProperties().getExtendedProperties().getUnderlyingProperties().getPages());
            if (pages > MAX_PAGES) {
                throw new IllegalArgumentException("DOCUMENT_PAGE_LIMIT_EXCEEDED");
            }
            BoundedTextWriter writer = new BoundedTextWriter(MAX_EXTRACTED_CHARACTERS);
            for (var paragraph : document.getParagraphs()) {
                writer.write(paragraph.getText());
                writer.write("\n");
            }
            return new ParsedDocument(writer.text(), pages);
        } catch (TextLimitExceededException exception) {
            throw new IllegalArgumentException("DOCUMENT_TEXT_LIMIT_EXCEEDED");
        } catch (IOException exception) {
            throw new IllegalArgumentException("DOCX_PARSE_FAILED");
        }
    }

    private void validateSource(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("DOCUMENT_SOURCE_EMPTY");
        }
        if (content.length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("DOCUMENT_SOURCE_LIMIT_EXCEEDED");
        }
    }

    private void validateDocxArchive(byte[] content) {
        long uncompressedBytes = 0;
        int entries = 0;
        byte[] buffer = new byte[8_192];
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_DOCX_ENTRIES) {
                    throw new IllegalArgumentException("DOCX_ARCHIVE_LIMIT_EXCEEDED");
                }
                long entryBytes = 0;
                int read;
                while ((read = archive.read(buffer)) != -1) {
                    entryBytes += read;
                    uncompressedBytes += read;
                    if (entryBytes > MAX_DOCX_ENTRY_BYTES || uncompressedBytes > MAX_DOCX_UNCOMPRESSED_BYTES) {
                        throw new IllegalArgumentException("DOCX_ARCHIVE_LIMIT_EXCEEDED");
                    }
                }
                archive.closeEntry();
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new IllegalArgumentException("DOCX_PARSE_FAILED");
        }
    }

    private static void configurePoiArchiveLimits() {
        // ZipSecureFile is process-wide in Apache POI. Apply only stricter floors before any DOCX is opened.
        ZipSecureFile.setMinInflateRatio(Math.max(ZipSecureFile.getMinInflateRatio(), 0.01d));
        ZipSecureFile.setMaxEntrySize(Math.min(ZipSecureFile.getMaxEntrySize(), MAX_DOCX_ENTRY_BYTES));
        ZipSecureFile.setMaxTextSize(Math.min(ZipSecureFile.getMaxTextSize(), MAX_EXTRACTED_CHARACTERS));
    }

    private ParsedDocument boundedText(String text, int pages) {
        if (text == null || text.length() > MAX_EXTRACTED_CHARACTERS) {
            throw new IllegalArgumentException("DOCUMENT_TEXT_LIMIT_EXCEEDED");
        }
        return new ParsedDocument(text, pages);
    }

    private static final class BoundedTextWriter extends Writer {
        private final int limit;
        private final StringBuilder value = new StringBuilder(4_096);

        private BoundedTextWriter(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(char[] characters, int offset, int length) throws IOException {
            append(length);
            value.append(characters, offset, length);
        }

        @Override
        public void write(String text) throws IOException {
            if (text == null) {
                return;
            }
            append(text.length());
            value.append(text);
        }

        @Override
        public void flush() {
            // No external resource is buffered.
        }

        @Override
        public void close() {
            // The caller owns the document lifecycle.
        }

        private void append(int length) throws TextLimitExceededException {
            if (length > limit - value.length()) {
                throw new TextLimitExceededException();
            }
        }

        private String text() {
            return value.toString();
        }
    }

    private static final class TextLimitExceededException extends IOException {
    }
}
