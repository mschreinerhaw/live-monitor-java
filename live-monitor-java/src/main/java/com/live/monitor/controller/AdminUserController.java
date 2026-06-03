package com.live.monitor.controller;

import com.live.monitor.dto.ChangePasswordPayload;
import com.live.monitor.dto.CreateUserPayload;
import com.live.monitor.dto.ResetUserPasswordPayload;
import com.live.monitor.entity.LoginAuditLog;
import com.live.monitor.entity.TUser;
import com.live.monitor.service.LoginAuditLogService;
import com.live.monitor.service.UserAdminService;
import java.util.List;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class AdminUserController {
    private final UserAdminService userAdminService;
    private final LoginAuditLogService loginAuditLogService;

    public AdminUserController(UserAdminService userAdminService,
                               LoginAuditLogService loginAuditLogService) {
        this.userAdminService = userAdminService;
        this.loginAuditLogService = loginAuditLogService;
    }

    @GetMapping("/api/admin/users")
    public List<TUser> users(HttpSession session) {
        return userAdminService.listUsers(currentUser(session));
    }

    @GetMapping("/api/admin/audit-logs")
    public List<LoginAuditLog> auditLogs(HttpSession session) {
        return loginAuditLogService.listRecent(currentUser(session));
    }

    @PostMapping("/api/admin/users")
    @ResponseStatus(HttpStatus.CREATED)
    public TUser createUser(HttpSession session, @Valid @RequestBody CreateUserPayload payload) {
        return userAdminService.createUser(currentUser(session), payload);
    }

    @PutMapping("/api/admin/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void changePassword(HttpSession session, @Valid @RequestBody ChangePasswordPayload payload) {
        userAdminService.changeOwnPassword(currentUser(session), payload);
    }

    @PutMapping("/api/admin/users/{userId}/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resetUserPassword(HttpSession session,
                                  @PathVariable String userId,
                                  @Valid @RequestBody ResetUserPasswordPayload payload) {
        userAdminService.resetUserPassword(currentUser(session), userId, payload);
    }

    private TUser currentUser(HttpSession session) {
        Object user = session.getAttribute(AuthController.SESSION_USER);
        if (user instanceof TUser) {
            return (TUser) user;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
}
