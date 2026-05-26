package com.aiminilab.aitoolmarket.auth;

import com.aiminilab.aitoolmarket.auth.security.AuthTestTokens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:auth_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql",
        "app.auth.sms.provider=local",
        "app.auth.sms.ihuyi-api-id=",
        "app.auth.sms.ihuyi-api-key=",
        "app.auth.sms.bmob-application-id=",
        "app.auth.sms.bmob-rest-api-key="
})
class AuthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @BeforeEach
    void clearSmsState() {
        try {
            for (String prefix : new String[]{"auth:sms:code:*", "auth:sms:cooldown:*"}) {
                Set<String> keys = redisTemplate.keys(prefix);
                if (keys != null && !keys.isEmpty()) {
                    redisTemplate.delete(keys);
                }
            }
        } catch (RuntimeException ignored) {
            // Redis may not be available in some CI environments; tests still cover happy paths.
        }
    }

    @Test
    void registersAndLogsInUserThenReturnsCurrentUser() throws Exception {
        var registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "new_user",
                                  "password": "123456",
                                  "nickname": "New User"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.user.id", notNullValue()))
                .andExpect(jsonPath("$.data.user.username").value("new_user"))
                .andExpect(jsonPath("$.data.user.userType").value("USER"))
                .andReturn();

        String registerToken = AuthTestTokens.userJwtFrom(registerResult);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + registerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.username").value("new_user"))
                .andExpect(jsonPath("$.data.userType").value("USER"));

        var loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "new_user",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.user.username").value("new_user"))
                .andReturn();

        String token = AuthTestTokens.userJwtFrom(loginResult);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.username").value("new_user"))
                .andExpect(jsonPath("$.data.userType").value("USER"));
    }

    @Test
    void registersAndLogsInUserByPhone() throws Exception {
        var registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "13800138000",
                                  "password": "123456",
                                  "nickname": "Phone User"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.user.id", notNullValue()))
                .andExpect(jsonPath("$.data.user.username").value("13800138000"))
                .andExpect(jsonPath("$.data.user.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.user.userType").value("USER"))
                .andReturn();

        String registerToken = AuthTestTokens.userJwtFrom(registerResult);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + registerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.username").value("13800138000"))
                .andExpect(jsonPath("$.data.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.userType").value("USER"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "13800138000",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.user.username").value("13800138000"))
                .andExpect(jsonPath("$.data.user.phone").value("13800138000"));
    }

    @Test
    void registersAndLogsInUserBySmsCode() throws Exception {
        String phone = "13900139000";
        String registerCode = sendSmsCode(phone, "REGISTER");

        var registerResult = mockMvc.perform(post("/api/v1/auth/sms-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "%s",
                                  "code": "%s",
                                  "nickname": "Sms User"
                                }
                                """.formatted(phone, registerCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.user.username").value(phone))
                .andExpect(jsonPath("$.data.user.phone").value(phone))
                .andReturn();

        String registerToken = AuthTestTokens.userJwtFrom(registerResult);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + registerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone").value(phone));

        String loginCode = sendSmsCode(phone, "LOGIN");
        mockMvc.perform(post("/api/v1/auth/sms-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "%s",
                                  "code": "%s"
                                }
                                """.formatted(phone, loginCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.user.phone").value(phone));
    }

    @Test
    void loginOrRegisterSmsCodeCreatesUserWhenPhoneIsNew() throws Exception {
        String phone = "13600136000";
        String code = sendSmsCode(phone, "LOGIN_OR_REGISTER");

        mockMvc.perform(post("/api/v1/auth/sms-login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "%s",
                                  "code": "%s"
                                }
                                """.formatted(phone, code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.user.username").value(phone))
                .andExpect(jsonPath("$.data.user.phone").value(phone));
    }

    @Test
    void resetsPasswordBySmsCode() throws Exception {
        String phone = "13500135000";
        String registerCode = sendSmsCode(phone, "REGISTER");
        mockMvc.perform(post("/api/v1/auth/sms-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "%s",
                                  "code": "%s",
                                  "password": "oldpass123"
                                }
                                """.formatted(phone, registerCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        String resetCode = sendSmsCode(phone, "RESET_PASSWORD");
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "%s",
                                  "code": "%s",
                                  "password": "newpass123"
                                }
                                """.formatted(phone, resetCode)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "newpass123"
                                }
                                """.formatted(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.user.phone").value(phone));
    }

    @Test
    void rejectsInvalidSmsCode() throws Exception {
        String phone = "13700137000";
        sendSmsCode(phone, "REGISTER");

        mockMvc.perform(post("/api/v1/auth/sms-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "%s",
                                  "code": "000000"
                                }
                                """.formatted(phone)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"));
    }

    @Test
    void adminLoginCanAccessAdminMeButUserTokenCannot() throws Exception {
        var adminLoginResult = mockMvc.perform(post("/api/admin/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "admin",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.user.userType").value("ADMIN"))
                .andReturn();

        String adminToken = AuthTestTokens.adminJwtFrom(adminLoginResult);

        mockMvc.perform(get("/api/admin/v1/auth/me")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.userType").value("ADMIN"));

        var userLoginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "user1",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String userToken = AuthTestTokens.userJwtFrom(userLoginResult);

        mockMvc.perform(get("/api/admin/v1/auth/me")
                        .header("Authorization", "Bearer " + userToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_FORBIDDEN"));
    }

    @Test
    void rejectsDuplicateRegistrationAndWrongPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "user1",
                                  "password": "123456",
                                  "nickname": "Duplicate User"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("PARAM_ERROR"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "user1",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    @Test
    void rejectsUnauthenticatedCurrentUserRequest() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String sendSmsCode(String phone, String scene) throws Exception {
        var result = mockMvc.perform(post("/api/v1/auth/sms-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "%s",
                                  "scene": "%s"
                                }
                                """.formatted(phone, scene)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.debugCode", notNullValue()))
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        return root.path("data").path("debugCode").asText();
    }
}
