package com.aiminilab.aitoolmarket.workflow.dsl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WorkflowDslParser {

    private final ObjectMapper objectMapper;

    public WorkflowDslParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public WorkflowDsl parse(String nodesJson, String edgesJson, String configJson) {
        try {
            JsonNode nodes = objectMapper.readTree(nodesJson);
            JsonNode edges = objectMapper.readTree(edgesJson);
            JsonNode config = configJson == null || configJson.isBlank()
                    ? objectMapper.createObjectNode()
                    : objectMapper.readTree(configJson);
            if (!nodes.isArray()) {
                throw new IllegalArgumentException("nodesJson must be a JSON array");
            }
            if (!edges.isArray()) {
                throw new IllegalArgumentException("edgesJson must be a JSON array");
            }

            List<WorkflowNodeDef> nodeDefs = new ArrayList<>();
            for (JsonNode node : nodes) {
                nodeDefs.add(parseNode(node));
            }
            List<WorkflowEdgeDef> edgeDefs = new ArrayList<>();
            for (JsonNode edge : edges) {
                edgeDefs.add(parseEdge(edge));
            }
            List<String> executionOrder = topologicalOrder(nodeDefs, edgeDefs);
            return new WorkflowDsl(nodeDefs, edgeDefs, config, executionOrder);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid workflow DSL: " + exception.getMessage(), exception);
        }
    }

    private WorkflowNodeDef parseNode(JsonNode node) {
        String id = text(node, "id");
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Workflow node id is required");
        }
        JsonNode data = node.path("data");
        String rawNodeDefType = text(data, "nodeDefType");
        if (rawNodeDefType == null || rawNodeDefType.isBlank()) {
            rawNodeDefType = text(data, "kind");
        }
        final String nodeDefType = rawNodeDefType;
        WorkflowNodeDefType type = WorkflowNodeDefType.parse(nodeDefType)
                .orElseThrow(() -> new IllegalArgumentException("Unsupported nodeDefType for node " + id + ": " + nodeDefType))
                .normalized();
        String title = text(data, "title");
        JsonNode parameters = data.path("parameters");
        if (parameters.isMissingNode() || parameters.isNull()) {
            parameters = objectMapper.createObjectNode();
        }
        return new WorkflowNodeDef(id, type, title, parameters);
    }

    private WorkflowEdgeDef parseEdge(JsonNode edge) {
        String source = text(edge, "source");
        String target = text(edge, "target");
        if (source == null || source.isBlank() || target == null || target.isBlank()) {
            throw new IllegalArgumentException("Workflow edge source/target are required");
        }
        return new WorkflowEdgeDef(
                text(edge, "id"),
                source,
                target,
                text(edge, "sourceHandle"),
                text(edge, "targetHandle")
        );
    }

    private List<String> topologicalOrder(List<WorkflowNodeDef> nodes, List<WorkflowEdgeDef> edges) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        for (WorkflowNodeDef node : nodes) {
            indegree.put(node.id(), 0);
            outgoing.put(node.id(), new ArrayList<>());
        }
        for (WorkflowEdgeDef edge : edges) {
            if (!indegree.containsKey(edge.source()) || !indegree.containsKey(edge.target())) {
                throw new IllegalArgumentException("Workflow edge references unknown node: " + edge.source() + " -> " + edge.target());
            }
            outgoing.get(edge.source()).add(edge.target());
            indegree.put(edge.target(), indegree.get(edge.target()) + 1);
        }

        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                queue.add(entry.getKey());
            }
        }

        List<String> order = new ArrayList<>();
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            order.add(current);
            for (String next : outgoing.getOrDefault(current, List.of())) {
                int updated = indegree.get(next) - 1;
                indegree.put(next, updated);
                if (updated == 0) {
                    queue.addLast(next);
                }
            }
        }
        if (order.size() != nodes.size()) {
            throw new IllegalArgumentException("Workflow graph contains a cycle");
        }
        return order;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            return value.asText();
        }
        return value.toString();
    }
}
