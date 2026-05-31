package com.live.monitor.service;

import com.live.monitor.dto.ChangePasswordPayload;
import com.live.monitor.dto.CreateUserPayload;
import com.live.monitor.dto.ResetUserPasswordPayload;
import com.live.monitor.entity.TUser;
import com.live.monitor.mapper.UserMapper;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class UserAdminService {
    private final UserMapper userMapper;
    private final AuthService authService;

    public UserAdminService(UserMapper userMapper, AuthService authService) {
        this.userMapper = userMapper;
        this.authService = authService;
    }

    public List<TUser> listUsers(TUser currentUser) {
        requireAdmin(currentUser);
        return userMapper.findAll();
    }

    public TUser createUser(TUser currentUser, CreateUserPayload payload) {
        requireAdmin(currentUser);
        String userId = normalizeUserId(payload.userId);
        if (userMapper.findByUserId(userId) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "user already exists");
        }
        String name = StringUtils.hasText(payload.name) ? payload.name.trim() : userId;
        Integer status = Boolean.FALSE.equals(payload.enabled) ? 0 : 1;
        userMapper.insertUser(userId, authService.encryptPassword(payload.password), name, status);
        TUser created = userMapper.findByUserId(userId);
        if (created != null) {
            created.password = null;
        }
        return created;
    }

    public void changeOwnPassword(TUser currentUser, ChangePasswordPayload payload) {
        requireAdmin(currentUser);
        if (currentUser == null || !StringUtils.hasText(currentUser.userId)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "authentication required");
        }
        TUser stored = userMapper.findByUserId(currentUser.userId);
        if (!authService.passwordMatches(stored, payload.currentPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "current password is incorrect");
        }
        if (payload.currentPassword.equals(payload.newPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "new password must be different");
        }
        userMapper.updatePassword(stored.id, authService.encryptPassword(payload.newPassword));
    }

    public void resetUserPassword(TUser currentUser, String userId, ResetUserPasswordPayload payload) {
        requireAdmin(currentUser);
        String normalized = normalizeUserId(userId);
        TUser target = userMapper.findByUserId(normalized);
        if (target == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found");
        }
        userMapper.updatePassword(target.id, authService.encryptPassword(payload.newPassword));
    }

    public boolean isAdmin(TUser user) {
        return user != null && "admin".equalsIgnoreCase(user.userId);
    }

    private void requireAdmin(TUser currentUser) {
        if (!isAdmin(currentUser)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "admin permission required");
        }
    }

    private String normalizeUserId(String userId) {
        String normalized = userId == null ? "" : userId.trim();
        if (!StringUtils.hasText(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "user id is required");
        }
        return normalized;
    }
}
