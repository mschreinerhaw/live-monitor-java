package com.live.monitor.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.live.monitor.service.ExternalMessageRelayService;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ExternalMessageControllerTest {
    @Test
    void acceptsJsonObjectAsRawRequestBodyForRelayParsing() throws Exception {
        ExternalMessageRelayService relayService = mock(ExternalMessageRelayService.class);
        MockMvc mvc = MockMvcBuilders
            .standaloneSetup(new ExternalMessageController(relayService))
            .build();
        when(relayService.forward("{\"message\":\"hello\"}", "application/json", "127.0.0.1"))
            .thenReturn(Collections.<String, Object>singletonMap("success", true));

        mvc.perform(post("/api/external/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"hello\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true));

        verify(relayService).forward("{\"message\":\"hello\"}", "application/json", "127.0.0.1");
    }
}
