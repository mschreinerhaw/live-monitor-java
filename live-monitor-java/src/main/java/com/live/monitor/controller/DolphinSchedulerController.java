package com.live.monitor.controller;

import com.live.monitor.dto.ServicePayload;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.service.DolphinSchedulerService;
import com.live.monitor.service.LiveMonitorService;
import java.util.*;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

@RestController
@RequestMapping("/api/dolphinscheduler")
public class DolphinSchedulerController {
    private final DolphinSchedulerService ds;
    private final LiveMonitorService monitor;
    public DolphinSchedulerController(DolphinSchedulerService ds,LiveMonitorService monitor){this.ds=ds;this.monitor=monitor;}
    @GetMapping("/connections") public List<MonitorService> connections(){List<MonitorService> rows=new ArrayList<>();for(MonitorService s:monitor.listServices(true))if("dolphinscheduler".equals(s.serviceType))rows.add(s);return rows;}
    @PostMapping("/connections") public MonitorService create(@Valid @RequestBody ServicePayload payload){validate(payload,true);return monitor.create(payload);}
    @PutMapping("/connections/{id}") public MonitorService update(@PathVariable Long id,@Valid @RequestBody ServicePayload payload){ds.require(id);validate(payload,false);return monitor.update(id,payload);}
    private void validate(ServicePayload p,boolean create){
        try{DolphinSchedulerService.validate(p.config);
            if(create&&(p.secretConfig==null||Objects.toString(p.secretConfig.get("token"),"").trim().isEmpty()))throw new IllegalArgumentException("Token is required");
            if(Boolean.TRUE.equals(p.serviceAlertEnabled)&&(p.alertGroupIds==null||p.alertGroupIds.isEmpty()))throw new IllegalArgumentException("请选择告警接收组");
            p.serviceType="dolphinscheduler";p.checkTimeoutSeconds=120.0;
            p.endpoint=p.config.get("base_url").toString();
            if(p.checkInterval==null || p.checkInterval<30 || p.checkInterval>86400)throw new IllegalArgumentException("检查间隔须为 30–86400 秒");
        }catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,e.getMessage());}
    }
    @GetMapping("/{id}/projects") public Object projects(@PathVariable Long id){return ds.projects(id);}
    @GetMapping("/{id}/projects/{project}/definitions") public Object definitions(@PathVariable Long id,@PathVariable String project){return ds.definitions(id,project);}
    @GetMapping("/{id}/overview") public Object overview(@PathVariable Long id,@RequestParam(required=false) String start,@RequestParam(required=false) String end,@RequestParam(required=false) String project,@RequestParam(name="etl_date",required=false) String etlDate){return ds.snapshot(ds.require(id),start,end,project,etlDate);}
    @GetMapping("/{id}/projects/{project}/instances/{instance}/tasks") public Object tasks(@PathVariable Long id,@PathVariable String project,@PathVariable long instance){return ds.tasks(id,project,instance);}
    @ExceptionHandler(IllegalArgumentException.class) @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY) public Map<String,String> invalid(IllegalArgumentException e){return Collections.singletonMap("detail",e.getMessage());}
    @ExceptionHandler(IllegalStateException.class) @ResponseStatus(HttpStatus.BAD_GATEWAY) public Map<String,String> failed(IllegalStateException e){return Collections.singletonMap("detail",e.getMessage());}
}
