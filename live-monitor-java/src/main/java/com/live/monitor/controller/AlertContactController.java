package com.live.monitor.controller;

import com.live.monitor.dto.AlertContactPayload;
import com.live.monitor.entity.TUser;
import com.live.monitor.service.AlertContactService;
import java.util.*;
import javax.servlet.http.HttpSession;
import javax.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/alert-contacts")
public class AlertContactController {
    private final AlertContactService contacts;
    public AlertContactController(AlertContactService contacts){this.contacts=contacts;}
    @GetMapping public List<Map<String,Object>> list(){return contacts.list();}
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public Map<String,Object> create(@Valid @RequestBody AlertContactPayload payload,HttpSession session){admin(session);return contacts.save(null,payload);}
    @PutMapping("/{id}") public Map<String,Object> update(@PathVariable long id,@Valid @RequestBody AlertContactPayload payload,HttpSession session){admin(session);return contacts.save(id,payload);}
    @DeleteMapping("/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id,HttpSession session){admin(session);contacts.delete(id);}
    private void admin(HttpSession session){Object user=session.getAttribute(AuthController.SESSION_USER);if(!(user instanceof TUser))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);if(!"admin".equalsIgnoreCase(((TUser)user).userId))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可以维护联系人通讯录");}
}
