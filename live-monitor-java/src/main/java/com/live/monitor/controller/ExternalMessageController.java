package com.live.monitor.controller;

import com.live.monitor.service.ExternalMessageRelayService;
import java.util.Map;
import javax.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/external/messages")
public class ExternalMessageController {
    private final ExternalMessageRelayService relayService;

    public ExternalMessageController(ExternalMessageRelayService relayService) {
        this.relayService = relayService;
    }

    @PostMapping(consumes = MediaType.ALL_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> forward(
        @RequestBody(required = false) String requestBody,
        HttpServletRequest request
    ) {
        return relayService.forward(requestBody, request.getContentType(), clientIp(request));
    }

    @PutMapping("/status")
    public Map<String, Object> status(@RequestBody Map<String, Object> payload, HttpServletRequest request) {
        Object value = payload.get("enabled");
        if (!(value instanceof Boolean)) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_REQUEST,
                "enabled must be true or false"
            );
        }
        return relayService.setEnabled((Boolean) value, clientIp(request));
    }

    @GetMapping("/audit")
    public Map<String, Object> audit(
        @RequestParam(defaultValue = "1") Integer page,
        @RequestParam(name = "page_size", defaultValue = "20") Integer pageSize,
        @RequestParam(required = false) String status,
        @RequestParam(required = false) String query
    ) {
        return relayService.auditPage(page, pageSize, status, query);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.trim().isEmpty()) {
            int comma = forwarded.indexOf(',');
            return (comma < 0 ? forwarded : forwarded.substring(0, comma)).trim();
        }
        return request.getRemoteAddr();
    }
}
