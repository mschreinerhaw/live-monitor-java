package com.live.monitor.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.Size;

public class CreateUserPayload {
    @NotBlank
    @Size(max = 50)
    @JsonAlias({"user_id", "userid", "username"})
    public String userId;

    @NotBlank
    @Size(max = 128)
    public String password;

    @Size(max = 30)
    public String name;

    public Boolean enabled = true;
}
