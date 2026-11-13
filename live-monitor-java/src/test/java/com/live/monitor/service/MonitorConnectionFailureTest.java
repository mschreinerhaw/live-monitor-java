package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.rule.ApiRuleEvaluator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.ServerSocket;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonitorConnectionFailureTest {
    @Test void closedRedisZooKeeperAndPortReportDown() throws Exception {
        int port;
        try(ServerSocket socket=new ServerSocket(0)) {port=socket.getLocalPort();}
        assertEquals("DOWN",new PortMonitorService().check("127.0.0.1",port,0.2).status);
        assertEquals("DOWN",new ZookeeperMonitorService().check("127.0.0.1",port,"ruok","ruok",null,0.2).status);
        CheckResult redis=new RedisMonitorService().check("127.0.0.1",port,null,null,false,0.2);
        assertEquals("DOWN",redis.status);assertNotNull(redis.message);
    }

    @Test void databaseAuthenticationFailureRetainsSpecificReason() throws Exception {
        String url="jdbc:h2:mem:auth-failure;DB_CLOSE_DELAY=-1";
        try(java.sql.Connection ignored=DriverManager.getConnection(url,"sa","correct-password")) {
            DatabaseMonitorService checker=new DatabaseMonitorService(new ApiRuleEvaluator(new ObjectMapper()),new ObjectMapper(),new LiveMonitorProperties());
            CheckResult result=checker.check("jdbc",null,null,null,"sa","wrong-password","SELECT 1",null,"fuzzy","org.h2.Driver",url,1);
            assertEquals("DOWN",result.status);
            assertTrue(result.message.contains("28000") || result.message.toLowerCase().contains("wrong user"));
            assertFalse(result.message.contains("wrong-password"));
        }
    }
}
