package com.aiminilab.aitoolmarket.common.observability.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class WebVitalMetricRequest {
    @NotBlank
    @Size(max = 32)
    private String name;

    @DecimalMin("0.0")
    @DecimalMax("600000.0")
    private double value;

    @Size(max = 32)
    private String rating;

    @Size(max = 256)
    private String page;

    @Size(max = 64)
    private String navigationType;

    @Size(max = 128)
    private String id;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public double getValue() { return value; }
    public void setValue(double value) { this.value = value; }
    public String getRating() { return rating; }
    public void setRating(String rating) { this.rating = rating; }
    public String getPage() { return page; }
    public void setPage(String page) { this.page = page; }
    public String getNavigationType() { return navigationType; }
    public void setNavigationType(String navigationType) { this.navigationType = navigationType; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
}
