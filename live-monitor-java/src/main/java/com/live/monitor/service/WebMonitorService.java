package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import com.live.monitor.rule.ApiRuleEvaluator;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WebMonitorService {
    private final ApiRuleEvaluator apiRuleEvaluator;
    private static final int MAX_RESPONSE_BODY_CHARS = 65536;

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

    private static final HostnameVerifier TRUST_ALL_HOSTNAMES = (hostname, session) -> true;

    public WebMonitorService(ApiRuleEvaluator apiRuleEvaluator) {
        this.apiRuleEvaluator = apiRuleEvaluator;
    }

    public CheckResult check(
        String url,
        String method,
        Integer expectedStatusCode,
        String keyword,
        boolean ignoreSslVerification,
        double timeoutSeconds
    ) {
        return check(url, method, expectedStatusCode, keyword, null, ignoreSslVerification, timeoutSeconds);
    }

    public CheckResult check(
        String url,
        String method,
        Integer expectedStatusCode,
        String keyword,
        String apiAssertionExpression,
        boolean ignoreSslVerification,
        double timeoutSeconds
    ) {
        return checkWeb(url, method, expectedStatusCode, keyword, apiAssertionExpression, ignoreSslVerification, timeoutSeconds);
    }

    public CheckResult checkWeb(
        String url,
        String method,
        Integer expectedStatusCode,
        String keyword,
        String apiAssertionExpression,
        boolean ignoreSslVerification,
        double timeoutSeconds
    ) {
        return execute(url, method, expectedStatusCode, keyword, apiAssertionExpression, ignoreSslVerification, timeoutSeconds, RequestOptions.web());
    }

    public CheckResult checkApi(
        String url,
        String method,
        Integer expectedStatusCode,
        String keyword,
        String apiAssertionExpression,
        boolean ignoreSslVerification,
        double timeoutSeconds,
        RequestOptions options
    ) {
        return execute(url, method, expectedStatusCode, keyword, apiAssertionExpression, ignoreSslVerification, timeoutSeconds, options == null ? RequestOptions.api() : options.asApi());
    }

    private CheckResult execute(
        String url,
        String method,
        Integer expectedStatusCode,
        String keyword,
        String apiAssertionExpression,
        boolean ignoreSslVerification,
        double timeoutSeconds,
        RequestOptions options
    ) {
        if (!StringUtils.hasText(url)) {
            return new CheckResult("UNKNOWN", null, "URL is required for HTTP request checks");
        }
        long started = System.nanoTime();
        OkHttpClient.Builder clientBuilder = new OkHttpClient.Builder()
            .connectTimeout((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS)
            .readTimeout((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS);
        if (ignoreSslVerification && url.trim().toLowerCase().startsWith("https://")) {
            try {
                clientBuilder
                    .sslSocketFactory(trustAllSslSocketFactory(), TRUST_ALL_CERTS)
                    .hostnameVerifier(TRUST_ALL_HOSTNAMES);
            } catch (Exception ex) {
                return new CheckResult("DOWN", elapsedMs(started), "Failed to initialize insecure SSL client: " + ex.getMessage());
            }
        }
        OkHttpClient client = clientBuilder.build();
        String normalizedMethod = StringUtils.hasText(method) ? method.toUpperCase() : "GET";
        RequestBody requestBody = requestBody(normalizedMethod, options);
        Request.Builder requestBuilder = new Request.Builder()
            .url(url)
            .method(normalizedMethod, requestBody)
            .header("User-Agent", "LiveMonitor/1.0");
        applyHeaders(requestBuilder, options);
        Request request = requestBuilder.build();
        try (Response response = client.newCall(request).execute()) {
            int elapsed = elapsedMs(started);
            int code = response.code();
            boolean statusOk = expectedStatusCode == null ? code >= 200 && code < 400 : code == expectedStatusCode;
            boolean keywordOk = true;
            boolean apiRuleOk = true;
            ResponseBody responseBody = response.body();
            boolean needsBody = options.apiMode || StringUtils.hasText(keyword) || StringUtils.hasText(apiAssertionExpression);
            String text = needsBody && responseBody != null ? responseBody.string() : "";
            if (StringUtils.hasText(keyword)) {
                keywordOk = text.contains(keyword);
            }
            ApiRuleEvaluator.Evaluation apiRule = null;
            if (StringUtils.hasText(apiAssertionExpression)) {
                apiRule = apiRuleEvaluator.evaluate(
                    apiAssertionExpression,
                    new ApiRuleEvaluator.ResponseContext(code, elapsed, text)
                );
                apiRuleOk = apiRule.matched;
            }
            String message = "HTTP " + code
                + (StringUtils.hasText(keyword) ? ", keyword " + (keywordOk ? "matched" : "missing") : "")
                + (apiRule == null ? "" : ", " + apiRule.message + ruleDetail(apiRule));
            CheckResult result = new CheckResult(statusOk && keywordOk && apiRuleOk ? "UP" : "DOWN", elapsed, message);
            result.httpStatusCode = code;
            if (options.apiMode) {
                result.responseSizeBytes = (long) text.getBytes(StandardCharsets.UTF_8).length;
                result.responseBody = shortBody(text);
            }
            return result;
        } catch (Exception ex) {
            return new CheckResult("DOWN", elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private RequestBody requestBody(String method, RequestOptions options) {
        if (!methodAllowsBody(method)) {
            return null;
        }
        String body = options == null || options.body == null || !options.apiMode ? "" : options.body;
        MediaType mediaType = null;
        if (options != null && options.apiMode && StringUtils.hasText(options.contentType)) {
            mediaType = MediaType.parse(options.contentType.trim());
        }
        return RequestBody.create(body.getBytes(StandardCharsets.UTF_8), mediaType);
    }

    private boolean methodAllowsBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private void applyHeaders(Request.Builder requestBuilder, RequestOptions options) {
        if (options == null || !options.apiMode || options.headers == null) {
            return;
        }
        for (Map.Entry<String, String> entry : options.headers.entrySet()) {
            if (!StringUtils.hasText(entry.getKey())) {
                continue;
            }
            requestBuilder.header(entry.getKey().trim(), entry.getValue() == null ? "" : entry.getValue());
        }
    }

    private String shortBody(String value) {
        if (value == null || value.length() <= MAX_RESPONSE_BODY_CHARS) {
            return value;
        }
        return value.substring(0, MAX_RESPONSE_BODY_CHARS) + "\n... response truncated by Live Monitor";
    }

    private String ruleDetail(ApiRuleEvaluator.Evaluation apiRule) {
        String detail = "";
        if (StringUtils.hasText(apiRule.hitContent)) {
            detail += ", hit: " + shortText(apiRule.hitContent, 120);
        }
        if (StringUtils.hasText(apiRule.failureReason)) {
            detail += ", reason: " + shortText(apiRule.failureReason, 120);
        }
        return detail;
    }

    private String shortText(String value, int limit) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= limit ? normalized : normalized.substring(0, limit - 3) + "...";
    }

    private int elapsedMs(long started) {
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private SSLSocketFactory trustAllSslSocketFactory() throws Exception {
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, new X509TrustManager[] { TRUST_ALL_CERTS }, new SecureRandom());
        return sslContext.getSocketFactory();
    }

    public static class RequestOptions {
        public Map<String, String> headers = new LinkedHashMap<String, String>();
        public String body;
        public String contentType;
        public boolean apiMode;

        public static RequestOptions web() {
            RequestOptions options = new RequestOptions();
            options.apiMode = false;
            return options;
        }

        public static RequestOptions api() {
            RequestOptions options = new RequestOptions();
            options.apiMode = true;
            return options;
        }

        private RequestOptions asApi() {
            this.apiMode = true;
            return this;
        }
    }
}
