package com.live.monitor.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.entity.AlertChannel;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class AlertDeliveryServiceTest {
    private static final Charset GBK = Charset.forName("GBK");
    private static HttpServer server;
    private static URI requestUri;
    private static String baseUrl;

    @BeforeAll
    static void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/smsSendServlet.htm", exchange -> {
            requestUri = exchange.getRequestURI();
            byte[] body = "0_1:13800000000".getBytes(GBK);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/smsSendServlet.htm";
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void smsGatewayUsesConfiguredRequestShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AlertChannel channel = new AlertChannel();
        channel.channelType = "sms";
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("alert_mobile", "13800000000, 13900000000");
        config.put("sms_api_url", baseUrl);
        config.put("sms_username", "xxzx");
        config.put("sms_password_is_md5", true);
        config.put("sms_password_md5", "751CB3F4AA17C36186F4856C8982BF27");
        config.put("sms_rstype", "text");
        config.put("sms_ext_code", "99");
        channel.configJson = mapper.writeValueAsString(config);

        AlertDeliveryService.DeliveryResult result =
            new AlertDeliveryService(mapper).send(channel, "\u670d\u52a1\u5f02\u5e38");

        Map<String, String> params = parseQuery(requestUri.getRawQuery());
        assertTrue(result.success, result.message);
        assertEquals("/smsSendServlet.htm", requestUri.getPath());
        assertEquals("sendMD5", params.get("command"));
        assertEquals("xxzx", params.get("username"));
        assertEquals("751CB3F4AA17C36186F4856C8982BF27", params.get("pwd"));
        assertEquals("13800000000,13900000000", URLDecoder.decode(params.get("mobiles"), "UTF-8"));
        assertEquals("text", params.get("rstype"));
        assertEquals("99", params.get("extCode"));
        assertEquals("\u670d\u52a1\u5f02\u5e38", URLDecoder.decode(params.get("content"), GBK.name()));
    }

    @Test
    void rendersBundledJinjaTemplateVariables(@TempDir Path templateDir) {
        Map<String, Object> variables = new LinkedHashMap<String, Object>();
        variables.put("serviceName", "Order API");
        variables.put("instanceName", "10.0.0.8:8080");
        variables.put("alertTime", "2026-05-29 19:20:00");
        variables.put("errorMsg", "Connection refused");

        String rendered = new AlertDeliveryService(new ObjectMapper(), templateDir.resolve("missing"))
            .renderTemplate("sms_service_alert.j2", variables, "fallback");

        assertTrue(rendered.contains("Order API"));
        assertTrue(rendered.contains("10.0.0.8:8080"));
        assertTrue(rendered.contains("Connection refused"));
    }

    @Test
    void rendersExternalTemplateBeforeBundledResource(@TempDir Path templateDir) throws Exception {
        Files.write(
            templateDir.resolve("sms_service_alert.j2"),
            "custom {{ serviceName }}".getBytes(StandardCharsets.UTF_8)
        );
        Map<String, Object> variables = new LinkedHashMap<String, Object>();
        variables.put("serviceName", "Billing API");

        String rendered = new AlertDeliveryService(new ObjectMapper(), templateDir)
            .renderTemplate("sms_service_alert.j2", variables, "fallback");

        assertEquals("custom Billing API", rendered);
    }

    @Test
    void springCanCreateAlertDeliveryServiceBean() {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(ObjectMapper.class);
        context.register(AlertDeliveryService.class);
        context.refresh();
        try {
            assertTrue(context.getBean(AlertDeliveryService.class) != null);
        } finally {
            context.close();
        }
    }

    private static Map<String, String> parseQuery(String query) throws Exception {
        Map<String, String> params = new LinkedHashMap<String, String>();
        for (String pair : query.split("&")) {
            int index = pair.indexOf('=');
            String key = index < 0 ? pair : pair.substring(0, index);
            String value = index < 0 ? "" : pair.substring(index + 1);
            params.put(URLDecoder.decode(key, "UTF-8"), value);
        }
        return params;
    }
}
