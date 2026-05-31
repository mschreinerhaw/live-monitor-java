package com.live.monitor.alert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.live.monitor.entity.AlertChannel;
import com.live.monitor.service.CryptoService;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AlertDeliveryService {
    private static final TypeReference<Map<String, Object>> STRING_OBJECT_MAP =
        new TypeReference<Map<String, Object>>() {};
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    private static final Charset GBK = Charset.forName("GBK");
    private static final String TEMPLATE_RESOURCE_PREFIX = "templates/";
    private static final Pattern JINJA_VARIABLE =
        Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*\\}\\}");
    private static final X509TrustManager TRUST_ALL_CERTS = new X509TrustManager() {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    };

    private final ObjectMapper objectMapper;
    private CryptoService cryptoService;
    private final OkHttpClient httpClient;
    private Path templateDirectory;

    @Autowired
    public AlertDeliveryService(ObjectMapper objectMapper) {
        this(objectMapper, null, Paths.get("./templates"));
    }

    AlertDeliveryService(ObjectMapper objectMapper, Path templateDirectory) {
        this(objectMapper, null, templateDirectory);
    }

    AlertDeliveryService(ObjectMapper objectMapper, CryptoService cryptoService, Path templateDirectory) {
        this.objectMapper = objectMapper;
        this.cryptoService = cryptoService;
        this.templateDirectory = templateDirectory;
        this.httpClient = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    }

    @Autowired(required = false)
    public void setCryptoService(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }

    @Value("${live-monitor.template-dir:./templates}")
    public void setTemplateDirectory(String templateDirectory) {
        if (StringUtils.hasText(templateDirectory)) {
            this.templateDirectory = Paths.get(templateDirectory.trim());
        }
    }

    public String renderTemplate(String templateName, Map<String, Object> variables, String fallback) {
        try {
            return renderJinjaVariables(readTemplate(templateName), variables);
        } catch (IOException ex) {
            return fallback;
        }
    }

    public DeliveryResult send(AlertChannel channel, String content) {
        String type = normalize(channel.channelType);
        Map<String, Object> config = parseJson(channel.configJson);
        try {
            if ("email".equals(type)) {
                sendEmail(config, content);
                return DeliveryResult.success();
            }
            if ("sms".equals(type)) {
                sendSms(config, content);
                return DeliveryResult.success();
            }
            if ("webhook".equals(type) || "dingtalk".equals(type) || "wecom".equals(type)) {
                sendWebhook(type, config, content);
                return DeliveryResult.success();
            }
            return DeliveryResult.failed("Unsupported alert channel type: " + channel.channelType);
        } catch (Exception ex) {
            return DeliveryResult.failed(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private void sendSms(Map<String, Object> config, String content) throws IOException {
        String apiUrl = stringValue(config, "sms_api_url");
        if (!StringUtils.hasText(apiUrl)) {
            throw new IOException("SMS API URL is empty");
        }
        List<String> mobiles = splitRecipients(stringValue(config, "alert_mobile"));
        if (mobiles.isEmpty()) {
            throw new IOException("SMS recipient is empty");
        }
        String username = stringValue(config, "sms_username");
        if (StringUtils.hasText(username)) {
            for (int index = 0; index < mobiles.size(); index += 100) {
                String batch = String.join(",", mobiles.subList(index, Math.min(index + 100, mobiles.size())));
                sendSmsGateway(config, apiUrl, username, batch, content);
            }
            return;
        }
        String token = stringValue(config, "sms_api_token");
        for (String mobile : mobiles) {
            sendSmsJsonApi(apiUrl, token, mobile, content);
        }
    }

    private void sendSmsGateway(
        Map<String, Object> config,
        String apiUrl,
        String username,
        String mobiles,
        String content
    ) throws IOException {
        Map<String, String> params = new LinkedHashMap<String, String>();
        params.put("command", "sendMD5");
        params.put("username", username);
        params.put("pwd", resolveSmsPassword(config));
        params.put("mobiles", mobiles);
        params.put("content", percentEncode(content.getBytes(GBK)));
        params.put("rstype", defaultString(stringValue(config, "sms_rstype"), "text"));
        String extCode = stringValue(config, "sms_ext_code");
        if (StringUtils.hasText(extCode)) {
            params.put("extCode", extCode.trim());
        }

        List<String> query = new ArrayList<String>();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if ("content".equals(entry.getKey())) {
                query.add(entry.getKey() + "=" + entry.getValue());
            } else {
                query.add(entry.getKey() + "=" + URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8.name()));
            }
        }
        String separator = apiUrl.contains("?") ? "&" : "?";
        Request request = new Request.Builder()
            .url(apiUrl + separator + String.join("&", query))
            .get()
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : new String(response.body().bytes(), GBK);
            if (!response.isSuccessful()) {
                throw new IOException("SMS gateway HTTP " + response.code());
            }
            Map<String, Object> parsed = parseSmsGatewayResult(body);
            if (!Boolean.TRUE.equals(parsed.get("success"))) {
                throw new IOException("SMS gateway returned " + parsed.get("raw"));
            }
        }
    }

    private void sendSmsJsonApi(String apiUrl, String token, String mobile, String content) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("mobile", mobile);
        payload.put("content", content);
        Request.Builder builder = new Request.Builder()
            .url(apiUrl)
            .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON));
        if (StringUtils.hasText(token)) {
            builder.header("Authorization", "Bearer " + token.trim());
        }
        try (Response response = httpClient.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("SMS API HTTP " + response.code());
            }
        }
    }

    private void sendWebhook(String type, Map<String, Object> config, String content) throws IOException {
        String webhookUrl = firstText(config, "webhook_url", "sms_api_url");
        if (!StringUtils.hasText(webhookUrl)) {
            throw new IOException("Webhook URL is empty");
        }
        if ("dingtalk".equals(type)) {
            webhookUrl = signedDingtalkWebhookUrl(webhookUrl, stringValue(config, "dingtalk_secret"));
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        if ("dingtalk".equals(type)) {
            Map<String, Object> text = new LinkedHashMap<String, Object>();
            text.put("content", content);
            payload.put("msgtype", "text");
            payload.put("text", text);
            Map<String, Object> at = new LinkedHashMap<String, Object>();
            at.put("atMobiles", splitRecipients(stringValue(config, "dingtalk_at_mobiles")));
            at.put("isAtAll", booleanValue(config.get("dingtalk_at_all"), false));
            payload.put("at", at);
        } else if ("wecom".equals(type)) {
            Map<String, Object> text = new LinkedHashMap<String, Object>();
            text.put("content", content);
            List<String> mentionedList = splitRecipients(stringValue(config, "wecom_mentioned_list"));
            if (booleanValue(config.get("wecom_at_all"), false) && !mentionedList.contains("@all")) {
                mentionedList.add("@all");
            }
            text.put("mentioned_list", mentionedList);
            text.put("mentioned_mobile_list", splitRecipients(stringValue(config, "wecom_mentioned_mobiles")));
            payload.put("msgtype", "text");
            payload.put("text", text);
        } else {
            payload.put("content", content);
        }
        Request request = new Request.Builder()
            .url(webhookUrl)
            .post(RequestBody.create(objectMapper.writeValueAsString(payload), JSON))
            .build();
        try (Response response = httpClient.newCall(request).execute()) {
            String body = response.body() == null ? "" : response.body().string();
            if (!response.isSuccessful()) {
                throw new IOException("Webhook HTTP " + response.code());
            }
            validateWebhookResponse(type, body);
        }
    }

    private String signedDingtalkWebhookUrl(String webhookUrl, String secret) throws IOException {
        if (!StringUtils.hasText(secret)) {
            return webhookUrl;
        }
        try {
            String timestamp = String.valueOf(System.currentTimeMillis());
            String stringToSign = timestamp + "\n" + secret.trim();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.trim().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = URLEncoder.encode(
                Base64.getEncoder().encodeToString(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8))),
                StandardCharsets.UTF_8.name()
            );
            String separator = webhookUrl.contains("?") ? "&" : "?";
            return webhookUrl + separator + "timestamp=" + timestamp + "&sign=" + sign;
        } catch (Exception ex) {
            throw new IOException("Unable to sign DingTalk webhook", ex);
        }
    }

    private void validateWebhookResponse(String type, String body) throws IOException {
        if (!StringUtils.hasText(body) || !("dingtalk".equals(type) || "wecom".equals(type))) {
            return;
        }
        Map<String, Object> result;
        try {
            result = objectMapper.readValue(body, STRING_OBJECT_MAP);
        } catch (Exception ignored) {
            // Some custom webhook gateways return plain text. Keep HTTP success as success for those.
            return;
        }
        Object errCode = result.get("errcode");
        if (errCode == null || intValue(errCode, 0) == 0) {
            return;
        }
        String message = stringValue(result, "errmsg");
        throw new IOException(channelDisplayName(type) + " webhook returned " + errCode + ": " + message);
    }

    private String channelDisplayName(String type) {
        if ("dingtalk".equals(type)) {
            return "DingTalk";
        }
        if ("wecom".equals(type)) {
            return "WeCom";
        }
        return "Webhook";
    }

    private void sendEmail(Map<String, Object> config, String content) throws IOException {
        String host = stringValue(config, "smtp_host");
        if (!StringUtils.hasText(host)) {
            throw new IOException("SMTP host is empty");
        }
        List<String> recipients = splitRecipients(stringValue(config, "alert_email"));
        if (recipients.isEmpty()) {
            throw new IOException("Email recipient is empty");
        }
        List<String> ccRecipients = splitRecipients(stringValue(config, "alert_cc"));
        List<String> envelopeRecipients = new ArrayList<String>();
        envelopeRecipients.addAll(recipients);
        envelopeRecipients.addAll(ccRecipients);
        int port = intValue(config.get("smtp_port"), 25);
        String user = stringValue(config, "smtp_user");
        String password = stringValue(config, "smtp_password");
        String from = firstNonBlank(stringValue(config, "smtp_from"), user, recipients.get(0));
        boolean useAuth = booleanValue(config.get("smtp_auth"), StringUtils.hasText(user));
        boolean useTls = booleanValue(config.get("smtp_use_tls"), false);
        boolean sslOnConnect = booleanValue(config.get("smtp_use_ssl"), port == 465);
        if (useAuth && !StringUtils.hasText(user)) {
            throw new IOException("SMTP auth account is empty");
        }
        if (useAuth && !StringUtils.hasText(password)) {
            throw new IOException("SMTP auth password is empty");
        }

        SSLSocketFactory sslSocketFactory = smtpSslSocketFactory(config, host);
        Socket socket = sslOnConnect
            ? sslSocketFactory.createSocket(host, port)
            : new Socket(host, port);
        socket.setSoTimeout(10000);
        BufferedReader reader = null;
        BufferedWriter writer = null;
        try {
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
            expect(reader, 220);
            ehlo(reader, writer);
            if (useTls && !sslOnConnect) {
                command(reader, writer, "STARTTLS", 220);
                socket = sslSocketFactory.createSocket(socket, host, port, true);
                socket.setSoTimeout(10000);
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                ehlo(reader, writer);
            }
            if (useAuth) {
                command(reader, writer, "AUTH LOGIN", 334);
                command(reader, writer, base64(user), 334);
                command(reader, writer, base64(password), 235);
            }
            command(reader, writer, "MAIL FROM:<" + cleanAddress(from) + ">", 250);
            for (String recipient : envelopeRecipients) {
                command(reader, writer, "RCPT TO:<" + cleanAddress(recipient) + ">", 250, 251);
            }
            command(reader, writer, "DATA", 354);
            String contentType = emailContentType(config, content);
            writeData(writer, buildEmailMessage(from, recipients, ccRecipients, "Live Monitor Alert", content, contentType));
            expect(reader, 250);
            command(reader, writer, "QUIT", 221);
        } finally {
            socket.close();
        }
    }

    private String buildEmailMessage(
        String from,
        List<String> recipients,
        List<String> ccRecipients,
        String subject,
        String content,
        String contentType
    ) {
        String body = base64(content);
        StringBuilder message = new StringBuilder();
        message.append("From: ").append(from).append("\r\n")
            .append("To: ").append(String.join(", ", recipients)).append("\r\n");
        if (ccRecipients != null && !ccRecipients.isEmpty()) {
            message.append("Cc: ").append(String.join(", ", ccRecipients)).append("\r\n");
        }
        message.append("Subject: ").append(encodedHeader(subject)).append("\r\n")
            .append("Date: ").append(rfc2822Date()).append("\r\n")
            .append("MIME-Version: 1.0\r\n")
            .append("Content-Type: ").append(contentType).append("; charset=UTF-8\r\n")
            .append("Content-Transfer-Encoding: base64\r\n")
            .append("\r\n")
            .append(wrapBase64(body)).append("\r\n");
        return message.toString();
    }

    private String readTemplate(String templateName) throws IOException {
        String fileName = templateName.endsWith(".j2") ? templateName : templateName + ".j2";
        Path external = templateDirectory.resolve(fileName).normalize();
        if (Files.isRegularFile(external)) {
            return new String(Files.readAllBytes(external), StandardCharsets.UTF_8);
        }
        InputStream stream = Thread.currentThread().getContextClassLoader()
            .getResourceAsStream(TEMPLATE_RESOURCE_PREFIX + fileName);
        if (stream == null) {
            throw new IOException("Template not found: " + fileName);
        }
        try {
            return readAll(stream);
        } finally {
            stream.close();
        }
    }

    private String readAll(InputStream stream) throws IOException {
        byte[] buffer = new byte[4096];
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private String renderJinjaVariables(String template, Map<String, Object> variables) {
        Matcher matcher = JINJA_VARIABLE.matcher(template);
        StringBuffer rendered = new StringBuffer();
        while (matcher.find()) {
            Object value = variables == null ? null : variables.get(matcher.group(1));
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value == null ? "" : String.valueOf(value)));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private String emailContentType(Map<String, Object> config, String content) {
        String configured = normalize(stringValue(config, "email_content_type"));
        if ("text/html".equals(configured) || "html".equals(configured)) {
            return "text/html";
        }
        if ("text/plain".equals(configured) || "plain".equals(configured)) {
            return "text/plain";
        }
        return looksLikeHtml(content) ? "text/html" : "text/plain";
    }

    private boolean looksLikeHtml(String content) {
        String normalized = content == null ? "" : content.trim().toLowerCase(Locale.ROOT);
        return normalized.contains("<html")
            || normalized.contains("<body")
            || normalized.contains("<table")
            || normalized.contains("<h1")
            || normalized.contains("<h2")
            || normalized.contains("<p")
            || normalized.contains("<br")
            || normalized.contains("<div");
    }

    private SSLSocketFactory smtpSslSocketFactory(Map<String, Object> config, String host) throws IOException {
        if (!trustAllSmtpCertificates(config, host)) {
            return (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[] { TRUST_ALL_CERTS }, new SecureRandom());
            return sslContext.getSocketFactory();
        } catch (Exception ex) {
            throw new IOException("Unable to initialize trusted SMTP SSL context", ex);
        }
    }

    private boolean trustAllSmtpCertificates(Map<String, Object> config, String host) {
        String trust = stringValue(config, "smtp_ssl_trust");
        if (!StringUtils.hasText(trust)) {
            return false;
        }
        String normalized = trust.trim().toLowerCase(Locale.ROOT);
        if ("*".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized) || "1".equals(normalized)) {
            return true;
        }
        String lowerHost = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        for (String item : normalized.split("[,;\\s\\uFF0C\\uFF1B]+")) {
            if (lowerHost.equals(item.trim())) {
                return true;
            }
        }
        return false;
    }

    private void writeData(BufferedWriter writer, String message) throws IOException {
        for (String line : message.split("\\r?\\n", -1)) {
            writer.write(line.startsWith(".") ? "." + line : line);
            writer.write("\r\n");
        }
        writer.write(".\r\n");
        writer.flush();
    }

    private void ehlo(BufferedReader reader, BufferedWriter writer) throws IOException {
        command(reader, writer, "EHLO live-monitor", 250);
    }

    private void command(BufferedReader reader, BufferedWriter writer, String command, int... expectedCodes)
        throws IOException {
        writer.write(command + "\r\n");
        writer.flush();
        expect(reader, expectedCodes);
    }

    private void expect(BufferedReader reader, int... expectedCodes) throws IOException {
        String response = readSmtpResponse(reader);
        int code = smtpCode(response);
        for (int expected : expectedCodes) {
            if (code == expected) {
                return;
            }
        }
        throw new IOException("SMTP server returned " + response);
    }

    private String readSmtpResponse(BufferedReader reader) throws IOException {
        StringBuilder response = new StringBuilder();
        String line;
        do {
            line = reader.readLine();
            if (line == null) {
                throw new IOException("SMTP server closed the connection");
            }
            if (response.length() > 0) {
                response.append(" | ");
            }
            response.append(line);
        } while (line.length() >= 4 && line.charAt(3) == '-');
        return response.toString();
    }

    private int smtpCode(String response) throws IOException {
        if (response.length() < 3) {
            throw new IOException("Invalid SMTP response: " + response);
        }
        try {
            return Integer.parseInt(response.substring(0, 3));
        } catch (NumberFormatException ex) {
            throw new IOException("Invalid SMTP response: " + response, ex);
        }
    }

    private String resolveSmsPassword(Map<String, Object> config) {
        boolean passwordIsMd5 = booleanValue(config.get("sms_password_is_md5"), true);
        if (passwordIsMd5) {
            return stringValue(config, "sms_password_md5").trim().toUpperCase(Locale.ROOT);
        }
        return md5Upper(stringValue(config, "sms_password"));
    }

    private Map<String, Object> parseSmsGatewayResult(String text) {
        String raw = text == null ? "" : text.trim();
        String code = raw.contains("_") ? raw.substring(0, raw.indexOf('_')).trim() : raw;
        Map<String, Object> result = new HashMap<String, Object>();
        result.put("raw", raw);
        result.put("code", code);
        result.put("success", "0".equals(code));
        return result;
    }

    private List<String> splitRecipients(String raw) {
        String[] parts = String.valueOf(raw == null ? "" : raw).split("[,;\\s\\uFF0C\\uFF1B]+");
        List<String> recipients = new ArrayList<String>();
        for (String part : parts) {
            String value = part == null ? "" : part.trim();
            if (value.isEmpty() || recipients.contains(value)) {
                continue;
            }
            recipients.add(value);
        }
        return recipients;
    }

    private Map<String, Object> parseJson(String json) {
        if (!StringUtils.hasText(json)) {
            return new HashMap<String, Object>();
        }
        try {
            String normalized = cryptoService == null ? json : cryptoService.decryptIfEncrypted(json);
            return objectMapper.readValue(normalized, STRING_OBJECT_MAP);
        } catch (Exception ex) {
            return new HashMap<String, Object>();
        }
    }

    private String stringValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private String firstText(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            String value = stringValue(map, key);
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() != 0;
        }
        if (value != null && StringUtils.hasText(String.valueOf(value))) {
            String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
            return "1".equals(normalized) || "true".equals(normalized) || "yes".equals(normalized) || "on".equals(normalized);
        }
        return fallback;
    }

    private String defaultString(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StringUtils.hasText(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String cleanAddress(String value) {
        String address = value == null ? "" : value.trim();
        int start = address.indexOf('<');
        int end = address.indexOf('>');
        if (start >= 0 && end > start) {
            return address.substring(start + 1, end).trim();
        }
        return address;
    }

    private String percentEncode(byte[] bytes) {
        StringBuilder encoded = new StringBuilder();
        for (byte raw : bytes) {
            int value = raw & 0xff;
            if ((value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z')
                || (value >= '0' && value <= '9') || value == '-' || value == '_' || value == '.' || value == '~') {
                encoded.append((char) value);
            } else {
                encoded.append('%');
                String hex = Integer.toHexString(value).toUpperCase(Locale.ROOT);
                if (hex.length() == 1) {
                    encoded.append('0');
                }
                encoded.append(hex);
            }
        }
        return encoded.toString();
    }

    private String md5Upper(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            byte[] bytes = digest.digest(String.valueOf(text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte value : bytes) {
                String part = Integer.toHexString(value & 0xff).toUpperCase(Locale.ROOT);
                if (part.length() == 1) {
                    hex.append('0');
                }
                hex.append(part);
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to calculate MD5", ex);
        }
    }

    private String base64(String value) {
        return Base64.getEncoder().encodeToString(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
    }

    private String encodedHeader(String value) {
        return "=?UTF-8?B?" + base64(value) + "?=";
    }

    private String wrapBase64(String value) {
        StringBuilder wrapped = new StringBuilder();
        for (int index = 0; index < value.length(); index += 76) {
            if (index > 0) {
                wrapped.append("\r\n");
            }
            wrapped.append(value, index, Math.min(index + 76, value.length()));
        }
        return wrapped.toString();
    }

    private String rfc2822Date() {
        SimpleDateFormat format = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    public static class DeliveryResult {
        public final boolean success;
        public final String message;

        private DeliveryResult(boolean success, String message) {
            this.success = success;
            this.message = message;
        }

        public static DeliveryResult success() {
            return new DeliveryResult(true, null);
        }

        public static DeliveryResult failed(String message) {
            return new DeliveryResult(false, message);
        }
    }
}
