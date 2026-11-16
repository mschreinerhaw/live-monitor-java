package com.live.monitor.controller;

import com.live.monitor.entity.TUser;
import com.live.monitor.service.CalendarService;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class CalendarController {
    private final CalendarService calendars;
    public CalendarController(CalendarService calendars){this.calendars=calendars;}
    @GetMapping("/api/calendars/{kind}")
    public Map<String,Object> list(@PathVariable String kind,@RequestParam(required=false) Integer from,@RequestParam(required=false) Integer to,@RequestParam(defaultValue="1") int page,@RequestParam(name="page_size",defaultValue="20") int size){return calendars.list(kind,from,to,page,size);}
    @GetMapping("/api/calendars/{kind}/resolve")
    public Map<String,Object> resolve(@PathVariable String kind,@RequestParam(name="natural_date") int date){return calendars.resolve(kind,date);}
    @PostMapping("/api/calendars/{kind}")
    public Map<String,Boolean> save(@PathVariable String kind,@RequestBody Map<String,Object> row,HttpServletRequest request){calendars.save(kind,row,admin(request.getSession()),request.getRemoteAddr());return Collections.singletonMap("success",true);}
    @DeleteMapping("/api/calendars/{kind}/{natural}/{business}")
    public Map<String,Boolean> delete(@PathVariable String kind,@PathVariable int natural,@PathVariable int business,HttpServletRequest request){calendars.delete(kind,natural,business,admin(request.getSession()),request.getRemoteAddr());return Collections.singletonMap("success",true);}
    @PostMapping("/api/calendars/import/apply")
    public Map<String,Object> apply(@RequestBody Map<String,String> body,HttpServletRequest request){TUser user=admin(request.getSession());String sql=body.get("sql");if(!hash(sql).equals(body.get("preview_hash")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"文件内容已改变，请重新预览");return calendars.importFile(sql,user,request.getRemoteAddr());}
    @PostMapping("/api/calendars/import/preview")
    public Map<String,Object> previewWithHash(@RequestBody Map<String,String> body,HttpSession session){admin(session);Map<String,Object> result=new LinkedHashMap<>();result.put("calendars",calendars.previewFile(body.get("sql")));result.put("preview_hash",hash(body.get("sql")));return result;}
    private String hash(String text){try{if(text==null)throw new IllegalArgumentException();byte[] bytes=java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format("%02x",b & 255));return s.toString();}catch(Exception ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"SQL 内容无效");}}
    private TUser admin(HttpSession session){Object principal=session.getAttribute(AuthController.SESSION_USER);if(!(principal instanceof TUser))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);TUser user=(TUser)principal;if(!"admin".equalsIgnoreCase(user.userId))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"仅管理员可维护日历数据");return user;}
}
