package com.live.monitor.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping({
        "/",
        "/dashboard",
        "/hosts",
        "/hosts/**",
        "/databases",
        "/external-projects",
        "/external-requests",
        "/account",
        "/services/**",
        "/data-send",
        "/alerts/**",
        "/admin",
        "/login"
    })
    public String spaEntry() {
        return "forward:/index.html";
    }
}
