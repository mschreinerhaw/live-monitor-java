package com.live.monitor.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class ChangePasswordPayload {
    @NotBlank
    @JsonAlias({"current_password"})
    public String currentPassword;

    @NotBlank
    @Size(max = 128)
    @JsonAlias({"new_password"})
    public String newPassword;
}
