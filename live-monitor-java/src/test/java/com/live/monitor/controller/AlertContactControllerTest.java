package com.live.monitor.controller;

import com.live.monitor.entity.TUser;
import com.live.monitor.service.AlertContactService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.*;

class AlertContactControllerTest {
 @Test void mutationsRequireAdministratorAndValidateContactFields() throws Exception {
  AlertContactService service=mock(AlertContactService.class);MockMvc mvc=standaloneSetup(new AlertContactController(service)).build();String body="{\"name\":\"Alice\",\"email\":\"alice@example.com\"}";
  mvc.perform(post("/api/alert-contacts").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
  TUser user=new TUser();user.userId="viewer";MockHttpSession viewer=new MockHttpSession();viewer.setAttribute(AuthController.SESSION_USER,user);
  mvc.perform(post("/api/alert-contacts").session(viewer).contentType("application/json").content(body)).andExpect(status().isForbidden());mvc.perform(delete("/api/alert-contacts/1").session(viewer)).andExpect(status().isForbidden());verifyNoInteractions(service);
  user=new TUser();user.userId="admin";MockHttpSession admin=new MockHttpSession();admin.setAttribute(AuthController.SESSION_USER,user);
  mvc.perform(post("/api/alert-contacts").session(admin).contentType("application/json").content("{\"name\":\"Alice\",\"email\":\"invalid\"}")).andExpect(status().isBadRequest());
  mvc.perform(post("/api/alert-contacts").session(admin).contentType("application/json").content(body)).andExpect(status().isCreated());verify(service).save(isNull(),any());
 }
}
