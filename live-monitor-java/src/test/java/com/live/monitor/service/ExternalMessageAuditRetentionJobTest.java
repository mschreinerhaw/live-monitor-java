package com.live.monitor.service;

import static org.mockito.ArgumentMatchers.matches;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.mapper.ExternalMessageAuditMapper;
import org.junit.jupiter.api.Test;

class ExternalMessageAuditRetentionJobTest {
    @Test
    void purgesRecordsUsingConfiguredRetentionWindow() {
        LiveMonitorProperties properties = new LiveMonitorProperties();
        properties.setExternalMessageAuditRetentionDays(30);
        ExternalMessageAuditMapper mapper = mock(ExternalMessageAuditMapper.class);
        ExternalMessageAuditRetentionJob job = new ExternalMessageAuditRetentionJob(properties, mapper);

        job.runDaily();

        verify(mapper).deleteBefore(matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
    }
}
