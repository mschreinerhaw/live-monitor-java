package com.live.monitor.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;

public class RuleTestPayload {
    @NotBlank
    public String expression;

    @Min(100)
    @Max(599)
    public Integer statusCode = 200;

    @Min(0)
    public Integer responseTimeMs = 0;

    public String body;
}
