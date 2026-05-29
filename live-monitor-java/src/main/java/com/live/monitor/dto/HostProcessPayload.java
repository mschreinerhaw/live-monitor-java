package com.live.monitor.dto;

import javax.validation.constraints.NotBlank;

public class HostProcessPayload {
    @NotBlank
    public String processName;
    public String matchKeyword;
    @NotBlank
    public String checkCommand;
    public Boolean enabled = true;
}
