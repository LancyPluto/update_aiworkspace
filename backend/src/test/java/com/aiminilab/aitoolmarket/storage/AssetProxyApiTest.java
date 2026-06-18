package com.aiminilab.aitoolmarket.storage;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:asset_proxy_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class AssetProxyApiTest {

    private static final String RELATIVE_KEY = "uploads/20260618/owner-private.png";
    private static final String PRIVATE_PATH = "/api/v1/assets/private/" + RELATIVE_KEY;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUpOwnedAsset() {
        jdbcTemplate.update("DELETE FROM user_upload_assets WHERE file_id = ?", "asset-proxy-owner");
        jdbcTemplate.update("""
                INSERT INTO user_upload_assets(
                  user_id, file_id, asset_kind, original_filename, content_type,
                  file_size, url, storage_path, status, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """,
                2L,
                "asset-proxy-owner",
                "image",
                "owner-private.png",
                "image/png",
                3L,
                PRIVATE_PATH,
                "oss://wlcloudai-assets-private/" + RELATIVE_KEY
        );
    }

    @Test
    void privateAssetRequiresLogin() throws Exception {
        mockMvc.perform(get(PRIVATE_PATH))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ownerCanReceiveRedirectForPrivateAsset() throws Exception {
        String ownerToken = login("user1", "123456");

        mockMvc.perform(get(PRIVATE_PATH)
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "/generated/" + RELATIVE_KEY));
    }

    @Test
    void anotherUserCannotReceiveRedirectForPrivateAsset() throws Exception {
        String otherToken = register("asset_proxy_other");

        mockMvc.perform(get(PRIVATE_PATH)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void ownerCannotReceiveRedirectForUnknownPrivateAsset() throws Exception {
        String ownerToken = login("user1", "123456");

        mockMvc.perform(get("/api/v1/assets/private/uploads/20260618/not-owned.png")
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isNotFound());
    }

    private String login(String username, String password) throws Exception {
        return AuthTestTokens.userJwtFrom(mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "%s"
                                }
                                """.formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn());
    }

    private String register(String username) throws Exception {
        return AuthTestTokens.userJwtFrom(mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "%s",
                                  "password": "123456",
                                  "nickname": "Other User"
                                }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn());
    }
}
