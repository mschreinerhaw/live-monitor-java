package com.live.monitor.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import javax.validation.constraints.NotBlank;

public class LoginPayload {
    @NotBlank
    @JsonAlias({"account", "user_id", "userid"})
    public String username;

    @NotBlank
    public String password;
}
