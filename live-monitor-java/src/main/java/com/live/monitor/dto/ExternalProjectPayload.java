package com.live.monitor.dto;

import java.util.List;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;

public class ExternalProjectPayload {
    @NotBlank(message = "project_name is required")
    @Size(max = 255, message = "project_name must not exceed 255 characters")
    public String projectName;

    @Size(max = 255, message = "match_keyword must not exceed 255 characters")
    public String matchKeyword;

    @Size(max = 1000, message = "description must not exceed 1000 characters")
    public String description;

    public Boolean enabled;

    @NotEmpty(message = "alert_group_ids must not be empty")
    public List<Long> alertGroupIds;
}
