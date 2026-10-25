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
    static { ACTIONS.addAll(StackManagementService.ACTIONS); ACTIONS.addAll(Arrays.asList("import_image","install_upload","install_plan","install_apply")); }
    @org.springframework.beans.factory.annotation.Autowired private ImageArchiveService archives;
    @org.springframework.beans.factory.annotation.Autowired private InstallationService installations;
    @PostMapping(value="/installer/upload",consumes="multipart/form-data")
    public Map<String,Object> uploadInstaller(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,@RequestParam("host_id") String hostId,@RequestParam("confirmation") String confirmation,@RequestParam("idempotency_key") String key,HttpServletRequest request)throws java.io.IOException {
        String actor=auth.actor(request,true);Map<String,Object> host=repo.get("host",hostId);auth.check(actor,hostId,text(host,"environment"),"install_upload");
        auth.confirm(map("confirmation",confirmation),"install_upload",hostId,text(host,"environment"));return ok(archives.submit(actor,host,file,key,"install_upload"));
    }
    @PostMapping("/installer/{id}/{operation}")
    public Map<String,Object> install(@PathVariable String id,@PathVariable String operation,@RequestBody Map<String,Object> input,HttpServletRequest request){
        require(Arrays.asList("plan","apply").contains(operation),"Unknown installer operation");String action="install_"+operation,actor=auth.actor(request,true);
        Map<String,Object> resource=repo.get("installer",id);auth.check(actor,text(resource,"host_id"),text(resource,"environment"),action);
        String key=text(input,"idempotency_key");require(key.matches("[a-zA-Z0-9_.:-]{8,128}"),"Invalid installation idempotency key");
        String proof="plan".equals(operation)?OpsTaskService.hash(json(new TreeMap<>(object(input,"parameters")))):text(input,"plan_id");
        if("apply".equals(operation))auth.confirm(input,action,id,text(resource,"environment"));
        List<Map<String,Object>> existing=repo.jdbc().queryForList("SELECT * FROM ops_task WHERE actor=? AND idempotency_key=?",actor,key);
        if(!existing.isEmpty()){
            Map<String,Object> previous=existing.get(0),previousInput=object(parse(text(previous,"request_json")),"input");
            require(action.equals(text(previous,"action"))&&id.equals(text(previous,"resource_id"))&&proof.equals(text(previousInput,"plan".equals(operation)?"client_parameters_hash":"plan_id")),"Installation idempotency key reused with different inputs");
            return ok(tasks.publicTask(previous));
        }
        require(!"INSTALLED".equals(text(resource,"status")),"Installation already completed; use container management");
        Map<String,Object> parameters;
        if("plan".equals(operation)){parameters=installations.planInput(resource,object(input,"parameters"));parameters.put("client_parameters_hash",proof);}
        else {auth.confirm(input,action,id,text(resource,"environment"));require(text(resource,"plan_id").equals(text(input,"plan_id"))&&"READY".equals(text(resource,"status")),"A successful current preflight is required");parameters=map("plan_id",text(input,"plan_id"));}
        return ok(tasks.publicTask(tasks.submit(actor,action,resource,parameters,text(input,"idempotency_key"))));
    }
    @PostMapping(value="/images/import",consumes="multipart/form-data")
    public Map<String,Object> importImage(@RequestParam("file") org.springframework.web.multipart.MultipartFile file,
            @RequestParam("host_id") String hostId,@RequestParam("confirmation") String confirmation,
            @RequestParam("idempotency_key") String key,HttpServletRequest request) throws java.io.IOException {
        String actor=auth.actor(request,true);Map<String,Object> host=repo.get("host",hostId);
        auth.check(actor,hostId,text(host,"environment"),"import_image");
        auth.confirm(map("confirmation",confirmation),"import_image",hostId,text(host,"environment"));
        return ok(archives.submit(actor,host,file,key));
    }
    public OpsController(OpsRepository repo,OpsAuthorization auth,HostManagementService hosts,ImageRegistryService registries,DeploymentPolicy policy,OpsTaskService tasks,ContainerManagementService containers){this.repo=repo;this.auth=auth;this.hosts=hosts;this.registries=registries;this.policy=policy;this.tasks=tasks;this.containers=containers;}
    private Map<String,Object> ok(Object data){return map("data",data,"success",true);}
    @GetMapping("/overview")
    public Map<String,Object> overview(HttpServletRequest request){return ok(new OpsOverviewService(repo,auth).read(auth.actor(request,false),System.currentTimeMillis()));}
    @GetMapping("/capabilities")
    public Map<String,Object> capabilities(HttpServletRequest request){
        String actor=auth.actor(request,false);
        Map<String,Object> scopes=new LinkedHashMap<>();
        for(Map<String,Object> h:repo.list("host"))scopes.put(text(h,"id"),ACTIONS.stream().filter(a->auth.allowed(actor,text(h,"id"),text(h,"environment"),a)).collect(Collectors.toList()));
        return ok(map("administrator","admin".equalsIgnoreCase(actor),"ops_only",auth.operationsOnly(actor),"event_codes",AlertEventService.CODES,"host_actions",scopes,"actions",ACTIONS,"restart_policies",Arrays.asList("no","on-failure","unless-stopped"),
            "data_kinds",Arrays.asList("stateless","volume","postgres"),"roles",map(
                "viewer",Arrays.asList("read","logs","images","registry_catalog","resolve_image","stack_probe","stack_logs"),"operator",Arrays.asList("read","logs","probe","images","start","stop","restart","ack","stack_probe","stack_start","stack_stop","stack_service_start","stack_service_stop","stack_service_restart","stack_logs"),
                "deployer",Arrays.asList("read","logs","images","import_image","install_upload","install_plan","install_apply","validate","pull","deploy","rollback","stack_plan","stack_probe","stack_deploy","stack_rollback","stack_logs"),"recovery",Arrays.asList("read","backup","restore_test","restore","stack_probe","stack_backup","stack_logs"))));
    }
    @GetMapping("/templates/application")
    public Map<String,Object> applicationTemplate(HttpServletRequest request){auth.actor(request,false);return ok(map("id","","name","","host_id","","environment","","image","",
        "cpus",1,"memory_mb",512,"ports",new ArrayList<>(),"mounts",new ArrayList<>(),"network","bridge","command",new ArrayList<>(),"user","","environment_variables",map(),"secret_refs",map(),
        "health",map("command",new ArrayList<>(),"interval_seconds",10,"timeout_seconds",3),"health_deadline_seconds",90,"restart_policy","on-failure", "restart_limit",3,
        "data_kind","stateless","recover_unhealthy",false,"failure_window_seconds",30,"backup_retention_days",30,"backup_interval_hours",0,"postgres_database","postgres","postgres_user","postgres"));}
    private boolean visible(String actor,Map<String,Object> row){return auth.allowed(actor,text(row,"host_id").isEmpty()?text(row,"id"):text(row,"host_id"),text(row,"environment"),"read");}
    @GetMapping("/resources/{kind}")
    public Map<String,Object> list(@PathVariable String kind,HttpServletRequest request){
        String actor=auth.actor(request,false);require(Arrays.asList("host","registry","application","snapshot","version","backup","alert","alert_rule","grant","stack","stack_version","stack_snapshot","installer").contains(kind),"Unknown resource kind");
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
            if(!text(input,"app_id").isEmpty()){
                boolean owned=false;for(String type:Arrays.asList("application","stack"))for(Map<String,Object> app:repo.list(type))if(text(input,"app_id").equals(text(app,"id"))&&text(h,"id").equals(text(app,"host_id")))owned=true;
                require(owned,"Application outside host scope");
            }
            result=repo.save(kind,map("id",text(input,"id"),"host_id",text(h,"id"),"environment",text(h,"environment"),"app_id",text(input,"app_id"),
                "code",text(input,"code"),"severity",text(input,"severity"),"enabled",!Boolean.FALSE.equals(input.get("enabled")),"revision",input.getOrDefault("revision",0)));
        }
        else if("grant".equals(kind)){
            auth.admin(actor);identifier(text(input,"id"));require(!text(input,"user_id").isEmpty(),"User id required");
            boolean operationsOnly=!Boolean.FALSE.equals(input.get("ops_only"));
            for(Map<String,Object> old:repo.list("grant"))if(text(old,"id").equals(text(input,"id"))){
                require(text(old,"user_id").equals(text(input,"user_id")),"Grant user cannot change; create a separate grant");
                if(Boolean.TRUE.equals(old.get("ops_only"))&&!operationsOnly)
                    require(("ALLOW_LEGACY:"+text(input,"user_id")).equals(text(input,"confirm_legacy_access")),"Explicit confirmation required to restore legacy monitor access");
            }
            String host=text(input,"host_id"),env=text(input,"environment");
            if(!"*".equals(host))repo.get("host",host);if(!"*".equals(env))identifier(env);
            List<String> actions=Arrays.asList(text(input,"actions").split(","));require(!actions.isEmpty()&&ACTIONS.containsAll(actions),"Unsupported grant actions");
            result=repo.save("grant",map("id",text(input,"id"),"user_id",text(input,"user_id"),"host_id",host,"environment",env,
                "actions",String.join(",",actions),"role",text(input,"role"),"ops_only",operationsOnly,"enabled",!Boolean.FALSE.equals(input.get("enabled")),"revision",input.getOrDefault("revision",0)));
        }else if("stack".equals(kind)){
            StackManagementService.validate(input);Map<String,Object> host=repo.get("host",text(input,"host_id"));
            auth.check(actor,text(host,"id"),text(host,"environment"),"stack_deploy");
            require(text(host,"environment").equals(text(input,"environment")),"Stack environment must match host");
            Map<String,Object> value=map("id",text(input,"id"),"name",text(input,"name"),"host_id",text(host,"id"),"environment",text(host,"environment"),"package_ref",text(input,"package_ref"),"package_sha256",text(input,"package_sha256"),"desired_state","STOPPED","revision",input.getOrDefault("revision",0));
            for(Map<String,Object> old:repo.list("stack"))if(text(old,"id").equals(text(input,"id"))){
                require(text(old,"host_id").equals(text(host,"id"))&&text(old,"environment").equals(text(host,"environment")),"Create a separate stack to change host/environment");
                value.put("desired_state",old.get("desired_state"));if(old.containsKey("active_version"))value.put("active_version",old.get("active_version"));
            }
            result=repo.save(kind,value);
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
        require(ACTIONS.contains(action)&&!Arrays.asList("read","ack","import_image","install_upload","install_plan","install_apply").contains(action),"Unknown operation; installers and image uploads require their dedicated endpoints");
        boolean hostAction=Arrays.asList("probe","images","registry_catalog","resolve_image","pull","cleanup","maintenance_on","maintenance_off").contains(action);
        Map<String,Object> resource=repo.get(hostAction?"host":StackManagementService.ACTIONS.contains(action)?"stack":"application",id);String host=hostAction?id:text(resource,"host_id"),env=text(resource,"environment");
        auth.check(actor,host,env,action);
        if(!Arrays.asList("probe","images","logs","validate","registry_catalog","resolve_image","stack_plan","stack_probe","stack_logs").contains(action))auth.confirm(input,action,id,env);
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
