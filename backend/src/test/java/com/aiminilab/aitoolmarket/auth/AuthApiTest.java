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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
        "app.auth.sms.bmob-application-id=",
        "app.auth.sms.bmob-rest-api-key=",
        "app.generated-media-dir=target/test-generated-media"
})
class AuthApiTest {

    private static final String DEFAULT_AVATAR_URL =
            "https://wlcloudai-assets-public.oss-cn-guangzhou.aliyuncs.com/assets/default-user-avatar.svg";
    private static final String DEFAULT_DISPLAY_NAME_PATTERN = "^用户\\d{5}$";
    private static final String REFERRAL_CODE_PATTERN = "^(?=.*[2-9])(?=.*[A-HJ-NP-Z])[2-9A-HJ-NP-Z]{6}$";
    private static final AtomicInteger PHONE_SEQUENCE = new AtomicInteger(1000);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
                .andExpect(jsonPath("$.data.user.publicCode").value(org.hamcrest.Matchers.matchesPattern("^[1-9]\\d{4}$")))
                .andExpect(jsonPath("$.data.user.referralCode").value(org.hamcrest.Matchers.matchesPattern(REFERRAL_CODE_PATTERN)))
                .andExpect(jsonPath("$.data.user.username").value("new_user"))
                .andExpect(jsonPath("$.data.user.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.avatarUrl").value(DEFAULT_AVATAR_URL))
                .andExpect(jsonPath("$.data.user.userType").value("USER"))
                .andReturn();

        String registerToken = AuthTestTokens.userJwtFrom(registerResult);
        String publicCode = objectMapper.readTree(registerResult.getResponse().getContentAsString())
                .path("data").path("user").path("publicCode").asText();
        String referralCode = objectMapper.readTree(registerResult.getResponse().getContentAsString())
                .path("data").path("user").path("referralCode").asText();

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + registerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.publicCode").value(publicCode))
                .andExpect(jsonPath("$.data.referralCode").value(referralCode))
                .andExpect(jsonPath("$.data.username").value("new_user"))
                .andExpect(jsonPath("$.data.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.avatarUrl").value(DEFAULT_AVATAR_URL))
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
                .andExpect(jsonPath("$.data.user.publicCode").value(publicCode))
                .andExpect(jsonPath("$.data.user.referralCode").value(referralCode))
                .andExpect(jsonPath("$.data.user.username").value("new_user"))
                .andExpect(jsonPath("$.data.user.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.avatarUrl").value(DEFAULT_AVATAR_URL))
                .andReturn();

        String token = AuthTestTokens.userJwtFrom(loginResult);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.publicCode").value(publicCode))
                .andExpect(jsonPath("$.data.referralCode").value(referralCode))
                .andExpect(jsonPath("$.data.username").value("new_user"))
                .andExpect(jsonPath("$.data.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.avatarUrl").value(DEFAULT_AVATAR_URL))
                .andExpect(jsonPath("$.data.userType").value("USER"));
    }

    @Test
    void updatesCurrentUserProfileAndReturnsAvatarUrl() throws Exception {
        var registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "profile_user",
                                  "password": "123456",
                                  "nickname": "Profile User"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String token = AuthTestTokens.userJwtFrom(registerResult);

        mockMvc.perform(patch("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "nickname": "Community Builder",
                                  "avatarUrl": null
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.nickname").value("Community Builder"))
                .andExpect(jsonPath("$.data.avatarUrl").value(DEFAULT_AVATAR_URL));
    }

    @Test
    void uploadsCurrentUserAvatarAndPersistsIt() throws Exception {
        var registerResult = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "username": "avatar_user",
                                  "password": "123456",
                                  "nickname": "Avatar User"
                                }
                                """))
                .andExpect(status().isOk())
                .andReturn();
        String token = AuthTestTokens.userJwtFrom(registerResult);

        MockMultipartFile avatar = new MockMultipartFile(
                "file",
                "avatar.png",
                "image/png",
                new byte[]{(byte) 0x89, 'P', 'N', 'G'}
        );

        mockMvc.perform(multipart("/api/v1/users/me/avatar")
                        .file(avatar)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.avatarUrl").value(org.hamcrest.Matchers.startsWith("/generated/avatars/")))
                .andExpect(jsonPath("$.data.user.avatarUrl").value(org.hamcrest.Matchers.startsWith("/generated/avatars/")));

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value(org.hamcrest.Matchers.startsWith("/generated/avatars/")));
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
                .andExpect(jsonPath("$.data.user.username").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.username").value(not("13800138000")))
                .andExpect(jsonPath("$.data.user.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.nickname").value(not("13800138000")))
                .andExpect(jsonPath("$.data.user.avatarUrl").value(DEFAULT_AVATAR_URL))
                .andExpect(jsonPath("$.data.user.phone").value("13800138000"))
                .andExpect(jsonPath("$.data.user.userType").value("USER"))
                .andReturn();

        String registerToken = AuthTestTokens.userJwtFrom(registerResult);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + registerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.username").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.username").value(not("13800138000")))
                .andExpect(jsonPath("$.data.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.nickname").value(not("13800138000")))
                .andExpect(jsonPath("$.data.avatarUrl").value(DEFAULT_AVATAR_URL))
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
                .andExpect(jsonPath("$.data.user.username").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.username").value(not("13800138000")))
                .andExpect(jsonPath("$.data.user.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.nickname").value(not("13800138000")))
                .andExpect(jsonPath("$.data.user.avatarUrl").value(DEFAULT_AVATAR_URL))
                .andExpect(jsonPath("$.data.user.phone").value("13800138000"));
    }

    @Test
    void registersAndLogsInUserBySmsCode() throws Exception {
        String phone = uniquePhone();
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
                .andExpect(jsonPath("$.data.user.username").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.username").value(not(phone)))
                .andExpect(jsonPath("$.data.user.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.nickname").value(not(phone)))
                .andExpect(jsonPath("$.data.user.avatarUrl").value(DEFAULT_AVATAR_URL))
                .andExpect(jsonPath("$.data.user.phone").value(phone))
                .andReturn();

        String registerToken = AuthTestTokens.userJwtFrom(registerResult);

        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + registerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.username").value(not(phone)))
                .andExpect(jsonPath("$.data.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.nickname").value(not(phone)))
                .andExpect(jsonPath("$.data.avatarUrl").value(DEFAULT_AVATAR_URL))
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
                .andExpect(jsonPath("$.data.user.username").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.username").value(not(phone)))
                .andExpect(jsonPath("$.data.user.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.nickname").value(not(phone)))
                .andExpect(jsonPath("$.data.user.avatarUrl").value(DEFAULT_AVATAR_URL))
                .andExpect(jsonPath("$.data.user.phone").value(phone));
    }

    @Test
    void loginOrRegisterSmsCodeCreatesUserWhenPhoneIsNew() throws Exception {
        String phone = uniquePhone();
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
                .andExpect(jsonPath("$.data.user.username").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.username").value(not(phone)))
                .andExpect(jsonPath("$.data.user.nickname").value(org.hamcrest.Matchers.matchesPattern(DEFAULT_DISPLAY_NAME_PATTERN)))
                .andExpect(jsonPath("$.data.user.nickname").value(not(phone)))
                .andExpect(jsonPath("$.data.user.avatarUrl").value(DEFAULT_AVATAR_URL))
                .andExpect(jsonPath("$.data.user.phone").value(phone));
    }

    @Test
    void resetsPasswordBySmsCode() throws Exception {
        String phone = uniquePhone();
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
        String phone = uniquePhone();
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
    void authSecurityAuditRecordsPasswordLoginSuccessAndFailureWithoutPlainPhone() throws Exception {
        String phone = uniquePhone();
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "phone": "%s",
                                  "password": "123456"
                                }
                                """.formatted(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", "8.8.8.8")
                        .header("User-Agent", "AuthApiTest/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "123456"
                                }
                                """.formatted(phone)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .header("X-Forwarded-For", "8.8.8.8")
                        .header("User-Agent", "AuthApiTest/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "%s",
                                  "password": "wrong-password"
                                }
                                """.formatted(phone)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));

        String maskedPhone = phone.substring(0, 3) + "****" + phone.substring(7);
        Integer successCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM auth_security_events
                WHERE event_type = 'LOGIN_PASSWORD'
                  AND result = 'SUCCESS'
                  AND account_masked = ?
                  AND account_hash IS NOT NULL
                  AND ip_address = '8.8.8.8'
                """, Integer.class, maskedPhone);
        Integer failureCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM auth_security_events
                WHERE event_type = 'LOGIN_PASSWORD'
                  AND result = 'FAILED'
                  AND account_masked = ?
                  AND failure_reason = 'UNAUTHORIZED'
                  AND account_hash IS NOT NULL
                  AND ip_address = '8.8.8.8'
                """, Integer.class, maskedPhone);
        Integer plainPhoneCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM auth_security_events
                WHERE account_masked = ?
                """, Integer.class, phone);

        org.junit.jupiter.api.Assertions.assertTrue(successCount != null && successCount >= 1);
        org.junit.jupiter.api.Assertions.assertTrue(failureCount != null && failureCount >= 1);
        org.junit.jupiter.api.Assertions.assertEquals(0, plainPhoneCount);
    }

    @Test
    void rejectsUnauthenticatedCurrentUserRequest() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
    }

    private String uniquePhone() {
        int suffix = PHONE_SEQUENCE.getAndIncrement();
        return "13800" + String.format("%06d", suffix);
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
