package com.live.monitor.ops;

import static com.live.monitor.ops.OpsData.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.live.monitor.config.LiveMonitorProperties;
import com.live.monitor.entity.HostConfig;
import com.live.monitor.mapper.HostMapper;
import com.live.monitor.service.CryptoService;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;

class InstallationServiceTest {
    OpsRepository repo;CryptoService crypto;InstallationService service;Map<String,Object> installation;
    @BeforeEach void setup(){
        DriverManagerDataSource source=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
        repo=new OpsRepository(new JdbcTemplate(source),new TransactionTemplate(new DataSourceTransactionManager(source)));repo.migrate();
        LiveMonitorProperties properties=new LiveMonitorProperties();properties.setSecretKey("isolated-installation-test-key");crypto=new CryptoService(properties);
        HostMapper monitors=mock(HostMapper.class);HostConfig host=new HostConfig();host.ip="192.0.2.10";when(monitors.findHost(1L)).thenReturn(host);
        repo.save("host",map("id","target","environment","test","monitor_host_id",1));service=new InstallationService(repo,crypto,monitors);
        installation=map("id","install-a","host_id","target","environment","test","bundle_id",String.join("",Collections.nCopies(64,"a")),"parameters",map("port",map("type","integer","default",18889,"min",1024,"max",65535),"password",map("type","secret"),"key",map("type","generated"),"cipher",map("type","generated","generation","aes-gcm","source","password","key","key")));
    }
    @Test void secretInputsAreEncryptedAndGeneratedValuesStableAcrossPreflightRetries(){
        Map<String,Object> first=service.planInput(installation,map("password","private-customer-password")),second=service.planInput(installation,map("password","private-customer-password"));
        assertFalse(json(first).contains("private-customer-password"));assertEquals(first.get("parameter_hash"),second.get("parameter_hash"));assertNotEquals(first.get("encrypted_parameters"),second.get("encrypted_parameters"));
        Map<String,Object> values=parse(crypto.decrypt(text(first,"encrypted_parameters")));assertEquals("private-customer-password",values.get("password"));assertTrue(text(values,"cipher").startsWith("ENC("));
        assertFalse(json(repo.list("installer_private")).contains("private-customer-password"));assertEquals("192.0.2.10",first.get("public_host"));
    }
    @Test void clientCannotReplaceGeneratedSecretsOrInventParameters(){
        assertThrows(Exception.class,()->service.planInput(installation,map("password","secret","key","override")));
        assertThrows(Exception.class,()->service.planInput(installation,map("password","secret","arbitrary","value")));
        assertThrows(Exception.class,()->service.planInput(installation,map("password","secret","port",80)));
    }
    @Test void changingAnInputSecretRegeneratesItsDerivedCredentialButKeepsTheKey(){
        Map<String,Object> first=parse(crypto.decrypt(text(service.planInput(installation,map("password","first-password")),"encrypted_parameters")));
        Map<String,Object> second=parse(crypto.decrypt(text(service.planInput(installation,map("password","second-password")),"encrypted_parameters")));
        assertEquals(first.get("key"),second.get("key"));assertNotEquals(first.get("cipher"),second.get("cipher"));
    }
    @Test void successfulInstallerRegistersStackAndSnapshotForExistingMonitor(){
        installation.putAll(map("name","Signed release","status","READY"));repo.save("installer",installation);
        OpsAgentGateway agent=mock(OpsAgentGateway.class);when(agent.call(anyMap(),eq("install_apply"),anyMap(),eq("task-a"))).thenReturn(map("package_ref","install-a","package_sha256",String.join("",Collections.nCopies(64,"b")),"snapshot",map("healthy",true,"desired_state","RUNNING","services",new ArrayList<>()),"entrypoints",new ArrayList<>()));
        service.execute(agent,"install_apply",installation,map("plan_id","plan-a"),"task-a");
        service.execute(agent,"install_apply",installation,map("plan_id","plan-a"),"task-a");
        assertEquals("RUNNING",repo.get("stack","install-a").get("desired_state"));assertEquals("INSTALLED",repo.get("installer","install-a").get("status"));assertTrue(flag(object(repo.get("stack_snapshot","install-a"),"data"),"healthy"));assertEquals(1,repo.list("stack_version").size());
    }
}
