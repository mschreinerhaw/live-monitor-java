package com.live.monitor.controller;

import com.live.monitor.dto.AlertChannelPayload;
import com.live.monitor.dto.AlertGroupPayload;
import com.live.monitor.entity.AlertPolicy;
import com.live.monitor.mapper.AlertMapper;
import com.live.monitor.service.AlertAdminService;
import java.util.List;
import java.util.Map;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AlertController {
    private final AlertMapper alertMapper;
    private final AlertAdminService alertAdminService;

    public AlertController(AlertMapper alertMapper, AlertAdminService alertAdminService) {
        this.alertMapper = alertMapper;
        this.alertAdminService = alertAdminService;
    }

    @GetMapping("/api/alert-policies")
    public List<AlertPolicy> policies(@RequestParam(name = "include_disabled", defaultValue = "true") boolean includeDisabled) {
        return alertMapper.listPolicies(includeDisabled ? 1 : 0);
    }

    @GetMapping("/api/alert-channels")
    public List<Map<String, Object>> channels(@RequestParam(name = "include_disabled", defaultValue = "true") boolean includeDisabled) {
        return alertAdminService.listChannels(includeDisabled);
    }

    @PostMapping("/api/alert-channels")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createChannel(@Valid @RequestBody AlertChannelPayload payload) {
        return alertAdminService.createChannel(payload);
    }

    @PutMapping("/api/alert-channels/{channelId}")
    public Map<String, Object> updateChannel(@PathVariable Long channelId, @Valid @RequestBody AlertChannelPayload payload) {
        return alertAdminService.updateChannel(channelId, payload);
    }

    @PostMapping("/api/alert-channels/{channelId}/test")
    public Map<String, Object> testChannel(@PathVariable Long channelId) {
        return alertAdminService.testChannel(channelId);
    }

    @DeleteMapping("/api/alert-channels/{channelId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteChannel(@PathVariable Long channelId) {
        if (!alertAdminService.deleteChannel(channelId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert channel not found");
        }
    }

    @GetMapping("/api/alert-groups")
    public List<Map<String, Object>> groups(@RequestParam(name = "include_disabled", defaultValue = "true") boolean includeDisabled) {
        return alertAdminService.listGroups(includeDisabled);
    }

    @GetMapping("/api/alert-groups/{groupId}")
    public Map<String, Object> group(
        @PathVariable Long groupId,
        @RequestParam(name = "include_secrets", defaultValue = "false") boolean includeSecrets
    ) {
        return alertAdminService.getGroup(groupId, includeSecrets);
    }

    @PostMapping("/api/alert-groups")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> createGroup(@Valid @RequestBody AlertGroupPayload payload) {
        return alertAdminService.createGroup(payload);
    }

    @PutMapping("/api/alert-groups/{groupId}")
    public Map<String, Object> updateGroup(@PathVariable Long groupId, @Valid @RequestBody AlertGroupPayload payload) {
        return alertAdminService.updateGroup(groupId, payload);
    }

    @DeleteMapping("/api/alert-groups/{groupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteGroup(@PathVariable Long groupId) {
        if (!alertAdminService.deleteGroup(groupId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert group not found");
        }
    }

    @GetMapping("/api/alert-configs")
    public List<Map<String, Object>> alertConfigs(@RequestParam(name = "include_disabled", defaultValue = "true") boolean includeDisabled) {
        return alertAdminService.listChannels(includeDisabled);
    }
}
