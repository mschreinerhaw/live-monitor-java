package com.live.monitor.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.alert.*;
import com.live.monitor.dto.AlertContactPayload;
import com.live.monitor.entity.*;
import com.live.monitor.mapper.AlertMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AlertContactServiceTest {
    JdbcTemplate jdbc;AlertContactService contacts;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){DriverManagerDataSource ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=LEGACY;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(ds);jdbc=new JdbcTemplate(ds);contacts=new AlertContactService(jdbc);}
    long person(String name,String email,String mobile){AlertContactPayload p=new AlertContactPayload();p.name=name;p.email=email;p.mobile=mobile;return ((Number)contacts.save(null,p).get("id")).longValue();}
    AlertChannel channel(String type) throws Exception {AlertChannel c=new AlertChannel();c.id=7L;c.enabled=true;c.channelType=type;c.channelName="shared";c.configJson=json.writeValueAsString(base());return c;}
    Map<String,Object> base(){Map<String,Object> c=new LinkedHashMap<>();c.put("alert_email","legacy@example.com");c.put("alert_cc","legacy-cc@example.com");c.put("alert_mobile","13900000000");c.put("smtp_host","smtp.example.com");c.put("smtp_password","transport-secret");return c;}

    @Test void addressBookReferencesPreventDeletionAndOmittedBindingUpdatesPreserveSelections(){
        long id=person("Alice","alice@example.com","13800000000");contacts.replace("GROUP","1",Arrays.asList(id,id));
        assertEquals(Collections.singletonList(id),contacts.ids("GROUP","1"));contacts.replace("GROUP","1",null);assertEquals(1,contacts.ids("GROUP","1").size());
        assertThrows(ResponseStatusException.class,()->contacts.delete(id));assertThrows(ResponseStatusException.class,()->contacts.replace("GROUP","1",Arrays.asList(id,99999L)));assertEquals(Collections.singletonList(id),contacts.ids("GROUP","1"));
        assertEquals(1,((Number)contacts.list().get(0).get("binding_count")).intValue());contacts.clear("GROUP","1");contacts.delete(id);assertTrue(contacts.list().isEmpty());
        AlertContactPayload empty=new AlertContactPayload();empty.name="No address";assertThrows(ResponseStatusException.class,()->contacts.save(null,empty));
    }

    @Test void projectRecipientsOverrideGroupRecipientsAndDoNotChangeTransportCredentials() throws Exception {
        long alice=person("Alice","alice@example.com",null),bob=person("Bob","bob@example.com",null);contacts.replace("GROUP","1",Collections.singletonList(alice));
        AlertChannel group=contacts.bind(channel("email"),Collections.singletonList(1L),null);Map<String,Object> result=contacts.apply(group,base());
        assertEquals("alice@example.com",result.get("alert_email"));assertEquals("",result.get("alert_cc"));assertEquals("transport-secret",result.get("smtp_password"));
        AlertChannel project=contacts.bind(channel("email"),Collections.singletonList(1L),Collections.singletonList(bob));assertEquals("bob@example.com",contacts.apply(project,base()).get("alert_email"));
        AlertContactPayload updated=new AlertContactPayload();updated.name="Bob";updated.email="new-bob@example.com";contacts.save(bob,updated);
        assertEquals("new-bob@example.com",contacts.apply(project,base()).get("alert_email"));
        assertNull(contacts.bind(channel("email"),Collections.singletonList(99L),null).recipientContextJson);
    }

    @Test void alertDispatcherCarriesGroupAndProjectSelectionToTheActualDeliveryBoundary() throws Exception {
        long alice=person("Alice","alice@example.com",null),bob=person("Bob","bob@example.com",null);
        contacts.replace("GROUP","1",Collections.singletonList(alice));
        AlertMapper mapper=mock(AlertMapper.class);AlertGroup group=new AlertGroup();group.id=1L;group.enabled=true;
        when(mapper.findGroup(1L)).thenReturn(group);when(mapper.listChannelsByGroup(1L)).thenReturn(Collections.singletonList(channel("email")));
        AlertDeliveryService delivery=mock(AlertDeliveryService.class);when(delivery.send(any(),anyString())).thenReturn(AlertDeliveryService.DeliveryResult.success());
        com.live.monitor.store.RocksDbHistoryRepository history=mock(com.live.monitor.store.RocksDbHistoryRepository.class);when(history.saveAlertRecord(any())).thenAnswer(call->call.getArgument(0));
        AlertService alerts=new AlertService(mapper,history,delivery);alerts.setContacts(contacts);
        MonitorService service=new MonitorService();service.id=3L;service.alertGroupId=1L;service.alertGroupIds=Collections.singletonList(1L);service.alertGroupEnabled=true;
        alerts.testAlerts(service);service.alertContactIds=Collections.singletonList(bob);alerts.testAlerts(service);
        org.mockito.ArgumentCaptor<AlertChannel> sent=org.mockito.ArgumentCaptor.forClass(AlertChannel.class);verify(delivery,times(2)).send(sent.capture(),anyString());
        assertEquals("alice@example.com",contacts.apply(sent.getAllValues().get(0),base()).get("alert_email"));
        assertEquals("bob@example.com",contacts.apply(sent.getAllValues().get(1),base()).get("alert_email"));
    }

    @Test void disabledOrUnreachableSelectedContactsNeverFallBackToLegacyRecipients() throws Exception {
        long id=person("Alice","alice@example.com",null);contacts.replace("GROUP","1",Collections.singletonList(id));
        AlertChannel sms=contacts.bind(channel("sms"),Collections.singletonList(1L),null);assertThrows(IllegalArgumentException.class,()->contacts.apply(sms,base()));
        AlertChannel email=contacts.bind(channel("email"),Collections.singletonList(1L),null);jdbc.update("UPDATE alert_contact SET enabled=0 WHERE id=?",id);
        assertThrows(IllegalArgumentException.class,()->contacts.apply(email,base()));
        AlertDeliveryService delivery=new AlertDeliveryService(json);delivery.setContacts(contacts);assertFalse(delivery.send(email,"disabled contact").success);assertNotNull(delivery.configurationError(email));
    }

    @Test void sharedChannelMergesGroupsAndDeduplicatesRecipientsWhileLegacyGroupsRemainCompatible() throws Exception {
        long a=person("Alice","same@example.com","13800000000"),b=person("Bob","same@example.com","13800000000");contacts.replace("GROUP","1",Collections.singletonList(a));contacts.replace("GROUP","2",Collections.singletonList(b));
        AlertChannel merged=contacts.bind(channel("email"),Arrays.asList(1L,2L),null);assertEquals("same@example.com",contacts.apply(merged,base()).get("alert_email"));
        AlertChannel mixed=contacts.bind(channel("email"),Arrays.asList(1L,99L),null);assertEquals("legacy@example.com,same@example.com",contacts.apply(mixed,base()).get("alert_email"));
    }

    @Test void queuedTasksMergeContactIdsAndManualRetryPreservesSelection() throws Exception {
        long a=person("Alice","alice@example.com",null),b=person("Bob","bob@example.com",null);contacts.replace("GROUP","1",Collections.singletonList(a));contacts.replace("GROUP","2",Collections.singletonList(b));
        AlertOutbox outbox=new AlertOutbox(jdbc,5);outbox.enqueue(9L,3L,contacts.bind(channel("email"),Collections.singletonList(1L),null),"incident","availability");outbox.enqueue(9L,3L,contacts.bind(channel("email"),Collections.singletonList(2L),null),"incident","availability");
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM alert_delivery_task",Integer.class));Map<String,Object> task=outbox.due(Long.MAX_VALUE).get(0);String context=(String)task.get("recipient_context_json");assertFalse(context.contains("transport-secret"));
        AlertChannel combined=channel("email");combined.recipientContextJson=context;assertEquals("alice@example.com,bob@example.com",contacts.apply(combined,base()).get("alert_email"));
        jdbc.update("UPDATE alert_delivery_task SET delivery_status='FINAL_FAILED'");AlertMapper mapper=mock(AlertMapper.class);when(mapper.findChannel(7L)).thenReturn(channel("email"));AlertDeliveryService delivery=new AlertDeliveryService(json);delivery.setContacts(contacts);
        String retry=new AlertTaskAdmin(jdbc,mapper,delivery).retry(task.get("id").toString(),"admin");assertEquals(context,jdbc.queryForObject("SELECT recipient_context_json FROM alert_delivery_task WHERE id=?",String.class,retry));
        AlertDeliveryService mocked=mock(AlertDeliveryService.class);when(mocked.send(any(),anyString())).thenReturn(AlertDeliveryService.DeliveryResult.success());new AlertWorker(outbox,mapper,mocked,mock(com.live.monitor.store.RocksDbHistoryRepository.class)).poll();
        org.mockito.ArgumentCaptor<AlertChannel> sent=org.mockito.ArgumentCaptor.forClass(AlertChannel.class);verify(mocked).send(sent.capture(),eq("incident"));assertEquals(context,sent.getValue().recipientContextJson);
    }

    @Test void realSmsSenderUsesSelectedAddressAndUpdatedPhoneWithoutSendingToLegacyPhone() throws Exception {
        AtomicReference<String> query=new AtomicReference<>();HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/smsSendServlet.htm",exchange->{query.set(exchange.getRequestURI().getRawQuery());byte[] body="0".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);exchange.close();});server.start();
        try{
            long id=person("Alice",null,"13800000000");contacts.replace("GROUP","1",Collections.singletonList(id));AlertChannel c=contacts.bind(channel("sms"),Collections.singletonList(1L),null);Map<String,Object> config=base();config.put("sms_api_url","http://127.0.0.1:"+server.getAddress().getPort()+"/smsSendServlet.htm");config.put("sms_username","test");config.put("sms_password_md5","test");c.configJson=json.writeValueAsString(config);
            AlertDeliveryService delivery=new AlertDeliveryService(json);delivery.setContacts(contacts);AlertDeliveryService.DeliveryResult result=delivery.send(c,"local test");assertTrue(result.success,result.message);String params=URLDecoder.decode(query.get(),"UTF-8");assertTrue(params.contains("13800000000"));assertFalse(params.contains("13900000000"));
            query.set(null);jdbc.update("UPDATE alert_contact SET enabled=0 WHERE id=?",id);assertFalse(delivery.send(c,"disabled").success);assertNull(query.get());
        }finally{server.stop(0);}
    }
}
