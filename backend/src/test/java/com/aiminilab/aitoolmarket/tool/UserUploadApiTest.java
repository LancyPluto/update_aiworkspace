package com.aiminilab.aitoolmarket.tool;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import com.aiminilab.aitoolmarket.storage.PrivateAssetAccessService;
import com.aiminilab.aitoolmarket.storage.AssetStorageService;
import com.aiminilab.aitoolmarket.storage.StoredAsset;
import com.aiminilab.aitoolmarket.tool.entity.UserUploadAsset;
import com.aiminilab.aitoolmarket.tool.mapper.UserUploadAssetMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:user_upload_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.generated-media-dir=target/test-generated-media-upload"
})
class UserUploadApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private AssetStorageService assetStorageService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private UserUploadAssetMapper userUploadAssetMapper;

    @Autowired
    private PrivateAssetAccessService privateAssetAccessService;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void uploadAssetsSupportPaging() throws Exception {
        String userToken = loginUser();
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(multipart("/api/v1/tool-upload")
                            .file(new MockMultipartFile(
                                    "file",
                                    "picker-" + i + ".png",
                                    "image/png",
                                    new byte[]{(byte) i, 2, 3}))
                            .header("Authorization", "Bearer " + userToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value("SUCCESS"))
                    .andExpect(jsonPath("$.data.historyVisible").value(true));
        }

        mockMvc.perform(get("/api/v1/upload-assets")
                        .header("Authorization", "Bearer " + userToken)
                        .param("pageNo", "1")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.list.length()").value(2))
                .andExpect(jsonPath("$.data.pageNo").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(true));

        mockMvc.perform(get("/api/v1/upload-assets")
                        .header("Authorization", "Bearer " + userToken)
                        .param("pageNo", "2")
                        .param("pageSize", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.list.length()").value(1))
                .andExpect(jsonPath("$.data.pageNo").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    @Test
    void hiddenUploadIsExcludedFromHistoryButRemainsPrivatelyAccessible() throws Exception {
        String userToken = loginUser();
        MvcResult uploadResult = mockMvc.perform(multipart("/api/v1/tool-upload")
                        .file(new MockMultipartFile(
                                "file",
                                "hidden-reference.mp4",
                                "video/mp4",
                                new byte[]{1, 2, 3}))
                        .param("retainHistory", "false")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.historyVisible").value(false))
                .andReturn();

        JsonNode uploadData = objectMapper.readTree(uploadResult.getResponse().getContentAsString()).path("data");
        UserUploadAsset asset = userUploadAssetMapper.selectById(uploadData.path("assetId").asLong());
        String relativeKey = privateAssetAccessService.privateRelativeKey(uploadData.path("url").asText());

        assertThat(asset).isNotNull();
        assertThat(asset.getStatus()).isEqualTo("ACTIVE");
        assertThat(asset.getHistoryVisible()).isFalse();
        assertThat(relativeKey).isNotBlank();
        assertThat(privateAssetAccessService.canAccess(asset.getUserId(), relativeKey)).isTrue();

        mockMvc.perform(get("/api/v1/upload-assets")
                        .header("Authorization", "Bearer " + userToken)
                        .param("kind", "video")
                        .param("pageNo", "1")
                        .param("pageSize", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list.length()").value(0));
    }

    @Test
    void databaseRollbackRemovesNewUniqueUpload() {
        AtomicReference<Path> storedPath = new AtomicReference<>();

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            StoredAsset stored = assetStorageService.storeMultipartPrivateUnique(
                    "uploads/rollback-" + UUID.randomUUID() + ".txt",
                    new MockMultipartFile("file", "rollback.txt", "text/plain", "temporary".getBytes())
            );
            storedPath.set(Path.of(stored.storagePath()));
            assertThat(storedPath.get()).exists();
            status.setRollbackOnly();
        });

        assertThat(storedPath.get()).isNotNull();
        assertThat(Files.exists(storedPath.get())).isFalse();
    }

    private String loginUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "user1",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        return AuthTestTokens.userJwtFrom(result);
    }
}
