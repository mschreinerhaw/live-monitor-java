package com.live.monitor.ops;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.live.monitor.controller.AuthController;
import com.live.monitor.entity.TUser;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:ops-app;MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
    "live-monitor.secret-key=isolated-integration-test-key","live-monitor.ops.startup-grace-ms=600000"})
@AutoConfigureMockMvc
class OpsApplicationIntegrationTest {
    static final Path ROOT=temp();
    static Path temp(){try{return Files.createTempDirectory("live-monitor-ops-test-");}catch(Exception e){throw new IllegalStateException(e);}}
    @DynamicPropertySource static void paths(DynamicPropertyRegistry r){
        r.add("live-monitor.ops.upload-directory",()->ROOT.resolve("uploads").toString());
        r.add("live-monitor.rocksdb-path",()->ROOT.resolve("rocksdb").toString());
        r.add("live-monitor.sqlite-path",()->ROOT.resolve("absent.db").toString());
        r.add("live-monitor.h2-legacy-path",()->ROOT.resolve("absent-h2").toString());
    }
    @Autowired MockMvc mvc;
    @Autowired OpsRepository repo;
    @Autowired OpsTaskService tasks;
    @Test void imageImportRequiresScopedPermissionHeaderAndExplicitConfirmation()throws Exception {
        repo.save("host",OpsData.map("id","import-test-host","environment","test"));
        org.springframework.mock.web.MockMultipartFile file=new org.springframework.mock.web.MockMultipartFile("file","demo.tar","application/octet-stream",new byte[]{1,2,3});
        mvc.perform(multipart("/api/ops/images/import").file(file).session(admin()).param("host_id","import-test-host").param("confirmation","import_image:import-test-host:test").param("idempotency_key","import-test-001")).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/ops/images/import").file(file).session(admin()).header("X-Ops-Request","1").param("host_id","import-test-host").param("confirmation","wrong").param("idempotency_key","import-test-001")).andExpect(status().isBadRequest());
        MockHttpSession viewer=user("archive-viewer");
        mvc.perform(multipart("/api/ops/images/import").file(file).session(viewer).header("X-Ops-Request","1").param("host_id","import-test-host").param("confirmation","import_image:import-test-host:test").param("idempotency_key","import-test-001")).andExpect(status().isForbidden());
        mvc.perform(multipart("/api/ops/images/import").file(file).session(admin()).header("X-Ops-Request","1").param("host_id","import-test-host").param("confirmation","import_image:import-test-host:test").param("idempotency_key","import-test-001")).andExpect(status().isOk()).andExpect(jsonPath("$.data.action").value("import_image")).andExpect(jsonPath("$.data.host_id").value("import-test-host")).andExpect(jsonPath("$.data.request_json").doesNotExist());
        mvc.perform(post("/api/ops/operations").session(admin()).header("X-Ops-Request","1").contentType("application/json").content("{\"action\":\"import_image\",\"resource_id\":\"import-test-host\",\"parameters\":{\"archive_path\":\"/etc/passwd\"}}")).andExpect(status().isBadRequest());
    }
    MockHttpSession admin(){TUser u=new TUser();u.userId="admin";MockHttpSession s=new MockHttpSession();s.setAttribute(AuthController.SESSION_USER,u);return s;}
    @Test void authenticationStillProtectsNewApi()throws Exception{mvc.perform(get("/api/ops/capabilities")).andExpect(status().isUnauthorized());}
    MockHttpSession user(String id)throws Exception{
        mvc.perform(post("/api/admin/users").session(admin()).contentType("application/json").content("{\"user_id\":\""+id+"\",\"password\":\"isolated-test-password\",\"name\":\"test\",\"enabled\":true}")).andExpect(status().isCreated());
        TUser u=new TUser();u.userId=id;MockHttpSession s=new MockHttpSession();s.setAttribute(AuthController.SESSION_USER,u);return s;
    }
    @Test void operationsOnlyAccountCannotBypassScopesThroughLegacyApis()throws Exception{
        MockHttpSession s=user("isolated-viewer");
        String grant="{\"id\":\"isolated-grant\",\"user_id\":\"isolated-viewer\",\"host_id\":\"*\",\"environment\":\"*\",\"actions\":\"read\"}";
        mvc.perform(post("/api/ops/resources/grant").session(admin()).header("X-Ops-Request","1").contentType("application/json").content(grant)).andExpect(status().isOk()).andExpect(jsonPath("$.data.ops_only").value(true));
        mvc.perform(get("/api/hosts").session(s)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("LEGACY_INTERFACE_DENIED"));
        mvc.perform(post("/api/services").session(s).contentType("application/json").content("{}")).andExpect(status().isForbidden());
        mvc.perform(get("/api/ops/capabilities").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.data.ops_only").value(true));
        java.util.Map<String,Object> g=repo.get("grant","isolated-grant");g.put("enabled",false);repo.save("grant",g);
        mvc.perform(get("/api/hosts").session(s)).andExpect(status().isForbidden());
        g=repo.get("grant","isolated-grant");g.put("ops_only",false);
        mvc.perform(post("/api/ops/resources/grant").session(admin()).header("X-Ops-Request","1").contentType("application/json").content(OpsData.json(g))).andExpect(status().isBadRequest());
        g.put("confirm_legacy_access","ALLOW_LEGACY:isolated-viewer");
        mvc.perform(post("/api/ops/resources/grant").session(admin()).header("X-Ops-Request","1").contentType("application/json").content(OpsData.json(g))).andExpect(status().isOk());
        mvc.perform(get("/api/hosts").session(s)).andExpect(status().isOk());
        mvc.perform(post("/api/auth/logout").session(s)).andExpect(status().isOk());
    }
    @Test void ordinaryExistingMonitorUserRetainsOriginalRoutes()throws Exception{
        MockHttpSession s=user("legacy-monitor-user");
        mvc.perform(get("/api/hosts").session(s)).andExpect(status().isOk());
        mvc.perform(get("/api/ops/capabilities").session(s)).andExpect(status().isOk()).andExpect(jsonPath("$.data.ops_only").value(false));
    }
    @Test void actualSpringRepositoryProxySupportsSchedulerAndApi()throws Exception{
        assertNotNull(repo.jdbc());assertNotNull(repo.transaction());tasks.dispatch();tasks.publishEvents();
        mvc.perform(get("/api/ops/capabilities").session(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.data.administrator").value(true));
        mvc.perform(get("/api/ops/resources/host").session(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
    }
    @Test void originalMonitoringRoutesRemainAvailable()throws Exception{
        for(String path:new String[]{"/api/dashboard","/api/services","/api/hosts","/api/alert-channels","/api/alerts"})
            mvc.perform(get(path).session(admin())).andExpect(status().isOk());
    }
    @Test void mutationUsesSessionHeaderValidationAndUnifiedResponse()throws Exception{
        String body="{\"id\":\"test-registry\",\"name\":\"test\",\"address\":\"docker.io\"}";
        mvc.perform(post("/api/ops/resources/registry").session(admin()).contentType("application/json").content(body)).andExpect(status().isForbidden());
        mvc.perform(post("/api/ops/resources/registry").session(admin()).header("X-Ops-Request","1").contentType("application/json").content(body)).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(get("/api/ops/audit").session(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.data").isArray());
    }
    @Test void historicalSnapshotsAreScopedWithoutOverwritingArchivedData()throws Exception{
        repo.save("host",OpsData.map("id","snapshot-host","environment","test"));
        repo.save("application",OpsData.map("id","owned-app","host_id","snapshot-host","environment","test"));
        repo.save("snapshot",OpsData.map("id","snapshot-host","host_id","snapshot-host","environment","test","data",OpsData.map("containers",java.util.Arrays.asList(OpsData.map("app_id","owned-app"),OpsData.map("app_id","other-host-app")))));
        mvc.perform(get("/api/ops/resources/snapshot").session(admin())).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].data.containers.length()").value(1));
        assertEquals(2,OpsData.rows(OpsData.object(repo.get("snapshot","snapshot-host"),"data"),"containers").size());
    }
}
