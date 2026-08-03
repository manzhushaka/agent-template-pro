package com.manzhushaka.agent.controlplane;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedDocumentTextParserTest {
    private final BoundedDocumentTextParser parser = new BoundedDocumentTextParser();

    @Test
    void extractsBoundedPdfAndDocxText() throws Exception {
        assertTrue(parser.parse("application/pdf", pdf("PDF policy text")).text().contains("PDF policy"));
        assertTrue(parser.parse("application/vnd.openxmlformats-officedocument.wordprocessingml.document", docx("DOCX policy text")).text().contains("DOCX policy"));
    }

    @Test
    void rejectsInvalidOrUnsupportedSource() {
        assertThrows(IllegalArgumentException.class, () -> parser.parse("application/pdf", new byte[] { 1, 2, 3 }));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("image/png", new byte[] { 1 }));
        assertThrows(IllegalArgumentException.class, () -> parser.parse("text/plain", new byte[BoundedDocumentTextParser.MAX_SOURCE_BYTES + 1]));
        assertEquals(1, parser.parse("text/plain", "plain text".getBytes()).pageCount());
    }

    @Test
    void rejectsExpandedDocxArchivesBeforePoiUnpacksThem() throws Exception {
        byte[] archive;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream(); ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(new byte[33 * 1024 * 1024]);
            zip.closeEntry();
            zip.finish();
            archive = output.toByteArray();
        }

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> parser.parse("application/vnd.openxmlformats-officedocument.wordprocessingml.document", archive)
        );

        assertEquals("DOCX_ARCHIVE_LIMIT_EXCEEDED", exception.getMessage());
    }

    private byte[] pdf(String text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(PDType1Font.HELVETICA, 12);
                stream.newLineAtOffset(72, 720);
                stream.showText(text);
                stream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private byte[] docx(String text) throws Exception {
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        }
    }
}
