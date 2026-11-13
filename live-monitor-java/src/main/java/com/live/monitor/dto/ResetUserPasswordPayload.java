package com.live.monitor.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class ResetUserPasswordPayload {
    @NotBlank
    @Size(max = 128)
    @JsonAlias({"new_password", "password"})
    public String newPassword;
}
