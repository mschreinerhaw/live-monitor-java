package com.live.monitor.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@Controller
public class PageController {
    @GetMapping({"/", "/dashboard"})
    public String dashboard() {
        return "forward:/index.html";
    }

    @GetMapping("/services/{serviceId}")
    public String serviceDetail(@PathVariable(required = false) Long serviceId) {
        return "forward:/service.html";
    }

    @GetMapping({"/services/new", "/services/{serviceId}/edit"})
    public String serviceForm(@PathVariable(required = false) Long serviceId) {
        return "forward:/add_service.html";
    }

    @GetMapping("/alerts/settings")
    public String alertSettings() {
        return "forward:/alert_settings.html";
    }

    @GetMapping("/hosts")
    public String hosts() {
        return "forward:/hosts.html";
    }
}
