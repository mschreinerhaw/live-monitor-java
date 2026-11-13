package com.live.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.entity.MonitorService;
import com.live.monitor.mapper.MonitorServiceMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DolphinSchedulerServiceTest {
    HttpServer server;ObjectMapper json=new ObjectMapper();MonitorService service;DolphinSchedulerService ds;String mode="ok";int pages;boolean credential;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/ds",exchange->{
            credential="fixture-token".equals(exchange.getRequestHeaders().getFirst("token"));
            String path=exchange.getRequestURI().getPath(),query=exchange.getRequestURI().getQuery(),data;
            int status=200;
            if("auth".equals(mode)){status=401;data="{}";}
            else if("business".equals(mode)){data="{\"code\":100,\"success\":false}";}
            else if(path.endsWith("/projects"))data="{\"code\":0,\"data\":{\"total\":1,\"totalList\":[{\"id\":1,\"code\":123,\"name\":\"project\"}]}}";
            else if(path.endsWith("/tasks"))data="{\"code\":0,\"data\":{\"taskList\":[{\"id\":9,\"name\":\"shell\",\"state\":\"FAILURE\",\"taskParams\":\"hidden-secret\"}]}}";
            else {
                pages++;List<Map<String,Object>> rows=new ArrayList<>();boolean second=query.contains("pageNo=2");
                if(!"empty".equals(mode)&&!(second&&"incomplete".equals(mode)))for(int i=second?101:1;i<=(second?101:100);i++){Map<String,Object> r=new LinkedHashMap<>();r.put("id",i);r.put("name","workflow"+i);r.put("state",i==101?"FAILURE":"SUCCESS");r.put("startTime","2026-11-13 10:00:00");rows.add(r);}
                data="{\"code\":0,\"data\":{\"total\":"+("empty".equals(mode)?0:101)+",\"totalList\":"+json.writeValueAsString(rows)+"}}";
            }
            byte[] body=data.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,body.length);exchange.getResponseBody().write(body);exchange.close();
        });server.start();
        Map<String,Object> config=new HashMap<>();config.put("base_url","http://127.0.0.1:"+server.getAddress().getPort()+"/ds");config.put("project_codes","123");config.put("window_hours",24);
        service=new MonitorService();service.id=7L;service.serviceName="DS fixture";service.serviceType="dolphinscheduler";service.configJson=json.writeValueAsString(config);
        CryptoService crypto=new CryptoService(new LiveMonitorProperties());service.secretConfigJson=crypto.encrypt("{\"token\":\"fixture-token\"}");MonitorServiceMapper mapper=mock(MonitorServiceMapper.class);when(mapper.findById(7L)).thenReturn(service);ds=new DolphinSchedulerService(json,crypto,mapper);
    }
    @AfterEach void close(){server.stop(0);}
    @Test void aggregatesAllPagesAndFindsFailureBeyondFirstHundred(){Map<String,Object> result=ds.snapshot(service,"2026-11-13T00:00","2026-11-13 23:59:59",null);assertEquals(101,result.get("total"));assertEquals(1,result.get("abnormal"));assertEquals(100,result.get("success"));assertEquals(2,pages);assertTrue(credential);assertEquals("DOWN",ds.check(service).status);assertTrue(ds.check(service).message.contains("workflow101"));}
    @Test void incompletePaginationCannotReportHealthy(){mode="incomplete";assertEquals("UNKNOWN",ds.check(service).status);}
    @Test void httpAndBusinessAuthenticationFailuresCannotReportHealthy(){mode="auth";assertEquals("UNKNOWN",ds.check(service).status);mode="business";assertEquals("UNKNOWN",ds.check(service).status);assertFalse(ds.check(service).message.contains("fixture-token"));}
    @Test void validatesScopeAndDateRanges(){assertThrows(IllegalArgumentException.class,()->ds.snapshot(service,null,null,"999"));assertThrows(IllegalArgumentException.class,()->ds.snapshot(service,"2026-01-01 00:00:00","2026-11-01 00:00:00",null));assertThrows(IllegalArgumentException.class,()->ds.snapshot(service,"invalid",null,null));}
    @Test void taskDetailsStripParametersAndSecrets(){List<Map<String,Object>> tasks=ds.tasks(7L,"123",5);assertEquals("shell",tasks.get(0).get("name"));assertFalse(tasks.toString().contains("hidden-secret"));assertThrows(IllegalArgumentException.class,()->ds.tasks(7L,"999",5));}
    @Test void runningTimeoutAndCustomStateRules() throws Exception {Map<String,Object> config=new HashMap<>();config.put("running_timeout_minutes",10);config.put("alert_states","FAILURE");JsonNode running=json.readTree("{\"state\":\"RUNNING_EXECUTION\",\"startTime\":\"2026-11-13 10:00:00\"}");assertTrue(DolphinSchedulerService.isAbnormal(running,config,LocalDateTime.of(2026,11,13,10,11)));config.put("running_timeout_minutes",0);assertFalse(DolphinSchedulerService.isAbnormal(running,config,LocalDateTime.of(2026,11,13,10,11)));assertFalse(DolphinSchedulerService.isAbnormal(json.readTree("{\"state\":\"STOP\"}"),config,LocalDateTime.now()));}
    @Test void emptyWindowIsOptionalUnknown() throws Exception {mode="empty";assertEquals("UP",ds.check(service).status);Map<String,Object> c=ds.config(service);c.put("alert_on_empty",true);service.configJson=json.writeValueAsString(c);assertEquals("UNKNOWN",ds.check(service).status);}
}
