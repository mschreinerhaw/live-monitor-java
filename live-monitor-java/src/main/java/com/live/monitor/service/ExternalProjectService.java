package com.live.monitor.service;

import com.live.monitor.dto.ExternalProjectPayload;
import com.live.monitor.entity.AlertGroup;
import com.live.monitor.entity.ExternalProject;
import com.live.monitor.entity.ExternalProjectAlertGroup;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.mapper.ExternalProjectMapper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ExternalProjectService {
    private final ExternalProjectMapper mapper;
    private final AlertMapper alertMapper;

    public ExternalProjectService(ExternalProjectMapper mapper, AlertMapper alertMapper) {
        this.mapper = mapper;
        this.alertMapper = alertMapper;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (ExternalProject project : mapper.list()) result.add(toMap(project));
        return result;
    }

    @Transactional
    public Map<String, Object> create(ExternalProjectPayload payload) {
        ExternalProject project = values(null, payload);
        ensureUnique(project.projectName, -1L);
        mapper.insert(project);
        replaceGroups(project.id, payload.alertGroupIds);
        return toMap(mapper.findById(project.id));
    }

    @Transactional
    public Map<String, Object> update(Long id, ExternalProjectPayload payload) {
        if (mapper.findById(id) == null) throw notFound();
        ExternalProject project = values(id, payload);
        ensureUnique(project.projectName, id);
        mapper.update(project);
        replaceGroups(id, payload.alertGroupIds);
        return toMap(mapper.findById(id));
    }

    @Transactional
    public void delete(Long id) {
        if (mapper.findById(id) == null) throw notFound();
        mapper.deleteAlertGroups(id);
        mapper.delete(id);
    }

    public RoutedProject route(String message, MonitorService source) {
        ExternalProject project = mapper.findFirstMatching(message);
        if (project == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "no enabled external project matched the message");
        }
        List<ExternalProjectAlertGroup> bindings = mapper.listAlertGroups(project.id);
        if (bindings.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "matched project has no alert group bound: " + project.projectName);
        }
        MonitorService routed = routingCopy(source);
        routed.alertGroupIds = new ArrayList<Long>();
        routed.alertGroupNames = new ArrayList<String>();
        boolean enabled = false;
        for (ExternalProjectAlertGroup binding : bindings) {
            routed.alertGroupIds.add(binding.groupId);
            routed.alertGroupNames.add(binding.groupName);
            if (Boolean.TRUE.equals(binding.groupEnabled)) enabled = true;
        }
        routed.alertGroupId = routed.alertGroupIds.get(0);
        routed.alertGroupName = routed.alertGroupNames.get(0);
        routed.alertGroupEnabled = enabled;
        return new RoutedProject(project, routed);
    }

    private ExternalProject values(Long id, ExternalProjectPayload payload) {
        ExternalProject project = new ExternalProject();
        project.id = id;
        project.projectName = payload.projectName.trim();
        project.matchKeyword = StringUtils.hasText(payload.matchKeyword)
            ? payload.matchKeyword.trim() : project.projectName;
        if (!StringUtils.hasText(project.matchKeyword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "match_keyword must not be blank");
        }
        project.description = StringUtils.hasText(payload.description) ? payload.description.trim() : null;
        project.enabled = payload.enabled == null || payload.enabled;
        return project;
    }

    private void ensureUnique(String projectName, Long excludeId) {
        if (mapper.findDuplicateName(projectName, excludeId) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "external project name already exists");
        }
    }

    private void replaceGroups(Long projectId, List<Long> rawIds) {
        Set<Long> ids = new LinkedHashSet<Long>();
        if (rawIds != null) {
            for (Long id : rawIds) if (id != null) ids.add(id);
        }
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "at least one alert group is required");
        }
        for (Long id : ids) {
            AlertGroup group = alertMapper.findGroup(id);
            if (group == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "alert group not found: " + id);
        }
        mapper.deleteAlertGroups(projectId);
        for (Long id : ids) mapper.insertAlertGroup(projectId, id);
    }

    private Map<String, Object> toMap(ExternalProject project) {
        List<ExternalProjectAlertGroup> bindings = mapper.listAlertGroups(project.id);
        List<Long> ids = new ArrayList<Long>();
        List<String> names = new ArrayList<String>();
        for (ExternalProjectAlertGroup binding : bindings) {
            ids.add(binding.groupId);
            names.add(binding.groupName);
        }
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", project.id);
        result.put("project_name", project.projectName);
        result.put("match_keyword", project.matchKeyword);
        result.put("description", project.description);
        result.put("enabled", project.enabled);
        result.put("alert_group_ids", ids);
        result.put("alert_group_names", names);
        result.put("created_at", project.createdAt);
        result.put("updated_at", project.updatedAt);
        return result;
    }

    private MonitorService routingCopy(MonitorService source) {
        MonitorService copy = new MonitorService();
        copy.id = source.id;
        copy.serviceName = source.serviceName;
        copy.serviceCategory = source.serviceCategory;
        copy.serviceType = source.serviceType;
        copy.clusterName = source.clusterName;
        copy.enabled = source.enabled;
        return copy;
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "external project not found");
    }

    public static class RoutedProject {
        public final ExternalProject project;
        public final MonitorService service;

        RoutedProject(ExternalProject project, MonitorService service) {
            this.project = project;
            this.service = service;
        }
    }
}
