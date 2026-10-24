package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/ops")
public class OpsController {
    static final Set<String> ACTIONS=new LinkedHashSet<>(Arrays.asList("read","logs","probe","images","registry_catalog","resolve_image","pull","validate","deploy","rollback","start","stop","restart","backup","restore_test","restore","cleanup","ack","maintenance_on","maintenance_off"));
    private final OpsRepository repo;private final OpsAuthorization auth;private final HostManagementService hosts;
    private final ImageRegistryService registries;private final DeploymentPolicy policy;private final OpsTaskService tasks;
    private final ContainerManagementService containers;
    public OpsController(OpsRepository repo,OpsAuthorization auth,HostManagementService hosts,ImageRegistryService registries,DeploymentPolicy policy,OpsTaskService tasks,ContainerManagementService containers){this.repo=repo;this.auth=auth;this.hosts=hosts;this.registries=registries;this.policy=policy;this.tasks=tasks;this.containers=containers;}
    private Map<String,Object> ok(Object data){return map("data",data,"success",true);}
    @GetMapping("/capabilities")
    public Map<String,Object> capabilities(HttpServletRequest request){
        String actor=auth.actor(request,false);
        Map<String,Object> scopes=new LinkedHashMap<>();
        for(Map<String,Object> h:repo.list("host"))scopes.put(text(h,"id"),ACTIONS.stream().filter(a->auth.allowed(actor,text(h,"id"),text(h,"environment"),a)).collect(Collectors.toList()));
        return ok(map("administrator","admin".equalsIgnoreCase(actor),"event_codes",AlertEventService.CODES,"host_actions",scopes,"actions",ACTIONS,"restart_policies",Arrays.asList("no","on-failure","unless-stopped"),
            "data_kinds",Arrays.asList("stateless","volume","postgres"),"roles",map(
                "viewer",Arrays.asList("read","logs","images","registry_catalog","resolve_image"),"operator",Arrays.asList("read","logs","probe","images","start","stop","restart","ack"),
                "deployer",Arrays.asList("read","logs","validate","pull","deploy","rollback"),"recovery",Arrays.asList("read","backup","restore_test","restore"))));
    }
    @GetMapping("/templates/application")
    public Map<String,Object> applicationTemplate(HttpServletRequest request){auth.actor(request,false);return ok(map("id","","name","","host_id","","environment","","image","",
        "cpus",1,"memory_mb",512,"ports",new ArrayList<>(),"mounts",new ArrayList<>(),"network","bridge","command",new ArrayList<>(),"user","","environment_variables",map(),"secret_refs",map(),
        "health",map("command",new ArrayList<>(),"interval_seconds",10,"timeout_seconds",3),"health_deadline_seconds",90,"restart_policy","on-failure", "restart_limit",3,
        "data_kind","stateless","recover_unhealthy",false,"failure_window_seconds",30,"backup_retention_days",30,"backup_interval_hours",0,"postgres_database","postgres","postgres_user","postgres"));}
    private boolean visible(String actor,Map<String,Object> row){return auth.allowed(actor,text(row,"host_id").isEmpty()?text(row,"id"):text(row,"host_id"),text(row,"environment"),"read");}
    @GetMapping("/resources/{kind}")
    public Map<String,Object> list(@PathVariable String kind,HttpServletRequest request){
        String actor=auth.actor(request,false);require(Arrays.asList("host","registry","application","snapshot","version","backup","alert","alert_rule","grant").contains(kind),"Unknown resource kind");
        if("grant".equals(kind))auth.admin(actor);
        List<Map<String,Object>> items=repo.list(kind);
        if("snapshot".equals(kind)){
            // Also scope historical snapshots without rewriting their archived source documents.
            for(Map<String,Object> snapshot:items){
                snapshot.put("data",containers.scopedSnapshot(text(snapshot,"host_id"),object(snapshot,"data")));
            }
        }
        if("registry".equals(kind)){if(!"admin".equalsIgnoreCase(actor)&&repo.list("host").stream().noneMatch(h->visible(actor,h)))items=Collections.emptyList();}
        else if(!"grant".equals(kind))items=items.stream().filter(v->visible(actor,v)).collect(Collectors.toList());
        return ok(items);
    }
    @GetMapping("/monitor-hosts")
    public Map<String,Object> monitoringHosts(HttpServletRequest request){String actor=auth.actor(request,false);auth.admin(actor);
        return ok(hosts.monitors.listHosts(1).stream().map(h->map("id",h.id,"host_name",h.hostName,"ip",h.ip,"enabled",h.enabled)).collect(Collectors.toList()));}
    @PostMapping("/resources/{kind}")
    public Map<String,Object> save(@PathVariable String kind,@RequestBody Map<String,Object> input,HttpServletRequest request){
        String actor=auth.actor(request,true);Map<String,Object> result;
        if("host".equals(kind)){auth.admin(actor);result=hosts.register(input);}
        else if("registry".equals(kind)){auth.admin(actor);result=registries.save(input);}
        else if("alert_rule".equals(kind)){
            auth.admin(actor);identifier(text(input,"id"));Map<String,Object> h=repo.get("host",text(input,"host_id"));
            require(AlertEventService.CODES.contains(text(input,"code")),"Unsupported event code");
            require(Arrays.asList("WARNING","ERROR","CRITICAL").contains(text(input,"severity")),"Invalid severity");
            if(!text(input,"app_id").isEmpty())require(text(repo.get("application",text(input,"app_id")),"host_id").equals(text(h,"id")),"Application outside host scope");
            result=repo.save(kind,map("id",text(input,"id"),"host_id",text(h,"id"),"environment",text(h,"environment"),"app_id",text(input,"app_id"),
                "code",text(input,"code"),"severity",text(input,"severity"),"enabled",!Boolean.FALSE.equals(input.get("enabled")),"revision",input.getOrDefault("revision",0)));
        }
        else if("grant".equals(kind)){
            auth.admin(actor);identifier(text(input,"id"));require(!text(input,"user_id").isEmpty(),"User id required");
            String host=text(input,"host_id"),env=text(input,"environment");
            if(!"*".equals(host))repo.get("host",host);if(!"*".equals(env))identifier(env);
            List<String> actions=Arrays.asList(text(input,"actions").split(","));require(!actions.isEmpty()&&ACTIONS.containsAll(actions),"Unsupported grant actions");
            result=repo.save("grant",map("id",text(input,"id"),"user_id",text(input,"user_id"),"host_id",host,"environment",env,
                "actions",String.join(",",actions),"role",text(input,"role"),"enabled",!Boolean.FALSE.equals(input.get("enabled")),"revision",input.getOrDefault("revision",0)));
        }else if("application".equals(kind)){
            Map<String,Object> host=repo.get("host",text(input,"host_id"));String env=text(host,"environment");
            auth.check(actor,text(host,"id"),env,"deploy");require(env.equals(text(input,"environment")),"Application environment must match host");
            policy.validate(input);Map<String,Object> value=new LinkedHashMap<>();
            for(String field:Arrays.asList("id","name","host_id","environment","image","command","user","ports","environment_variables","secret_refs","cpus","memory_mb","network","mounts","health","restart_policy","restart_limit","health_deadline_seconds","data_kind","recover_unhealthy","failure_window_seconds","backup_retention_days","backup_interval_hours","postgres_database","postgres_user","revision"))if(input.containsKey(field))value.put(field,input.get(field));
            value.put("desired_state","STOPPED");
            for(Map<String,Object> old:repo.list("application"))if(text(old,"id").equals(text(value,"id"))){
                require(text(old,"host_id").equals(text(value,"host_id"))&&text(old,"environment").equals(env),"Create a new application to change host/environment");
                for(String field:Arrays.asList("desired_state","active_version","active_image"))if(old.containsKey(field))value.put(field,old.get(field));
            }
            result=repo.save(kind,value);
        }else throw new IllegalArgumentException("Resource is not directly editable");
        repo.audit(actor,"SAVE_"+kind,text(result,"id"),"","SUCCEEDED");return ok(result);
    }
    @PostMapping("/operations")
    public Map<String,Object> operate(@RequestBody Map<String,Object> input,HttpServletRequest request){
        String actor=auth.actor(request,true),action=text(input,"action"),id=text(input,"resource_id");
        require(ACTIONS.contains(action)&&!Arrays.asList("read","ack").contains(action),"Unknown operation");
        boolean hostAction=Arrays.asList("probe","images","registry_catalog","resolve_image","pull","cleanup","maintenance_on","maintenance_off").contains(action);
        Map<String,Object> resource=repo.get(hostAction?"host":"application",id);String host=hostAction?id:text(resource,"host_id"),env=text(resource,"environment");
        auth.check(actor,host,env,action);
        if(!Arrays.asList("probe","images","logs","validate","registry_catalog","resolve_image").contains(action))auth.confirm(input,action,id,env);
        Map<String,Object> body=object(input,"parameters");
        if(Arrays.asList("registry_catalog","resolve_image").contains(action)){
            Map<String,Object> registry=repo.get("registry",text(body,"registry_id"));
            body=map("registry",text(registry,"address"),"credential_ref",text(registry,"credential_ref"),
                "repository",text(body,"repository"),"tag",text(body,"tag"));
        }
        if("pull".equals(action)){
            String image=text(body,"image");require(image.matches("[a-zA-Z0-9][a-zA-Z0-9.:-]+/[a-zA-Z0-9_./-]+@sha256:[a-f0-9]{64}"),"Immutable registry image required");
            Map<String,Object> registry=repo.list("registry").stream().filter(r->text(r,"address").equals(image.split("/")[0])).findFirst().orElseThrow(()->new IllegalArgumentException("Unapproved registry"));
            body=map("image",image,"credential_ref",text(registry,"credential_ref"));
        }
        return ok(tasks.publicTask(tasks.submit(actor,action,resource,body,text(input,"idempotency_key"))));
    }
    @GetMapping("/tasks")
    public Map<String,Object> tasks(HttpServletRequest request){String actor=auth.actor(request,false);return ok(repo.tasks().stream().filter(t->auth.allowed(actor,text(t,"host_id"),text(t,"environment"),"read")&&(!"logs".equals(text(t,"action"))||auth.allowed(actor,text(t,"host_id"),text(t,"environment"),"logs"))).map(tasks::publicTask).collect(Collectors.toList()));}
    @GetMapping("/tasks/{id}")
    public Map<String,Object> task(@PathVariable String id,HttpServletRequest request){return ok(tasks.publicTask(authorizedTask(id,request)));}
    private Map<String,Object> authorizedTask(String id,HttpServletRequest request){Map<String,Object> t=repo.task(id);String actor=auth.actor(request,false);auth.check(actor,text(t,"host_id"),text(t,"environment"),"read");if("logs".equals(text(t,"action")))auth.check(actor,text(t,"host_id"),text(t,"environment"),"logs");return t;}
    @GetMapping(value="/tasks/{id}/events",produces="text/event-stream")
    public SseEmitter events(@PathVariable String id,HttpServletRequest request){authorizedTask(id,request);return tasks.stream(id,auth.actor(request,false));}
    @PostMapping("/alerts/{id}/ack")
    public Map<String,Object> acknowledge(@PathVariable String id,HttpServletRequest request){String actor=auth.actor(request,true);Map<String,Object> a=repo.get("alert",id);
        Map<String,Object> h=repo.get("host",text(a,"host_id"));auth.check(actor,text(h,"id"),text(h,"environment"),"ack");a.put("acknowledged_by",actor);a.put("acknowledged_at",System.currentTimeMillis());repo.save("alert",a);repo.audit(actor,"ACK",id,"","SUCCEEDED");return ok(a);}
    @GetMapping("/audit")
    public Map<String,Object> audit(HttpServletRequest request){auth.admin(auth.actor(request,false));return ok(repo.jdbc().queryForList("SELECT * FROM ops_audit ORDER BY created_at DESC LIMIT 500"));}
}
