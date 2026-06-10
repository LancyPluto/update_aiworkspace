package com.aiminilab.aitoolmarket.workflow.dsl;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WorkflowDslValidator {

    public WorkflowDslValidationResult validate(WorkflowDsl dsl) {
        if (dsl == null || dsl.nodes() == null || dsl.nodes().isEmpty()) {
            return WorkflowDslValidationResult.of("Workflow must contain at least one node");
        }
        WorkflowDslValidationResult result = WorkflowDslValidationResult.ok();
        Map<String, WorkflowNodeDef> nodes = dsl.nodeById();

        long startCount = dsl.nodes().stream().filter(node -> node.type() == WorkflowNodeDefType.START).count();
        if (startCount != 1) {
            result = result.merge(WorkflowDslValidationResult.of("Workflow must contain exactly one start node"));
        }

        boolean hasOutput = dsl.nodes().stream().anyMatch(node -> node.type() == WorkflowNodeDefType.VIDEO_OUTPUT);
        if (!hasOutput) {
            result = result.merge(WorkflowDslValidationResult.of("Workflow must contain a video_output node"));
        }

        Set<String> reachable = reachableFromStart(dsl);
        for (WorkflowNodeDef node : dsl.nodes()) {
            if (!reachable.contains(node.id())) {
                result = result.merge(WorkflowDslValidationResult.of("Orphan workflow node is not reachable from start: " + node.id()));
            }
        }

        for (WorkflowEdgeDef edge : dsl.edges()) {
            if (!nodes.containsKey(edge.source()) || !nodes.containsKey(edge.target())) {
                result = result.merge(WorkflowDslValidationResult.of(
                        "Workflow edge references unknown node: " + edge.source() + " -> " + edge.target()));
            }
        }

        if (dsl.executionOrder() == null || dsl.executionOrder().size() != dsl.nodes().size()) {
            result = result.merge(WorkflowDslValidationResult.of("Workflow execution order is invalid or contains a cycle"));
        }

        for (WorkflowNodeDef node : dsl.nodes()) {
            if (node.type().isWorkerStep() && !hasIncomingEdge(dsl, node.id()) && node.type() != WorkflowNodeDefType.START) {
                // parallel branches are allowed; only warn when completely disconnected besides reachability check
            }
            if (node.type().isWorkerStep() && requiresModelConfig(node.type())) {
                if (!node.parameters().hasNonNull("modelConfigId")) {
                    result = result.merge(WorkflowDslValidationResult.of(
                            "Node " + node.id() + " (" + node.type() + ") requires parameters.modelConfigId"));
                }
            }
        }

        return result;
    }

    private boolean requiresModelConfig(WorkflowNodeDefType type) {
        return type == WorkflowNodeDefType.LLM_TEXT
                || type == WorkflowNodeDefType.IMAGE_MODEL
                || type == WorkflowNodeDefType.TTS_MODEL
                || type == WorkflowNodeDefType.VIDEO_MODEL;
    }

    private boolean hasIncomingEdge(WorkflowDsl dsl, String nodeId) {
        return dsl.edges().stream().anyMatch(edge -> nodeId.equals(edge.target()));
    }

    private Set<String> reachableFromStart(WorkflowDsl dsl) {
        String startId = dsl.nodes().stream()
                .filter(node -> node.type() == WorkflowNodeDefType.START)
                .map(WorkflowNodeDef::id)
                .findFirst()
                .orElse(null);
        if (startId == null) {
            return Set.of();
        }
        Set<String> visited = new HashSet<>();
        List<String> queue = new java.util.ArrayList<>();
        queue.add(startId);
        while (!queue.isEmpty()) {
            String current = queue.remove(0);
            if (!visited.add(current)) {
                continue;
            }
            for (WorkflowEdgeDef edge : dsl.edges()) {
                if (current.equals(edge.source()) && !visited.contains(edge.target())) {
                    queue.add(edge.target());
                }
            }
        }
        return visited;
    }
}
