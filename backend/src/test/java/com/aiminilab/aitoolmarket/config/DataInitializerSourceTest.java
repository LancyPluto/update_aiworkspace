package com.aiminilab.aitoolmarket.config;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DataInitializerSourceTest {

    @Test
    void defaultImageToolSupportsOptionalSourceImageForImageToImage() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/aiminilab/aitoolmarket/config/DataInitializer.java"));
        int start = source.indexOf("private void seedDefaultTextToImageTool()");
        int end = source.indexOf("private void seedAgnesTextToVideoField", start);
        String defaultImageToolSource = source.substring(start, end);

        assertThat(defaultImageToolSource).contains("sourceImageUrl");
        assertThat(defaultImageToolSource).contains("seedToolField(\"gpt_image_text_to_image\", \"sourceImageUrl\"");
        assertThat(defaultImageToolSource).contains("input_modality = 'MULTIMODAL'");
        assertThat(defaultImageToolSource).contains("未上传图片走文生图，上传图片走图文生图");
    }
}
