package com.aiminilab.aitoolmarket.workflow;

import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDsl;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDslParser;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDslValidationResult;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowDslValidator;
import com.aiminilab.aitoolmarket.workflow.dsl.WorkflowNodeDefType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowDslParserTest {

    private final WorkflowDslParser parser = new WorkflowDslParser(new ObjectMapper());
    private final WorkflowDslValidator validator = new WorkflowDslValidator();

    @Test
    void parsesComicDramaLinearWorkflow() {
        String nodesJson = """
                [
                  {"id":"start","type":"workflowNode","data":{"nodeDefType":"start","title":"Start","parameters":{}}},
                  {"id":"field-input","type":"workflowNode","data":{"nodeDefType":"field_input","title":"Input","parameters":{}}},
                  {"id":"script-planner","type":"workflowNode","data":{"nodeDefType":"llm_text","title":"Script","parameters":{"modelConfigId":1}}},
                  {"id":"keyframe","type":"workflowNode","data":{"nodeDefType":"image_model","title":"Keyframe","parameters":{"modelConfigId":2}}},
                  {"id":"tts","type":"workflowNode","data":{"nodeDefType":"tts_model","title":"TTS","parameters":{"modelConfigId":3}}},
                  {"id":"clip-video","type":"workflowNode","data":{"nodeDefType":"video_model","title":"Video","parameters":{"modelConfigId":4}}},
                  {"id":"compose","type":"workflowNode","data":{"nodeDefType":"subtitle","title":"Compose","parameters":{}}},
                  {"id":"output","type":"workflowNode","data":{"nodeDefType":"video_output","title":"End","parameters":{}}}
                ]
                """;
        String edgesJson = """
                [
                  {"id":"e1","source":"start","target":"field-input"},
                  {"id":"e2","source":"field-input","target":"script-planner"},
                  {"id":"e3","source":"script-planner","target":"keyframe"},
                  {"id":"e4","source":"script-planner","target":"tts"},
                  {"id":"e5","source":"keyframe","target":"clip-video"},
                  {"id":"e6","source":"clip-video","target":"compose"},
                  {"id":"e7","source":"tts","target":"compose"},
                  {"id":"e8","source":"compose","target":"output"}
                ]
                """;

        WorkflowDsl dsl = parser.parse(nodesJson, edgesJson, "{\"workflowType\":\"AI_COMIC_DRAMA\"}");
        assertEquals(8, dsl.nodes().size());
        assertEquals(8, dsl.executionOrder().size());
        assertEquals(WorkflowNodeDefType.START, dsl.requireNode("start").type());
        assertEquals(WorkflowNodeDefType.VIDEO_OUTPUT, dsl.requireNode("output").type());

        WorkflowDslValidationResult validation = validator.validate(dsl);
        assertTrue(validation.valid(), validation.errors().toString());
    }

    @Test
    void rejectsCycle() {
        String nodesJson = """
                [
                  {"id":"a","type":"workflowNode","data":{"nodeDefType":"start","title":"A","parameters":{}}},
                  {"id":"b","type":"workflowNode","data":{"nodeDefType":"field_input","title":"B","parameters":{}}},
                  {"id":"c","type":"workflowNode","data":{"nodeDefType":"video_output","title":"C","parameters":{}}}
                ]
                """;
        String edgesJson = """
                [
                  {"id":"e1","source":"a","target":"b"},
                  {"id":"e2","source":"b","target":"c"},
                  {"id":"e3","source":"c","target":"a"}
                ]
                """;
        try {
            parser.parse(nodesJson, edgesJson, "{}");
            throw new AssertionError("expected cycle detection");
        } catch (IllegalArgumentException exception) {
            assertTrue(exception.getMessage().contains("cycle"));
        }
    }
}
