package com.live.monitor.controller;

import com.live.monitor.alert.AlertTaskAdmin;
import com.live.monitor.entity.TUser;
import java.util.*;
import javax.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AlertTaskController {
    private final AlertTaskAdmin admin;
    public AlertTaskController(AlertTaskAdmin admin) { this.admin = admin; }

    @GetMapping("/api/alert-tasks")
    public Map<String, Object> list(@RequestParam(name="service_id", required=false) Long service,
        @RequestParam(name="channel_id", required=false) Long channel,
        @RequestParam(required=false) String status, @RequestParam(name="start_time", required=false) String start,
        @RequestParam(name="end_time", required=false) String end, @RequestParam(defaultValue="1") int page,
        @RequestParam(name="page_size", defaultValue="50") int pageSize) {
        return admin.list(service, channel, status, start, end, page, pageSize);
    }

    @GetMapping("/api/alert-tasks/{id}/attempts")
    public List<Map<String, Object>> attempts(@PathVariable String id) { return admin.attempts(id); }

    @PostMapping("/api/alert-tasks/{id}/retry")
    public Map<String, String> retry(@PathVariable String id, HttpSession session) {
        Object principal = session.getAttribute(AuthController.SESSION_USER);
        if (!(principal instanceof TUser)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        TUser user = (TUser) principal;
        if (!"admin".equalsIgnoreCase(user.userId))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Administrator required");
        return Collections.singletonMap("task_id", admin.retry(id, user.userId));
    }
}
