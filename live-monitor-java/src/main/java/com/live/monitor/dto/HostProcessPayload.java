package com.live.monitor.dto;

import javax.validation.constraints.NotBlank;

public class HostProcessPayload {
    @NotBlank
    public String processName;
    @NotBlank
    public String matchKeyword;
    public Boolean enabled = true;
}
