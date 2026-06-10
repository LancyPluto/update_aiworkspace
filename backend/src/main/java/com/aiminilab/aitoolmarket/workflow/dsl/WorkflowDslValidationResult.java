package com.aiminilab.aitoolmarket.workflow.dsl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class WorkflowDslValidationResult {

    private final List<String> errors;

    public WorkflowDslValidationResult(List<String> errors) {
        this.errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static WorkflowDslValidationResult ok() {
        return new WorkflowDslValidationResult(List.of());
    }

    public static WorkflowDslValidationResult of(String... errors) {
        return new WorkflowDslValidationResult(List.of(errors));
    }

    public boolean valid() {
        return errors.isEmpty();
    }

    public List<String> errors() {
        return errors;
    }

    public String firstError() {
        return errors.isEmpty() ? null : errors.get(0);
    }

    public WorkflowDslValidationResult merge(WorkflowDslValidationResult other) {
        if (other == null || other.errors.isEmpty()) {
            return this;
        }
        List<String> merged = new ArrayList<>(errors);
        merged.addAll(other.errors);
        return new WorkflowDslValidationResult(Collections.unmodifiableList(merged));
    }
}
