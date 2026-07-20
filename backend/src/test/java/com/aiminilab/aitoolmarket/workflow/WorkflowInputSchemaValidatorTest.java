package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.common.enums.ErrorCode;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.tool.entity.ToolWorkflowVersion;
import com.aiminilab.aitoolmarket.workflow.service.WorkflowInputSchemaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkflowInputSchemaValidatorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final WorkflowInputSchemaValidator validator = new WorkflowInputSchemaValidator(objectMapper);

    @Test
    void validatesSupportedTypesEnumsAndObjectItemsWithoutProperties() throws Exception {
        ToolWorkflowVersion version = version("""
                {
                  "type":"object",
                  "properties":{
                    "text":{"type":"string","enum":["allowed"]},
                    "amount":{"type":"number"},
                    "count":{"type":"integer"},
                    "enabled":{"type":"boolean"},
                    "labels":{"type":"array","items":{"type":"string"}},
                    "metadata":{"type":"object"},
                    "subjects":{"type":"array","items":{"type":"object"}}
                  },
                  "required":["text","amount","count","enabled","labels","metadata","subjects"]
                }
                """);

        assertThatCode(() -> validator.validate(version, objectMapper.readTree("""
                {
                  "text":"allowed",
                  "amount":1.5,
                  "count":2,
                  "enabled":true,
                  "labels":["one"],
                  "metadata":{"freeForm":true},
                  "subjects":[{"name":"hero"}]
                }
                """))).doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validate(version, objectMapper.readTree("""
                {
                  "text":"blocked",
                  "amount":1.5,
                  "count":2,
                  "enabled":true,
                  "labels":["one"],
                  "metadata":{},
                  "subjects":[]
                }
                """)))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR);
                    assertThat(exception.getMessage()).contains("$.text is not an allowed value");
                });
    }

    @Test
    void rejectsExplicitNullForTypedOptionalField() throws Exception {
        ToolWorkflowVersion version = version("""
                {
                  "type":"object",
                  "properties":{"optionalText":{"type":"string"}}
                }
                """);

        assertThatCode(() -> validator.validate(version, objectMapper.readTree("{}")))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validate(
                version,
                objectMapper.readTree("{\"optionalText\":null}")
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.PARAM_ERROR);
            assertThat(exception.getMessage()).contains("$.optionalText must be string");
        });
    }

    private ToolWorkflowVersion version(String inputSchema) {
        ToolWorkflowVersion version = new ToolWorkflowVersion();
        version.setId(1L);
        version.setInputSchemaSnapshotJson(inputSchema);
        return version;
    }
}
