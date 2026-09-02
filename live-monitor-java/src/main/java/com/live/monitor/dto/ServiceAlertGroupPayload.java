package com.live.monitor.dto;

import java.util.List;

public class ServiceAlertGroupPayload {
    public Long alertGroupId;
    /** Preferred multi-group binding; when present overrides {@link #alertGroupId}. */
    public List<Long> alertGroupIds;
}
