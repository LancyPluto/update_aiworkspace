package com.aiminilab.aitoolmarket.ppt;

import com.aiminilab.aitoolmarket.ppt.config.PptEngineProperties;
import com.aiminilab.aitoolmarket.ppt.service.PptWorkflowService;
import com.aiminilab.aitoolmarket.ppt.workflow.PptWorkflow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PptWorkflowServiceTest {

    private final PptWorkflowService service = new PptWorkflowService(
            null,
            new PptEngineProperties(),
            new ObjectMapper()
    );

    @Test
    void parsesWorkflowFromConfigNote() {
        String configNote = """
                运营说明
                <!-- ppt-workflow:{"integrationMode":"PPT_WORKSPACE","customUiRoute":"/tools/banana_ppt_generator/workspace","creationTypes":["idea"],"steps":[{"code":"CREATE","name":"创建","credits":5,"enabled":true},{"code":"OUTLINE","name":"大纲","credits":10,"enabled":true}],"features":{"renovation":false}} -->
                """;
        Optional<PptWorkflow> workflow = service.parseWorkflow(configNote);
        assertTrue(workflow.isPresent());
        assertEquals("PPT_WORKSPACE", workflow.get().getIntegrationMode());
        assertEquals(2, workflow.get().getSteps().size());
        assertEquals(10, workflow.get().getSteps().get(1).getCredits());
    }

    @Test
    void returnsEmptyWhenMarkerMissing() {
        assertTrue(service.parseWorkflow("plain note").isEmpty());
    }
}
