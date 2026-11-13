package com.live.monitor.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.dto.CheckResult;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.MonitorServiceMapper;
import com.live.monitor.util.MonitorTime;
import java.time.LocalDateTime;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;
import okhttp3.*;
import org.springframework.stereotype.Service;

/** Read-only DS integration. Checks return results to the existing transactional alert pipeline. */
@Service
public class DolphinSchedulerService {
    private final ObjectMapper json;
    private final CryptoService crypto;
    private final MonitorServiceMapper mapper;
    private final OkHttpClient client = new OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).followRedirects(false).build();
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> RUNNING = new HashSet<>(Arrays.asList("RUNNING_EXECUTION","READY_PAUSE","READY_STOP","DELAY_EXECUTION","SERIAL_WAIT"));
    public DolphinSchedulerService(ObjectMapper json, CryptoService crypto, MonitorServiceMapper mapper) {
        this.json=json;this.crypto=crypto;this.mapper=mapper;
    }
    public static void validate(Map<String,Object> config) {
        if(config==null)throw new IllegalArgumentException("DolphinScheduler configuration is required");
        HttpUrl url=HttpUrl.parse(String.valueOf(config.get("base_url")));
        if(url==null || !url.username().isEmpty() || !url.password().isEmpty() || url.query()!=null || url.fragment()!=null)
            throw new IllegalArgumentException("DolphinScheduler base URL must be an HTTP(S) address without credentials, query or fragment");
        number(config,"window_hours",24,1,744);number(config,"running_timeout_minutes",180,0,10080);
        Object codes=config.get("project_codes");
        if(codes!=null && !codes.toString().trim().isEmpty())for(String code:codes.toString().split(","))if(!code.trim().matches("[0-9]{1,18}"))throw new IllegalArgumentException("Project codes must be comma-separated numeric codes");
        Object states=config.get("alert_states");
        if(states!=null)for(String state:states.toString().split(","))if(!Arrays.asList("FAILURE","STOP","KILL","PAUSE","NEED_FAULT_TOLERANCE").contains(state.trim()))throw new IllegalArgumentException("Unsupported alert state");
    }
    private static int number(Map<String,Object> c,String key,int fallback,int min,int max){
        int n=c.get(key)==null?fallback:Integer.parseInt(c.get(key).toString());
        if(n<min||n>max)throw new IllegalArgumentException(key+" outside allowed range");return n;
    }
    public MonitorService require(Long id){MonitorService s=mapper.findById(id);if(s==null||!"dolphinscheduler".equals(s.serviceType))throw new IllegalArgumentException("DolphinScheduler connection not found");return s;}
    @SuppressWarnings("unchecked")
    public Map<String,Object> config(MonitorService s){try{return json.readValue(s.configJson,Map.class);}catch(Exception e){throw new IllegalArgumentException("Invalid DolphinScheduler configuration");}}
    private String token(MonitorService s){try{JsonNode secret=json.readTree(crypto.decryptIfEncrypted(s.secretConfigJson));String token=secret.path("token").asText();if(token.trim().isEmpty())throw new IllegalArgumentException();return token;}catch(Exception e){throw new IllegalArgumentException("DolphinScheduler Token is missing or unreadable");}}
    private JsonNode get(MonitorService s,String path,Map<String,String> params){
        if(Thread.currentThread().isInterrupted())throw new IllegalStateException("DolphinScheduler collection interrupted");
        Map<String,Object> c=config(s);validate(c);String base=c.get("base_url").toString().replaceAll("/+$","");
        HttpUrl.Builder url=Objects.requireNonNull(HttpUrl.parse(base+path)).newBuilder();params.forEach(url::addQueryParameter);
        Request request=new Request.Builder().url(url.build()).header("token",token(s)).header("Accept","application/json").build();
        try(Response response=client.newCall(request).execute()){
            if(!response.isSuccessful())throw new IllegalStateException("DolphinScheduler HTTP "+response.code());
            if(response.body()==null)throw new IllegalStateException("DolphinScheduler empty response");
            JsonNode root=json.readTree(response.body().string());
            if(!root.has("code")||root.path("code").asInt(-1)!=0||root.path("success").isBoolean()&&!root.path("success").asBoolean())throw new IllegalStateException("DolphinScheduler rejected request; verify Token and project permissions");
            return root.path("data");
        }catch(java.io.IOException e){throw new IllegalStateException("DolphinScheduler connection or response error");}
    }
    private List<JsonNode> all(MonitorService s,String path,Map<String,String> filters,long deadline){
        List<JsonNode> rows=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(int page=1;page<=100;page++){
            if(System.nanoTime()>deadline)throw new IllegalStateException("DolphinScheduler collection deadline exceeded; narrow project/time range");
            Map<String,String> params=new LinkedHashMap<>(filters);params.put("pageNo",String.valueOf(page));params.put("pageSize","100");
            JsonNode data=get(s,path,params),list=data.path("totalList");
            if(!list.isArray()||!data.has("total"))throw new IllegalStateException("Unsupported DolphinScheduler pagination response");
            int added=0;for(JsonNode item:list){String key=item.path("id").asText(item.path("code").asText());if(seen.add(key)){rows.add(item);added++;}}
            long total=data.path("total").asLong();if(rows.size()>=total)return rows;
            if(added==0)throw new IllegalStateException("Incomplete DolphinScheduler page; refresh query");
        }throw new IllegalStateException("DolphinScheduler results exceed 10000 records; narrow project/time range");
    }
    public List<Map<String,Object>> projects(Long id){MonitorService s=require(id);List<Map<String,Object>> out=new ArrayList<>();
        for(JsonNode p:all(s,"/projects",Collections.emptyMap(),System.nanoTime()+TimeUnit.SECONDS.toNanos(60))){Map<String,Object> row=new LinkedHashMap<>();row.put("code",p.path("code").asText());row.put("name",p.path("name").asText());row.put("description",p.path("description").asText());out.add(row);}return out;
    }
    public Map<String,Object> snapshot(MonitorService s,String start,String end,String project){
        Map<String,Object> c=config(s);validate(c);
        LocalDateTime until=end==null||end.isEmpty()?MonitorTime.now():parseTime(end);
        LocalDateTime since=start==null||start.isEmpty()?until.minusHours(number(c,"window_hours",24,1,744)):parseTime(start);
        if(until.isBefore(since)||Duration.between(since,until).compareTo(Duration.ofDays(31))>0)throw new IllegalArgumentException("Query range must be ordered and within 31 days");
        Set<String> codes=new LinkedHashSet<>();String configured=Objects.toString(c.get("project_codes"),"");for(String code:configured.split(","))if(!code.trim().isEmpty())codes.add(code.trim());
        if(project!=null&&!project.isEmpty()&&!codes.isEmpty()&&!codes.contains(project))throw new IllegalArgumentException("Project is outside configured scope");
        List<Map<String,Object>> summaries=new ArrayList<>(),instances=new ArrayList<>();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(110);
        List<JsonNode> projects=all(s,"/projects",Collections.emptyMap(),deadline);Set<String> found=new HashSet<>();int failures=0,success=0,running=0,other=0;
        for(JsonNode p:projects){String code=p.path("code").asText();if(!codes.isEmpty()&&!codes.contains(code)||project!=null&&!project.isEmpty()&&!project.equals(code))continue;found.add(code);
            Map<String,String> params=new LinkedHashMap<>();params.put("startDate",TIME.format(since));params.put("endDate",TIME.format(until));
            int pf=0,ps=0,pr=0,po=0;List<JsonNode> rows=all(s,"/projects/"+code+"/process-instances",params,deadline);
            for(JsonNode node:rows){Map<String,Object> row=instance(node);String state=node.path("state").asText();boolean failed=isAbnormal(node,c,MonitorTime.now());
                row.put("project_code",code);row.put("project_name",p.path("name").asText());row.put("project_description",p.path("description").asText());row.put("abnormal",failed);instances.add(row);
                if(failed)pf++;if("SUCCESS".equals(state)||"FORCED_SUCCESS".equals(state))ps++;else if(RUNNING.contains(state)||"SUBMITTED_SUCCESS".equals(state))pr++;else po++;
            }
            Map<String,Object> summary=new LinkedHashMap<>();summary.put("code",code);summary.put("name",p.path("name").asText());summary.put("description",p.path("description").asText());summary.put("total",rows.size());summary.put("success",ps);summary.put("running",pr);summary.put("abnormal",pf);summary.put("other",po);summaries.add(summary);failures+=pf;success+=ps;running+=pr;other+=po;
        }
        if(project!=null&&!project.isEmpty()&&!found.contains(project)||(project==null||project.isEmpty())&&!found.containsAll(codes))throw new IllegalStateException("Configured project is unavailable; verify project permissions");
        instances.sort(Comparator.comparing((Map<String,Object> r)->Objects.toString(r.get("start_time"),"")).reversed());
        Map<String,Object> result=new LinkedHashMap<>();result.put("projects",summaries);result.put("instances",instances);result.put("total",instances.size());result.put("success",success);result.put("running",running);result.put("abnormal",failures);result.put("other",other);result.put("start",TIME.format(since));result.put("end",TIME.format(until));result.put("collected_at",TIME.format(MonitorTime.now()));return result;
    }
    public static boolean isAbnormal(JsonNode node,Map<String,Object> config,LocalDateTime now){
        String state=node.path("state").asText();Set<String> states=new HashSet<>(Arrays.asList(Objects.toString(config.get("alert_states"),"FAILURE,STOP,KILL").split(",")));
        if(states.stream().anyMatch(value->value.trim().equals(state)))return true;
        int minutes=number(config,"running_timeout_minutes",180,0,10080);
        if(minutes>0&&RUNNING.contains(state)&&node.path("endTime").asText("").isEmpty())try{return Duration.between(LocalDateTime.parse(node.path("startTime").asText(),TIME),now).toMinutes()>=minutes;}catch(Exception ignored){}
        return false;
    }
    private LocalDateTime parseTime(String value){try{String text=value.replace('T',' ');return LocalDateTime.parse(text.length()==16?text+":00":text,TIME);}catch(Exception e){throw new IllegalArgumentException("Date format must be yyyy-MM-dd HH:mm:ss");}}
    private Map<String,Object> instance(JsonNode n){Map<String,Object> r=new LinkedHashMap<>();for(String[] pair:new String[][]{{"id","id"},{"name","name"},{"state","state"},{"start_time","startTime"},{"end_time","endTime"},{"duration","duration"},{"host","host"},{"executor","executorName"},{"definition_code","processDefinitionCode"},{"task_type","taskType"}})r.put(pair[0],n.path(pair[1]).asText(""));return r;}
    public List<Map<String,Object>> tasks(Long id,String project,long instance){if(!project.matches("[0-9]{1,18}")||instance<1)throw new IllegalArgumentException("Invalid instance");MonitorService s=require(id);
        String codes=Objects.toString(config(s).get("project_codes"),"");if(!codes.trim().isEmpty()&&!Arrays.asList(codes.split(",")).contains(project))throw new IllegalArgumentException("Project outside configured scope");
        JsonNode data=get(s,"/projects/"+project+"/process-instances/"+instance+"/tasks",Collections.emptyMap());JsonNode list=data.isArray()?data:data.path("taskList");if(!list.isArray())throw new IllegalStateException("Unsupported DolphinScheduler task response");List<Map<String,Object>> rows=new ArrayList<>();for(JsonNode n:list)rows.add(instance(n));return rows;
    }
    @SuppressWarnings("unchecked")
    public CheckResult check(MonitorService s){long started=System.nanoTime();try{Map<String,Object> snapshot=snapshot(s,null,null,null);int failures=(Integer)snapshot.get("abnormal");StringBuilder message=new StringBuilder("DolphinScheduler ").append(s.serviceName).append(" · ").append(snapshot.get("start")).append(" ~ ").append(snapshot.get("end")).append("\n工作流实例 ").append(snapshot.get("total")).append("，成功 ").append(snapshot.get("success")).append("，运行中 ").append(snapshot.get("running")).append("，异常 ").append(failures);
        int shown=0;for(Map<String,Object> row:(List<Map<String,Object>>)snapshot.get("instances"))if(Boolean.TRUE.equals(row.get("abnormal"))&&shown++<15)message.append("\n项目 ").append(row.get("project_description")).append(" / ").append(row.get("project_name")).append("，实例 ").append(row.get("id")).append(" ").append(row.get("name")).append("，状态 ").append(row.get("state")).append("，开始 ").append(row.get("start_time"));
        boolean empty=((Integer)snapshot.get("total"))==0&&Boolean.TRUE.equals(config(s).get("alert_on_empty"));
        return new CheckResult(empty?"UNKNOWN":failures>0?"DOWN":"UP",(int)TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),message.toString());
        }catch(Exception e){return new CheckResult("UNKNOWN",(int)TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started),MonitorCheckExecution.safe(e.getMessage()));}}
}
