package com.live.monitor.alert;

import com.live.monitor.service.LiveMonitorService;
import com.live.monitor.store.RocksDbHistoryRepository;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "spring.datasource.url=jdbc:h2:mem:alert-app;MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "spring.datasource.username=sa", "spring.datasource.password=",
    "live-monitor.scheduler-tick-seconds=3600"})
@DirtiesContext
class AlertApplicationTest {
    static final Path directory=Paths.get("target","alert-smoke-"+UUID.randomUUID()).toAbsolutePath();
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("live-monitor.rocksdb-path",()->directory.resolve("rocks").toString());
        registry.add("live-monitor.lucene-index-path",()->directory.resolve("lucene").toString());
        registry.add("live-monitor.sqlite-path",()->directory.resolve("absent.db").toString());
        registry.add("live-monitor.h2-legacy-path",()->directory.resolve("absent-h2").toString());
    }
    @Autowired JdbcTemplate jdbc;
    @Autowired LiveMonitorService monitors;
    @Autowired RocksDbHistoryRepository history;

    @Test void realCheckCommitsThenWorkerSendsWebhookAndProjectsHistory() throws Exception {
        AtomicInteger notifications=new AtomicInteger();
        HttpServer gateway=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        gateway.createContext("/probe",exchange->{exchange.sendResponseHeaders(503,-1);exchange.close();});
        gateway.createContext("/notify",exchange->{
            while(exchange.getRequestBody().read()!=-1) { }
            notifications.incrementAndGet();
            byte[] body="{\"errcode\":0}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();
        });
        gateway.start();
        try {
            String base="http://127.0.0.1:"+gateway.getAddress().getPort();
            jdbc.update("INSERT INTO alert_group(id,group_name,enabled) VALUES(900,'smoke',1)");
            jdbc.update("INSERT INTO alert_channel(id,channel_name,channel_type,config_json,enabled) VALUES(900,'local robot','dingtalk',?,1)","{\"webhook_url\":\""+base+"/notify\"}");
            jdbc.update("INSERT INTO group_channel_rel(group_id,channel_id) VALUES(900,900)");
            jdbc.update("INSERT INTO monitor_service(id,service_name,service_type,endpoint,config_json,check_timeout_seconds) VALUES(900,'local monitor','web',?, ?,3)",base+"/probe","{\"service_consecutive_failures\":1}");
            jdbc.update("INSERT INTO service_alert_group(service_id,group_id) VALUES(900,900)");
            assertEquals("DOWN",monitors.checkAndStore(900L).status);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline) {
                Integer delivered=jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=900 AND delivery_status='SUCCESS'",Integer.class);
                if(delivered==1 && !history.listMonitorResults(900L,1).isEmpty()) break;
                Thread.sleep(50);
            }
            assertEquals(1,notifications.get());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task WHERE service_id=900 AND delivery_status='SUCCESS'",Integer.class));
            assertEquals("DOWN",history.listMonitorResults(900L,1).get(0).status);
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_attempt",Integer.class));
        } finally {gateway.stop(0);}
    }
}
