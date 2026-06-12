package com.live.monitor.controller;

import com.live.monitor.dto.AuditLogPageResponse;
import com.live.monitor.dto.ChangePasswordPayload;
import com.live.monitor.dto.CreateUserPayload;
import com.live.monitor.dto.ResetUserPasswordPayload;
import com.live.monitor.dto.UpdateUserStatusPayload;
import com.live.monitor.entity.TUser;
import com.live.monitor.service.LoginAuditLogService;
import com.live.monitor.service.UserAdminService;
import java.util.List;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
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
    public AuditLogPageResponse auditLogs(HttpSession session,
                                          @RequestParam(defaultValue = "1") Integer page,
                                          @RequestParam(name = "page_size", defaultValue = "20") Integer pageSize,
                                          @RequestParam(required = false) String query) {
        return loginAuditLogService.listPage(currentUser(session), page, pageSize, query);
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

    @PutMapping("/api/admin/users/{userId}/status")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void updateUserStatus(HttpSession session,
                                 @PathVariable String userId,
                                 @Valid @RequestBody UpdateUserStatusPayload payload) {
        userAdminService.updateUserStatus(currentUser(session), userId, payload);
    }

    @DeleteMapping("/api/admin/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUser(HttpSession session, @PathVariable String userId) {
        userAdminService.deleteUser(currentUser(session), userId);
    }

    private TUser currentUser(HttpSession session) {
        Object user = session.getAttribute(AuthController.SESSION_USER);
        if (user instanceof TUser) {
            return (TUser) user;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
    }
}
