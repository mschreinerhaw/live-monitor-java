package com.live.monitor.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.live.monitor.entity.ExternalProject;
import com.live.monitor.entity.ExternalProjectAlertGroup;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.mapper.ExternalProjectMapper;
import java.util.Arrays;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class ExternalProjectServiceTest {
    @Test
    void routesMatchedProjectToItsOwnAlertGroupsWithoutMutatingSourceService() {
        ExternalProjectMapper mapper = mock(ExternalProjectMapper.class);
        ExternalProjectService service = new ExternalProjectService(mapper, mock(AlertMapper.class));
        ExternalProject project = new ExternalProject();
        project.id = 8L;
        project.projectName = "IDS3.0-ZYZQ";
        project.matchKeyword = "IDS3.0-ZYZQ";
        ExternalProjectAlertGroup mail = binding(8L, 10L, "生产邮件告警", true);
        ExternalProjectAlertGroup sms = binding(8L, 11L, "短信告警", true);
        MonitorService source = new MonitorService();
        source.id = 1L;
        source.serviceName = "外部 REST 消息转发";
        source.serviceType = "external_message";
        source.alertGroupIds = Collections.singletonList(99L);

        String message = "项目名称:IDS3.0-ZYZQ,流程名称:告警测试,执行状态:success";
        when(mapper.findFirstMatching(message)).thenReturn(project);
        when(mapper.listAlertGroups(8L)).thenReturn(Arrays.asList(mail, sms));

        ExternalProjectService.RoutedProject routed = service.route(message, source);

        assertEquals("IDS3.0-ZYZQ", routed.project.projectName);
        assertEquals(Arrays.asList(10L, 11L), routed.service.alertGroupIds);
        assertEquals(Collections.singletonList(99L), source.alertGroupIds);
        assertNotSame(source, routed.service);
    }

    @Test
    void rejectsMessageWhenNoEnabledProjectMatches() {
        ExternalProjectMapper mapper = mock(ExternalProjectMapper.class);
        ExternalProjectService service = new ExternalProjectService(mapper, mock(AlertMapper.class));
        when(mapper.findFirstMatching("unknown project")).thenReturn(null);

        ResponseStatusException error = assertThrows(
            ResponseStatusException.class,
            () -> service.route("unknown project", new MonitorService())
        );

        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.getStatus());
    }

    private ExternalProjectAlertGroup binding(Long projectId, Long groupId, String name, boolean enabled) {
        ExternalProjectAlertGroup binding = new ExternalProjectAlertGroup();
        binding.projectId = projectId;
        binding.groupId = groupId;
        binding.groupName = name;
        binding.groupEnabled = enabled;
        return binding;
    }
}
