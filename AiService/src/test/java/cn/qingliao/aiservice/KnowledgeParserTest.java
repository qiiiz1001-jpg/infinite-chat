package cn.qingliao.aiservice;

import static org.junit.jupiter.api.Assertions.*;

import cn.qingliao.aiservice.knowledge.KnowledgeParser;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.*;

class KnowledgeParserTest {
    private final KnowledgeParser parser = new KnowledgeParser();

    private MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "application/octet-stream", bytes);
    }

    @Test
    void utf8MarkdownAndFingerprintAreStable() {
        var bytes = "# 接口\n登录使用邮箱和密码".getBytes(StandardCharsets.UTF_8);
        var first = parser.parse(file("../api.md", bytes));
        assertEquals("api.md", first.filename());
        assertTrue(first.text().contains("邮箱"));
        assertEquals(first.sha256(), parser.parse(file("another.txt", bytes)).sha256());
    }

    @Test
    void rejectsUnknownTypesEmptyInvalidUtf8AndExcessiveText() {
        assertThrows(RuntimeException.class, () -> parser.parse(file("a.exe", new byte[] {1})));
        assertThrows(RuntimeException.class, () -> parser.parse(file("a.txt", new byte[0])));
        assertThrows(
                RuntimeException.class,
                () -> parser.parse(file("a.txt", new byte[] {(byte) 0xff})));
        assertThrows(
                RuntimeException.class,
                () ->
                        parser.parse(
                                file("a.md", "x".repeat(200001).getBytes(StandardCharsets.UTF_8))));
        assertThrows(
                RuntimeException.class,
                () -> parser.parse(file("a.md", new byte[KnowledgeParser.MAX_BYTES + 1])));
    }

    private byte[] docx(String xml) throws Exception {
        var out = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return out.toByteArray();
    }

    @Test
    void docxExtractsParagraphsAndTableCells() throws Exception {
        var xml =
                "<w:document xmlns:w='urn:word'><w:body><w:p><w:r><w:t>项目资料</w:t></w:r></w:p>"
                    + "<w:tbl><w:tr><w:tc><w:p><w:r><w:t>登录接口</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>";
        assertTrue(parser.parse(file("api.docx", docx(xml))).text().contains("项目资料\n登录接口"));
    }

    @Test
    void docxRefusesExternalEntitiesAndExpansion() throws Exception {
        var xml =
                "<!DOCTYPE x [<!ENTITY stolen SYSTEM"
                        + " 'file:///nonexistent-secret'>]><document><t>&stolen;</t></document>";
        assertThrows(RuntimeException.class, () -> parser.parse(file("evil.docx", docx(xml))));
        assertThrows(
                RuntimeException.class,
                () -> parser.parse(file("large.docx", docx("x".repeat(4 * 1024 * 1024 + 1)))));
    }

    @Test
    void pdfExtractsActualTextAndRejectsImageOnlyDocument() throws Exception {
        var out = new ByteArrayOutputStream();
        try (var doc = new PDDocument()) {
            var page = new PDPage();
            doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(20, 700);
                content.showText("Project API reference");
                content.endText();
            }
            doc.save(out);
        }
        assertTrue(
                parser.parse(file("api.pdf", out.toByteArray()))
                        .text()
                        .contains("Project API reference"));
        out.reset();
        try (var doc = new PDDocument()) {
            doc.addPage(new PDPage());
            doc.save(out);
        }
        assertThrows(
                RuntimeException.class, () -> parser.parse(file("scan.pdf", out.toByteArray())));
    }
}
