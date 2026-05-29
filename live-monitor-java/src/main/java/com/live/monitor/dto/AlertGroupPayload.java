package com.live.monitor.dto;

import java.util.ArrayList;
import java.util.List;
import javax.validation.constraints.NotBlank;

public class AlertGroupPayload {
    @NotBlank
    public String groupName;
    public String description;
    public Boolean enabled = true;
    public List<Long> policyIds = new ArrayList<Long>();
    public List<Long> channelIds = new ArrayList<Long>();
}
