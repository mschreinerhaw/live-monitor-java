package com.live.monitor.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.dto.AlertChannelPayload;
import com.live.monitor.dto.AlertGroupPayload;
import com.live.monitor.entity.AlertChannel;
import com.live.monitor.entity.AlertGroup;
import com.live.monitor.mapper.AlertMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AlertAdminService {
    private static final String[] SECRET_FIELDS = {
        "smtp_password", "sms_api_token", "sms_password", "sms_password_md5"
    };

    private final AlertMapper alertMapper;
    private final ObjectMapper objectMapper;

    public AlertAdminService(AlertMapper alertMapper, ObjectMapper objectMapper) {
        this.alertMapper = alertMapper;
        this.objectMapper = objectMapper;
    }

    public List<Map<String, Object>> listChannels(boolean includeDisabled) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AlertChannel channel : alertMapper.listChannels(includeDisabled ? 1 : 0)) {
            result.add(channelToMap(channel, false));
        }
        return result;
    }

    @Transactional
    public Map<String, Object> createChannel(AlertChannelPayload payload) {
        AlertChannel channel = new AlertChannel();
        channel.channelName = payload.channelName;
        channel.channelType = payload.channelType;
        channel.enabled = payload.enabled == null || payload.enabled;
        channel.configJson = toJson(channelConfig(payload, null));
        alertMapper.insertChannel(channel);
        return channelToMap(alertMapper.findChannel(channel.id), false);
    }

    @Transactional
    public Map<String, Object> updateChannel(Long id, AlertChannelPayload payload) {
        AlertChannel existing = alertMapper.findChannel(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert channel not found");
        }
        AlertChannel channel = new AlertChannel();
        channel.id = id;
        channel.channelName = payload.channelName;
        channel.channelType = payload.channelType;
        channel.enabled = payload.enabled == null || payload.enabled;
        channel.configJson = toJson(channelConfig(payload, parseJson(existing.configJson)));
        alertMapper.updateChannel(channel);
        return channelToMap(alertMapper.findChannel(id), false);
    }

    public boolean deleteChannel(Long id) {
        return alertMapper.deleteChannel(id) > 0;
    }

    public List<Map<String, Object>> listGroups(boolean includeDisabled) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (AlertGroup group : alertMapper.listGroups(includeDisabled ? 1 : 0)) {
            result.add(groupToMap(group));
        }
        return result;
    }

    public Map<String, Object> getGroup(Long id) {
        AlertGroup group = alertMapper.findGroup(id);
        if (group == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert group not found");
        }
        return groupToMap(group);
    }

    @Transactional
    public Map<String, Object> createGroup(AlertGroupPayload payload) {
        AlertGroup group = new AlertGroup();
        group.groupName = payload.groupName;
        group.description = payload.description;
        group.enabled = payload.enabled == null || payload.enabled;
        alertMapper.insertGroup(group);
        syncGroupRelations(group.id, payload);
        return getGroup(group.id);
    }

    @Transactional
    public Map<String, Object> updateGroup(Long id, AlertGroupPayload payload) {
        AlertGroup existing = alertMapper.findGroup(id);
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "alert group not found");
        }
        existing.groupName = payload.groupName;
        existing.description = payload.description;
        existing.enabled = payload.enabled == null || payload.enabled;
        alertMapper.updateGroup(existing);
        syncGroupRelations(id, payload);
        return getGroup(id);
    }

    public boolean deleteGroup(Long id) {
        return alertMapper.deleteGroup(id) > 0;
    }

    private void syncGroupRelations(Long groupId, AlertGroupPayload payload) {
        alertMapper.deleteGroupPolicies(groupId);
        alertMapper.deleteGroupChannels(groupId);
        for (Long policyId : payload.policyIds == null ? new ArrayList<Long>() : payload.policyIds) {
            alertMapper.insertGroupPolicy(groupId, policyId);
        }
        for (Long channelId : payload.channelIds == null ? new ArrayList<Long>() : payload.channelIds) {
            alertMapper.insertGroupChannel(groupId, channelId);
        }
    }

    private Map<String, Object> groupToMap(AlertGroup group) {
        Map<String, Object> item = new HashMap<String, Object>();
        item.put("id", group.id);
        item.put("group_name", group.groupName);
        item.put("description", group.description);
        item.put("enabled", group.enabled);
        item.put("created_at", group.createdAt);
        List<Long> policyIds = new ArrayList<Long>();
        List<Long> channelIds = new ArrayList<Long>();
        List<Object> policies = new ArrayList<Object>();
        List<Map<String, Object>> channels = new ArrayList<Map<String, Object>>();
        for (Object policy : alertMapper.listPoliciesByGroup(group.id)) {
            com.live.monitor.entity.AlertPolicy p = (com.live.monitor.entity.AlertPolicy) policy;
            policyIds.add(p.id);
            policies.add(p);
        }
        for (AlertChannel channel : alertMapper.listChannelsByGroup(group.id)) {
            channelIds.add(channel.id);
            channels.add(channelToMap(channel, false));
        }
        item.put("policy_ids", policyIds);
        item.put("channel_ids", channelIds);
        item.put("policies", policies);
        item.put("channels", channels);
        item.put("service_count", alertMapper.countServicesByGroup(group.id));
        return item;
    }

    private Map<String, Object> channelToMap(AlertChannel channel, boolean includeSecrets) {
        Map<String, Object> item = new HashMap<String, Object>();
        item.put("id", channel.id);
        item.put("channel_name", channel.channelName);
        item.put("channel_type", channel.channelType);
        item.put("enabled", channel.enabled);
        item.put("created_at", channel.createdAt);
        item.putAll(parseJson(channel.configJson));
        if (!includeSecrets) {
            for (String field : SECRET_FIELDS) {
                if (item.containsKey(field)) {
                    item.put(field, null);
                }
            }
        }
        return item;
    }

    private Map<String, Object> channelConfig(AlertChannelPayload payload, Map<String, Object> existing) {
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("alert_email", payload.alertEmail);
        config.put("alert_mobile", payload.alertMobile);
        config.put("smtp_host", payload.smtpHost);
        config.put("smtp_port", payload.smtpPort);
        config.put("smtp_user", payload.smtpUser);
        config.put("smtp_password", payload.smtpPassword);
        config.put("smtp_from", payload.smtpFrom);
        config.put("smtp_use_tls", payload.smtpUseTls == null ? false : payload.smtpUseTls);
        config.put("sms_api_url", payload.smsApiUrl);
        config.put("sms_api_token", payload.smsApiToken);
        config.put("sms_username", payload.smsUsername);
        config.put("sms_password", payload.smsPassword);
        config.put("sms_password_is_md5", payload.smsPasswordIsMd5 == null ? true : payload.smsPasswordIsMd5);
        config.put("sms_password_md5", payload.smsPasswordMd5);
        config.put("sms_rstype", payload.smsRstype);
        config.put("sms_ext_code", payload.smsExtCode);
        config.put("webhook_url", payload.webhookUrl);
        if (existing != null) {
            for (String field : SECRET_FIELDS) {
                if (config.get(field) == null && existing.containsKey(field)) {
                    config.put(field, existing.get(field));
                }
            }
        }
        return config;
    }

    private Map<String, Object> parseJson(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new HashMap<String, Object>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ex) {
            return new HashMap<String, Object>();
        }
    }

    private String toJson(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to serialize channel config", ex);
        }
    }
}
