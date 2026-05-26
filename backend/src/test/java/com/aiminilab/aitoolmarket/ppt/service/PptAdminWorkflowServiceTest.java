package com.aiminilab.aitoolmarket.ppt.service;

import com.aiminilab.aitoolmarket.admin.engine.EngineApiSettingsService;
import com.aiminilab.aitoolmarket.admin.service.SystemSettingService;
import com.aiminilab.aitoolmarket.common.exception.BusinessException;
import com.aiminilab.aitoolmarket.ppt.config.PptEngineProperties;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflowStep;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PptAdminWorkflowServiceTest {

    private final EngineApiSettingsService engineApiSettingsService = new EngineApiSettingsService(emptySettings());

    private final PptAdminWorkflowService service = new PptAdminWorkflowService(
            null,
            new PptWorkflowService(null, new PptEngineProperties(), new ObjectMapper()),
            null,
            null,
            engineApiSettingsService,
            new ObjectMapper()
    );

    private static SystemSettingService emptySettings() {
        return new SystemSettingService() {
            @Override
            public Map<String, String> settings() {
                return Map.of();
            }

            @Override
            public Map<String, String> updateSettings(Map<String, String> settings) {
                return Map.of();
            }
        };
    }

    @Test
    void mergesWorkflowIntoExistingConfigNote() {
        String existing = """
                运营说明
                <!-- ppt-workflow:{"integrationMode":"PPT_WORKSPACE","creationTypes":["idea"],"steps":[{"code":"CREATE","name":"创建","credits":5,"enabled":true}]} -->
                """;
        String merged = service.mergeWorkflowIntoConfigNote(existing, sampleWorkflow());

        assertTrue(merged.contains("运营说明"));
        assertTrue(merged.contains("\"OUTLINE\""));
        assertTrue(new PptWorkflowService(null, new PptEngineProperties(), new ObjectMapper())
                .parseWorkflow(merged)
                .map(w -> w.getSteps().size())
                .orElse(0) == 2);
    }

    @Test
    void rejectsEmptySteps() {
        PptWorkflow workflow = new PptWorkflow();
        workflow.setCreationTypes(List.of("idea"));
        workflow.setSteps(List.of());
        assertThrows(BusinessException.class, () -> service.validateWorkflow(workflow));
    }

    private PptWorkflow sampleWorkflow() {
        PptWorkflow workflow = new PptWorkflow();
        workflow.setIntegrationMode("PPT_WORKSPACE");
        workflow.setCreationTypes(List.of("idea"));
        PptWorkflowStep create = new PptWorkflowStep();
        create.setCode("CREATE");
        create.setName("创建");
        create.setCredits(5);
        PptWorkflowStep outline = new PptWorkflowStep();
        outline.setCode("OUTLINE");
        outline.setName("大纲");
        outline.setCredits(10);
        workflow.setSteps(List.of(create, outline));
        return workflow;
    }
}
