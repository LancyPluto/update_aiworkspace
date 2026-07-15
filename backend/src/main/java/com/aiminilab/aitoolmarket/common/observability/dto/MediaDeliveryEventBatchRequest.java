package com.aiminilab.aitoolmarket.common.observability.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public class MediaDeliveryEventBatchRequest {
    @NotEmpty @Size(max = 50)
    private List<@Valid MediaDeliveryEventRequest> events;

    public List<MediaDeliveryEventRequest> getEvents() { return events; }
    public void setEvents(List<MediaDeliveryEventRequest> events) { this.events = events; }
}
