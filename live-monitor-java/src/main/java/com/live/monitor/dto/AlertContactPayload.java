package com.live.monitor.dto;

import javax.validation.constraints.*;

public class AlertContactPayload {
    @NotBlank @Size(max=120) public String name;
    @Email @Size(max=255) public String email;
    @Pattern(regexp="^$|\\+?[0-9]{5,20}") public String mobile;
    @Size(max=120) public String wecomUser;
    @Size(max=1000) public String description;
    public Boolean enabled = true;
}
