package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import java.util.*;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** One scoped read model for onboarding, applications and runtime monitoring. Never returns secrets/spec env. */
@Service
public class OpsOverviewService {
    private final OpsRepository repo;
    private final OpsAuthorization auth;
    public OpsOverviewService(OpsRepository repo,OpsAuthorization auth){this.repo=repo;this.auth=auth;}
    public Map<String,Object> read(String actor,long now){
        List<Map<String,Object>> hosts=new ArrayList<>(), apps=new ArrayList<>(), runtimes=new ArrayList<>();
        Set<String> visible=new HashSet<>(), physical=new HashSet<>();
        for(Map<String,Object> h:repo.list("host"))if(auth.allowed(actor,text(h,"id"),text(h,"environment"),"read")){
            visible.add(text(h,"id"));physical.add(text(h,"monitor_host_id"));Map<String,Object> s=find(repo.list("snapshot"),"host_id",text(h,"id"));
            hosts.add(map("id",text(h,"id"),"name",text(h,"name"),"environment",text(h,"environment"),"maintenance",flag(h,"maintenance"),
                "observed_at",s.get("observed_at"),"stale",stale(s,now),"runtime",map("docker_version",object(s,"data").get("docker_version"),"cpu_count",object(s,"data").get("cpu_count"),"memory_bytes",object(s,"data").get("memory_bytes"))));
        }
        for(String kind:Arrays.asList("application","stack"))for(Map<String,Object> a:repo.list(kind))if(visible.contains(text(a,"host_id"))){
            boolean stack="stack".equals(kind);String id=text(a,"id");
            Map<String,Object> snapshot=find(repo.list(stack?"stack_snapshot":"snapshot"),stack?"stack_id":"host_id",stack?id:text(a,"host_id"));
            Map<String,Object> data=object(snapshot,"data");
            List<Map<String,Object>> services=stack?rows(data,"services"):rows(data,"containers").stream().filter(c->id.equals(text(c,"app_id"))).collect(Collectors.toList());
            boolean old=stale(snapshot,now);List<Map<String,Object>> serviceRows=new ArrayList<>();
            for(Map<String,Object> service:services){
                Map<String,Object> row=new LinkedHashMap<>(service);
                row.putAll(map("host_id",text(a,"host_id"),"environment",text(a,"environment"),"resource_id",id,"kind",kind,"observed_at",snapshot.get("observed_at"),"stale",old));
                row.put("name",stack?text(service,"service"):text(service,"name"));serviceRows.add(row);runtimes.add(row);
            }
            List<Map<String,Object>> dependencies=stack?rows(data,"dependencies"):Collections.emptyList();
            String status=text(a,"active_version").isEmpty()?"NOT_DEPLOYED":"STOPPED".equals(text(a,"desired_state"))?"STOPPED":old?"UNKNOWN":
                !services.isEmpty()&&services.stream().allMatch(c->flag(c,"healthy")||flag(c,"manual_stop"))&&dependencies.stream().allMatch(c->flag(c,"healthy"))?"HEALTHY":"UNHEALTHY";
            Map<String,Object> active=map();
            if(!text(a,"active_version").isEmpty())active=object(find(repo.list(stack?"stack_version":"version"),"id",text(a,"active_version")),"spec");
            apps.add(map("id",id,"name",text(a,"name"),"host_id",text(a,"host_id"),"environment",text(a,"environment"),"kind",kind,"status",status,"desired_state",text(a,"desired_state"),
                "observed_at",snapshot.get("observed_at"),"stale",old,"services",serviceRows,"dependencies",dependencies,"recovery",stack?object(data,"recovery_policy"):
                    map("restart_policy",text(active,"restart_policy"),"limit",active.get("restart_limit"),"recover_unhealthy",flag(active,"recover_unhealthy"),"failure_window_seconds",active.get("failure_window_seconds")),
                "version",stack?text(active,"package_ref"):text(active,"image")));
            for(Map<String,Object> d:dependencies)if(!text(d,"container_id").startsWith("external:")){
                Map<String,Object> row=new LinkedHashMap<>(d);row.putAll(map("kind","dependency","host_id",text(a,"host_id"),"environment",text(a,"environment"),"resource_id",id,"status",flag(d,"running")?"running":"exited","stale",old,"observed_at",snapshot.get("observed_at")));runtimes.add(row);
            }
        }
        Map<String,Map<String,Object>> unique=new LinkedHashMap<>();
        for(Map<String,Object> r:runtimes)unique.put(text(r,"host_id")+":"+(text(r,"container_id").isEmpty()?text(r,"resource_id")+":"+text(r,"name"):text(r,"container_id")),r);
        List<Map<String,Object>> alerts=repo.list("alert").stream().filter(a->visible.contains(text(a,"host_id"))).collect(Collectors.toList());
        List<Map<String,Object>> recent=new ArrayList<>();
        for(String kind:Arrays.asList("version","stack_version"))for(Map<String,Object> v:repo.list(kind))if(visible.contains(text(v,"host_id")))recent.add(map("id",text(v,"id"),"resource_id",text(v,kind.equals("version")?"app_id":"stack_id"),"host_id",text(v,"host_id"),"created_at",v.get("created_at"),"version",text(object(v,"spec"),kind.equals("version")?"image":"package_ref")));
        recent.sort((a,b)->Long.compare(((Number)b.get("created_at")).longValue(),((Number)a.get("created_at")).longValue()));
        return map("observed_at",now,"hosts",hosts,"applications",apps,"containers",new ArrayList<>(unique.values()),"recent_deployments",recent.stream().limit(10).collect(Collectors.toList()),"alerts",alerts,
            "counts",map("hosts",physical.size(),"targets",hosts.size(),"applications",apps.size(),"healthy_applications",apps.stream().filter(a->"HEALTHY".equals(text(a,"status"))).count(),"running",unique.values().stream().filter(c->!flag(c,"stale")&&"running".equals(text(c,"status"))).count(),
                "abnormal",unique.values().stream().filter(c->!flag(c,"stale")&&!flag(c,"healthy")&&!flag(c,"manual_stop")).count(),"stale_hosts",hosts.stream().filter(h->flag(h,"stale")).count(),"alerts",alerts.stream().filter(a->"OPEN".equals(text(a,"status"))).count()));
    }
    private static Map<String,Object> find(List<Map<String,Object>> rows,String key,String id){return rows.stream().filter(r->id.equals(text(r,key))).findFirst().orElseGet(OpsData::map);}
    private static boolean stale(Map<String,Object> sample,long now){return !(sample.get("observed_at") instanceof Number)||now-((Number)sample.get("observed_at")).longValue()>90000;}
}
