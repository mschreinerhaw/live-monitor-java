package com.live.monitor.controller;

import com.live.monitor.entity.TUser;
import com.live.monitor.service.CalendarService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.*;

class CalendarControllerTest {
 @Test void unauthenticatedAndNonAdminCannotPreviewOrMutate() throws Exception {
  CalendarService service=mock(CalendarService.class);MockMvc mvc=standaloneSetup(new CalendarController(service)).build();
  mvc.perform(post("/api/calendars/import/preview").contentType("application/json").content("{\"sql\":\"x\"}")).andExpect(status().isUnauthorized());
  TUser viewer=new TUser();viewer.userId="viewer";MockHttpSession session=new MockHttpSession();session.setAttribute(AuthController.SESSION_USER,viewer);
  mvc.perform(post("/api/calendars/import/preview").session(session).contentType("application/json").content("{\"sql\":\"x\"}")).andExpect(status().isForbidden());
  mvc.perform(post("/api/calendars/trading").session(session).contentType("application/json").content("{}")).andExpect(status().isForbidden());
  mvc.perform(delete("/api/calendars/trading/20260101/20251231").session(session)).andExpect(status().isForbidden());verifyNoInteractions(service);
 }
}
