package com.live.monitor.dto;

import javax.validation.constraints.NotNull;

public class UpdateUserStatusPayload {
    @NotNull
    public Boolean enabled;
}
