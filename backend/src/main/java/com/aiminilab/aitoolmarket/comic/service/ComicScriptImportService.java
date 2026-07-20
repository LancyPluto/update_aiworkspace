package com.aiminilab.aitoolmarket.comic.service;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Service
public class ComicScriptImportService {
    static final long MAX_FILE_BYTES = 5L * 1024 * 1024;
    static final int MAX_SCRIPT_CHARS = 500_000;
    private static final int MAX_DOCUMENT_XML_BYTES = 10 * 1024 * 1024;
    private static final Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "markdown");

    public ImportedScript parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("请选择剧本文件");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED, "剧本文件不能超过 5MB");
        }
        String filename = safeFilename(file.getOriginalFilename());
        String extension = extension(filename);
        try {
            String text;
            String sourceType;
            if (TEXT_EXTENSIONS.contains(extension)) {
                text = decodeUtf8(file.getBytes());
                sourceType = extension.equals("txt") ? "TXT" : "MARKDOWN";
            } else if (extension.equals("docx")) {
                text = parseDocx(file.getBytes());
                sourceType = "DOCX";
            } else {
                throw new BusinessException(ErrorCode.FILE_TYPE_NOT_ALLOWED,
                        "仅支持 TXT、Markdown 和 DOCX 剧本文件");
            }
            text = normalize(text);
            if (text.isBlank()) {
                throw invalid("剧本文件没有可用文字");
            }
            if (text.length() > MAX_SCRIPT_CHARS) {
                throw new BusinessException(ErrorCode.FILE_SIZE_EXCEEDED, "剧本文字不能超过 50 万字");
            }
            return new ImportedScript(filename, sourceType, text);
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("剧本文件解析失败");
        }
    }

    private String parseDocx(byte[] bytes) throws Exception {
        byte[] documentXml = null;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = input.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains("../")) {
                    throw invalid("DOCX 文件包含非法路径");
                }
                if (!"word/document.xml".equals(name)) {
                    continue;
                }
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_DOCUMENT_XML_BYTES) {
                        throw invalid("DOCX 正文过大");
                    }
                    output.write(buffer, 0, read);
                }
                documentXml = output.toByteArray();
                break;
            }
        }
        if (documentXml == null) {
            throw invalid("DOCX 缺少正文");
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        Document document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(documentXml));
        NodeList paragraphs = document.getElementsByTagNameNS(
                "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "p");
        StringBuilder result = new StringBuilder();
        for (int index = 0; index < paragraphs.getLength(); index++) {
            Node paragraph = paragraphs.item(index);
            NodeList textNodes = ((org.w3c.dom.Element) paragraph).getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/wordprocessingml/2006/main", "t");
            StringBuilder line = new StringBuilder();
            for (int textIndex = 0; textIndex < textNodes.getLength(); textIndex++) {
                line.append(textNodes.item(textIndex).getTextContent());
            }
            if (!line.toString().isBlank()) {
                if (!result.isEmpty()) {
                    result.append('\n');
                }
                result.append(line);
            }
        }
        return result.toString();
    }

    private String decodeUtf8(byte[] bytes) {
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (!value.isEmpty() && value.charAt(0) == '\uFEFF') {
            value = value.substring(1);
        }
        if (value.indexOf('\0') >= 0) {
            throw invalid("文本文件不是有效的 UTF-8 剧本");
        }
        return value;
    }

    private String normalize(String value) {
        return value.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private String safeFilename(String original) {
        String value = original == null ? "" : original.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        value = slash >= 0 ? value.substring(slash + 1) : value;
        if (value.isBlank() || value.length() > 255) {
            throw invalid("剧本文件名无效");
        }
        return value;
    }

    private String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private BusinessException invalid(String message) {
        return new BusinessException(ErrorCode.PARAM_ERROR, message);
    }

    public record ImportedScript(String filename, String sourceType, String text) {
    }
}
