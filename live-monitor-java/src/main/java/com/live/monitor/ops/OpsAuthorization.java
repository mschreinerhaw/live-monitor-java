package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import com.live.monitor.config.AuthInterceptor;
import com.live.monitor.controller.AuthController;
import com.live.monitor.entity.TUser;
import com.live.monitor.mapper.UserMapper;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OpsAuthorization {
    private final OpsRepository repo;
    private final UserMapper users;
    public OpsAuthorization(OpsRepository repo,UserMapper users){this.repo=repo;this.users=users;}
    public String actor(HttpServletRequest request,boolean write) {
        HttpSession s=request.getSession(false);
        if(s==null || s.getAttribute(AuthInterceptor.SESSION_EMBED_EXPIRES_AT)!=null)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Interactive login required for operations");
        Object principal=s.getAttribute(AuthController.SESSION_USER);
        if(!(principal instanceof TUser))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        String id=((TUser)principal).userId; TUser stored=users.findByUserId(id);
        if(stored==null || !Integer.valueOf(1).equals(stored.status))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Account is disabled");
        if(write && !"1".equals(request.getHeader("X-Ops-Request")))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Same-origin operations header required");
        return id;
    }
    public boolean allowed(String actor,String host,String env,String action) {
        TUser user=users.findByUserId(actor);
        if(user==null||!Integer.valueOf(1).equals(user.status))return false;
        if("admin".equalsIgnoreCase(actor))return true;
        return repo.list("grant").stream().anyMatch(g->!Boolean.FALSE.equals(g.get("enabled"))&&actor.equals(text(g,"user_id"))
            && match(text(g,"host_id"),host) && match(text(g,"environment"),env)
            && Arrays.asList(text(g,"actions").split(",")).contains(action));
    }
    private boolean match(String scope,String actual){return "*".equals(scope)||scope.equals(actual);}
    public void check(String actor,String host,String env,String action) {
        if(!allowed(actor,host,env,action)) {
            repo.audit(actor,action,host,"","DENIED");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Operation outside authorized host/environment/action scope");
        }
    }
    public void admin(String actor){if(!"admin".equalsIgnoreCase(actor))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Administrator required");}
    public void confirm(Map<String,Object> payload,String action,String resource,String env) {
        require((action+":"+resource+":"+env).equals(text(payload,"confirmation")),"Explicit confirmation does not match operation/resource/environment");
    }
}
