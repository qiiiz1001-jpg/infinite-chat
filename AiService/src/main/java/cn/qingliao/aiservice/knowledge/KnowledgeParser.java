package cn.qingliao.aiservice.knowledge;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

@Component
public class KnowledgeParser {
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    public static final int MAX_CHARS = 200000;

    public record Parsed(String filename, long bytes, String sha256, String text) {}

    public Parsed parse(MultipartFile file) {
        if (file == null || file.isEmpty() || file.getSize() > MAX_BYTES)
            throw KnowledgeAccess.invalid("文件不能为空，且不能超过10MB");
        String name = Objects.toString(file.getOriginalFilename(), "").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.isBlank() || name.length() > 200 || name.chars().anyMatch(Character::isISOControl))
            throw KnowledgeAccess.invalid("文件名无效或过长");
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        try {
            byte[] bytes;
            try (var input = file.getInputStream()) {
                bytes = input.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) throw KnowledgeAccess.invalid("文件不能超过10MB");
            String text =
                    switch (extension) {
                        case "txt", "md" ->
                                StandardCharsets.UTF_8
                                        .newDecoder()
                                        .onMalformedInput(CodingErrorAction.REPORT)
                                        .decode(ByteBuffer.wrap(bytes))
                                        .toString();
                        case "pdf" -> pdf(bytes);
                        case "docx" -> docx(bytes);
                        default ->
                                throw KnowledgeAccess.invalid("仅支持 UTF-8 TXT、Markdown、文字型PDF和DOCX");
                    };
            text = text.replace("\u0000", "").strip();
            if (text.isBlank()) throw KnowledgeAccess.invalid("没有可提取的文字；扫描PDF需先自行OCR");
            if (text.length() > MAX_CHARS) throw KnowledgeAccess.invalid("提取文字超过20万字符，请拆分文件");
            String sha =
                    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            return new Parsed(name, bytes.length, sha, text);
        } catch (cn.qingliao.common.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw KnowledgeAccess.invalid("文件解析失败，请检查格式、UTF-8编码或文档加密状态");
        }
    }

    private String pdf(byte[] bytes) throws IOException {
        try (var document = Loader.loadPDF(bytes)) {
            if (document.isEncrypted() || document.getNumberOfPages() > 300)
                throw KnowledgeAccess.invalid("PDF不能加密且最多300页");
            StringWriter writer =
                    new StringWriter() {
                        private void check(int length) {
                            if (getBuffer().length() + length > MAX_CHARS)
                                throw KnowledgeAccess.invalid("提取文字超过20万字符");
                        }

                        @Override
                        public void write(String text) {
                            check(text.length());
                            super.write(text);
                        }

                        @Override
                        public void write(String text, int offset, int length) {
                            check(length);
                            super.write(text, offset, length);
                        }

                        @Override
                        public void write(int value) {
                            check(1);
                            super.write(value);
                        }

                        @Override
                        public void write(char[] chars, int offset, int length) {
                            check(length);
                            super.write(chars, offset, length);
                        }
                    };
            new PDFTextStripper().writeText(document, writer);
            return writer.toString();
        }
    }

    /** 只读取Word正文XML，不解压到磁盘，不解析外部实体或外部链接。 */
    private String docx(byte[] bytes) throws Exception {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int count = 0, expanded = 0;
            for (ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                if (++count > 1000) throw KnowledgeAccess.invalid("DOCX条目过多");
                byte[] xml = zip.readNBytes(4 * 1024 * 1024 + 1);
                if (xml.length > 4 * 1024 * 1024) throw KnowledgeAccess.invalid("DOCX正文解压后过大");
                expanded += xml.length;
                if (expanded > 12 * 1024 * 1024) throw KnowledgeAccess.invalid("DOCX解压内容过大");
                if (!"word/document.xml".equals(entry.getName())) continue;
                var factory = XMLInputFactory.newFactory();
                factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
                factory.setProperty("javax.xml.stream.isSupportingExternalEntities", false);
                var reader = factory.createXMLStreamReader(new ByteArrayInputStream(xml));
                StringBuilder text = new StringBuilder();
                boolean inText = false;
                try {
                    while (reader.hasNext()) {
                        int event = reader.next();
                        if (event == XMLStreamConstants.DTD)
                            throw KnowledgeAccess.invalid("DOCX不允许DTD");
                        if (event == XMLStreamConstants.START_ELEMENT
                                && "t".equals(reader.getLocalName())) inText = true;
                        if (event == XMLStreamConstants.CHARACTERS && inText)
                            text.append(reader.getText());
                        if (event == XMLStreamConstants.END_ELEMENT) {
                            if ("t".equals(reader.getLocalName())) inText = false;
                            if (Set.of("p", "tr").contains(reader.getLocalName()))
                                text.append('\n');
                        }
                        if (text.length() > MAX_CHARS) throw KnowledgeAccess.invalid("提取文字超过20万字符");
                    }
                } finally {
                    reader.close();
                }
                return text.toString();
            }
        }
        throw KnowledgeAccess.invalid("DOCX缺少正文");
    }
}
