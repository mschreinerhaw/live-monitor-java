package com.live.monitor.alert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.entity.AlertChannel;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
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
    private static String requestBody;
    private static String baseUrl;
    private static String robotUrl;

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
        server.createContext("/robot", exchange -> {
            requestUri = exchange.getRequestURI();
            requestBody = readRequestBody(exchange.getRequestBody());
            byte[] body = "{\"errcode\":0,\"errmsg\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/smsSendServlet.htm";
        robotUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/robot?access_token=test-token";
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
        requestUri = null;
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
    void dingtalkWebhookUsesRobotMessageShapeAndSignature() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        requestUri = null;
        requestBody = null;
        AlertChannel channel = new AlertChannel();
        channel.channelType = "dingtalk";
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("webhook_url", robotUrl);
        config.put("dingtalk_secret", "SEC123456");
        config.put("dingtalk_at_mobiles", "13800000000,13900000000");
        config.put("dingtalk_at_all", false);
        channel.configJson = mapper.writeValueAsString(config);

        AlertDeliveryService.DeliveryResult result =
            new AlertDeliveryService(mapper).send(channel, "service down");

        Map<String, Object> payload = mapper.readValue(requestBody, Map.class);
        Map<String, Object> text = (Map<String, Object>) payload.get("text");
        Map<String, Object> at = (Map<String, Object>) payload.get("at");
        Map<String, String> params = parseQuery(requestUri.getRawQuery());
        assertTrue(result.success, result.message);
        assertEquals("/robot", requestUri.getPath());
        assertEquals("text", payload.get("msgtype"));
        assertEquals("service down", text.get("content"));
        assertEquals(false, at.get("isAtAll"));
        assertEquals("test-token", params.get("access_token"));
        assertTrue(params.containsKey("timestamp"));
        assertTrue(params.containsKey("sign"));
    }

    @Test
    void wecomWebhookUsesTextMessageShape() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        requestUri = null;
        requestBody = null;
        AlertChannel channel = new AlertChannel();
        channel.channelType = "wecom";
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("webhook_url", robotUrl);
        config.put("wecom_mentioned_list", "zhangsan");
        config.put("wecom_mentioned_mobiles", "13800000000");
        config.put("wecom_at_all", true);
        channel.configJson = mapper.writeValueAsString(config);

        AlertDeliveryService.DeliveryResult result =
            new AlertDeliveryService(mapper).send(channel, "service down");

        Map<String, Object> payload = mapper.readValue(requestBody, Map.class);
        Map<String, Object> text = (Map<String, Object>) payload.get("text");
        assertTrue(result.success, result.message);
        assertEquals("/robot", requestUri.getPath());
        assertEquals("text", payload.get("msgtype"));
        assertEquals("service down", text.get("content"));
        assertTrue(((java.util.List<?>) text.get("mentioned_list")).contains("zhangsan"));
        assertTrue(((java.util.List<?>) text.get("mentioned_list")).contains("@all"));
        assertTrue(((java.util.List<?>) text.get("mentioned_mobile_list")).contains("13800000000"));
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
    void rendersHostResourceTemplateReasons(@TempDir Path templateDir) {
        Map<String, Object> variables = new LinkedHashMap<String, Object>();
        variables.put("host", "10.0.0.8");
        variables.put("cpuText", "45.0% / 85.0%");
        variables.put("memoryText", "90.0% / 80.0%");
        variables.put("diskText", "20.0% / 90.0%");
        variables.put("alertItems", "MEM");
        variables.put("recoverItems", "MEM");
        variables.put("alertReason", "MEMORY_USED_90_GE_80");
        variables.put("recoverReason", "ALL_RESOURCE_METRICS_RECOVERED");
        variables.put("alertSummary", "memory threshold breached");
        variables.put("historyAlert", "MEMORY_USED_90_GE_80");
        variables.put("duration", "180s");
        variables.put("alertId", "alert-001");
        variables.put("alertTime", "2026-06-02 18:10:00");
        variables.put("recoverTime", "2026-06-02 18:13:00");

        AlertDeliveryService service = new AlertDeliveryService(new ObjectMapper(), templateDir.resolve("missing"));
        String rendered = service.renderTemplate("alert_host_resource.j2", variables, "fallback");
        String recoverRendered = service.renderTemplate("alert_host_resource_recover.j2", variables, "fallback");

        assertTrue(rendered.contains("MEMORY_USED_90_GE_80"));
        assertTrue(rendered.contains("memory threshold breached"));
        assertTrue(recoverRendered.contains("ALL_RESOURCE_METRICS_RECOVERED"));
        assertTrue(recoverRendered.contains("MEMORY_USED_90_GE_80"));
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

    private static String readRequestBody(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
