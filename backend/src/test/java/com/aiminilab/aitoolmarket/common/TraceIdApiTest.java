package com.aiminilab.aitoolmarket.common;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:trace_id_api_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:schema-test.sql"
})
class TraceIdApiTest {

    private static final String REQUEST_ID_HEADER = "X-Request-Id";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void responseContainsRequestIdHeader() throws Exception {
        mockMvc.perform(get("/api/v1/ping"))
                .andExpect(status().isOk())
                .andExpect(header().string(REQUEST_ID_HEADER, not(blankOrNullString())));
    }

    @Test
    void errorResponseContainsSameTraceId() throws Exception {
        String requestId = "trace-test_123";

        mockMvc.perform(post("/api/v1/auth/login")
                        .header(REQUEST_ID_HEADER, requestId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "account": "user1",
                                  "password": "wrong-password"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(REQUEST_ID_HEADER, requestId))
                .andExpect(jsonPath("$.traceId").value(requestId));
    }

    @Test
    void providedValidRequestIdIsPreserved() throws Exception {
        String requestId = "valid-id_123";

        mockMvc.perform(get("/api/v1/ping")
                        .header(REQUEST_ID_HEADER, requestId))
                .andExpect(status().isOk())
                .andExpect(header().string(REQUEST_ID_HEADER, requestId));
    }

    @Test
    void invalidRequestIdIsReplaced() throws Exception {
        mockMvc.perform(get("/api/v1/ping")
                        .header(REQUEST_ID_HEADER, "unsafe id!"))
                .andExpect(status().isOk())
                .andExpect(header().string(REQUEST_ID_HEADER, not("unsafe id!")))
                .andExpect(header().string(REQUEST_ID_HEADER,
                        matchesPattern("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")));
    }
}
