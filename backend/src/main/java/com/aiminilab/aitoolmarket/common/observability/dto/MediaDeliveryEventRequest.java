package com.aiminilab.aitoolmarket.common.observability.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class MediaDeliveryEventRequest {
    @NotBlank @Pattern(regexp = "/(home|dashboard|community|library|tools|other)")
    private String page;
    @NotBlank @Pattern(regexp = "image|video|audio")
    private String mediaKind;
    @NotBlank @Pattern(regexp = "derivative|original|poster|preview")
    private String stage;
    @NotBlank @Pattern(regexp = "loaded|fallback|failed")
    private String outcome;
    @Min(1) @Max(1000)
    private int count;

    public String getPage() { return page; }
    public void setPage(String page) { this.page = page; }
    public String getMediaKind() { return mediaKind; }
    public void setMediaKind(String mediaKind) { this.mediaKind = mediaKind; }
    public String getStage() { return stage; }
    public void setStage(String stage) { this.stage = stage; }
    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }
    public int getCount() { return count; }
    public void setCount(int count) { this.count = count; }
}
