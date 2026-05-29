package com.live.monitor.controller;

import com.live.monitor.dto.LoginPayload;
import com.live.monitor.entity.TUser;
import com.live.monitor.service.AuthService;
import java.util.HashMap;
import java.util.Map;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AuthController {
    public static final String SESSION_USER = "LOGIN_USER";

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/api/auth/login")
    public Map<String, Object> login(@Valid @RequestBody LoginPayload payload, HttpSession session) {
        TUser user = authService.authenticate(payload.username, payload.password);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
        }
        session.setAttribute(SESSION_USER, user);
        return currentUser(user, true);
    }

    @PostMapping("/api/auth/logout")
    public Map<String, Object> logout(HttpSession session) {
        session.invalidate();
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("authenticated", false);
        return result;
    }

    @GetMapping("/api/auth/me")
    public Map<String, Object> me(HttpSession session) {
        Object user = session.getAttribute(SESSION_USER);
        return currentUser(user instanceof TUser ? (TUser) user : null, user instanceof TUser);
    }

    private Map<String, Object> currentUser(TUser user, boolean authenticated) {
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("authenticated", authenticated);
        if (user != null) {
            result.put("user_id", user.userId);
            result.put("name", user.name == null ? user.userId : user.name);
        }
        return result;
    }
}
