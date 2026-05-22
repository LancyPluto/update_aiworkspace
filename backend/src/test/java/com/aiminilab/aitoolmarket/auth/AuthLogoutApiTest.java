package com.aiminilab.aitoolmarket.auth;

import com.aiminilab.aitoolmarket.auth.security.TokenDenylistService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_logout_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class AuthLogoutApiTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private TokenDenylistService tokenDenylistService;

    @Test
    void logoutInvalidatesCurrentToken() throws Exception {
        Set<String> deniedTokenIds = new HashSet<>();
        Mockito.doAnswer(invocation -> {
                    deniedTokenIds.add(invocation.getArgument(0));
                    return null;
                })
                .when(tokenDenylistService)
                .deny(anyString(), any(Duration.class));
        Mockito.when(tokenDenylistService.isDenied(anyString()))
                .thenAnswer(invocation -> deniedTokenIds.contains(invocation.getArgument(0)));

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "user1",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken", not(blankOrNullString())))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = loginResponse.replaceAll("(?s).*\\\"accessToken\\\"\\s*:\\s*\\\"([^\\\"]+)\\\".*", "$1");

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));
    }

    @Test
    void logoutWithSessionCookieRevokesTokenAndClearsCookie() throws Exception {
        Set<String> deniedTokenIds = new HashSet<>();
        Mockito.doAnswer(invocation -> {
                    deniedTokenIds.add(invocation.getArgument(0));
                    return null;
                })
                .when(tokenDenylistService)
                .deny(anyString(), any(Duration.class));
        Mockito.when(tokenDenylistService.isDenied(anyString()))
                .thenAnswer(invocation -> deniedTokenIds.contains(invocation.getArgument(0)));

        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "user1",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        var sessionCookie = loginResult.getResponse().getCookie("ATM_USER_SESSION");

        mockMvc.perform(get("/api/v1/users/me")
                        .cookie(sessionCookie))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(sessionCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(cookie().maxAge("ATM_USER_SESSION", 0));

        mockMvc.perform(get("/api/v1/users/me")
                        .cookie(sessionCookie))
                .andExpect(status().isUnauthorized());
    }
}
