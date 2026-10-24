package com.live.monitor.ops;
import java.util.Map;
import org.springframework.core.annotation.Order;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import com.live.monitor.controller.AuthController;
import com.live.monitor.entity.TUser;

@Order(-1)
@RestControllerAdvice(assignableTypes=OpsController.class)
public class OpsExceptionHandler {
    private final OpsRepository repo;
    public OpsExceptionHandler(OpsRepository repo){this.repo=repo;}
    private void audit(HttpServletRequest request,int status){
        if("GET".equals(request.getMethod()))return;
        HttpSession session=request.getSession(false);Object user=session==null?null:session.getAttribute(AuthController.SESSION_USER);
        String actor=user instanceof TUser?((TUser)user).userId:"anonymous";
        String path=request.getRequestURI();
        try{repo.audit(actor,"REJECTED_REQUEST",path.substring(0,Math.min(64,path.length())),"","HTTP_"+status);}catch(Exception ignored){ }
    }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,Object>> status(ResponseStatusException e,HttpServletRequest request){audit(request,e.getStatus().value());return ResponseEntity.status(e.getStatus()).body(OpsData.map("success",false,"message",e.getReason()==null?e.getStatus().getReasonPhrase():e.getReason(),"code",e.getStatus().value()));}
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String,Object>> invalid(IllegalArgumentException e,HttpServletRequest request){audit(request,400);return ResponseEntity.badRequest().body(OpsData.map("success",false,"message",e.getMessage(),"code",400));}
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String,Object>> failed(Exception e,HttpServletRequest request){audit(request,500);return ResponseEntity.status(500).body(OpsData.map("success",false,"message","Operation failed; review server diagnostics","code",500));}
}
