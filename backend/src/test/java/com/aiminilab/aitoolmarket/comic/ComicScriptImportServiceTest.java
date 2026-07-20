package com.aiminilab.aitoolmarket.comic;

import com.aiminilab.aitoolmarket.comic.service.ComicScriptImportService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComicScriptImportServiceTest {
    private final ComicScriptImportService service = new ComicScriptImportService();

    @Test
    void importsUtf8TextAndMarkdown() {
        var text = service.parse(new MockMultipartFile(
                "file", "episode.txt", "text/plain", "第一幕\r\n第二幕".getBytes(StandardCharsets.UTF_8)
        ));
        var markdown = service.parse(new MockMultipartFile(
                "file", "episode.md", "text/markdown", "# 第一集\n对白".getBytes(StandardCharsets.UTF_8)
        ));

        assertThat(text.sourceType()).isEqualTo("TXT");
        assertThat(text.text()).isEqualTo("第一幕\n第二幕");
        assertThat(markdown.sourceType()).isEqualTo("MARKDOWN");
    }

    @Test
    void importsDocxParagraphsWithoutExternalXmlAccess() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                  <w:body><w:p><w:r><w:t>场景一</w:t></w:r></w:p>
                  <w:p><w:r><w:t>角色对白</w:t></w:r></w:p></w:body>
                </w:document>
                """;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(xml.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }

        var imported = service.parse(new MockMultipartFile(
                "file", "episode.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                bytes.toByteArray()
        ));

        assertThat(imported.sourceType()).isEqualTo("DOCX");
        assertThat(imported.text()).isEqualTo("场景一\n角色对白");
    }

    @Test
    void rejectsUnsupportedFiles() {
        assertThatThrownBy(() -> service.parse(new MockMultipartFile(
                "file", "episode.pdf", "application/pdf", new byte[]{1, 2, 3}
        ))).isInstanceOf(BusinessException.class)
                .hasMessageContaining("TXT");
    }
}
