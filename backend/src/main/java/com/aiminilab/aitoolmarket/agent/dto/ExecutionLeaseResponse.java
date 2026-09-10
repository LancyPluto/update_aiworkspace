package com.aiminilab.aitoolmarket.agent.dto;

public record ExecutionLeaseResponse(boolean acquired, boolean renewed) {
    public static ExecutionLeaseResponse acquired(boolean value) { return new ExecutionLeaseResponse(value, false); }
    public static ExecutionLeaseResponse renewed(boolean value) { return new ExecutionLeaseResponse(false, value); }
}
