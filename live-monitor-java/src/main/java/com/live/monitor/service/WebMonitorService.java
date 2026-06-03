package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import com.live.monitor.rule.ApiRuleEvaluator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;
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
        if (!StringUtils.hasText(url)) {
            return new CheckResult("UNKNOWN", null, "URL is required for web service checks");
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
        RequestBody requestBody = "POST".equals(normalizedMethod) ? RequestBody.create(new byte[0], null) : null;
        Request request = new Request.Builder()
            .url(url)
            .method(normalizedMethod, requestBody)
            .header("User-Agent", "LiveMonitor/1.0")
            .build();
        try (Response response = client.newCall(request).execute()) {
            int elapsed = elapsedMs(started);
            int code = response.code();
            boolean statusOk = expectedStatusCode == null ? code >= 200 && code < 400 : code == expectedStatusCode;
            boolean keywordOk = true;
            boolean apiRuleOk = true;
            String text = null;
            if (StringUtils.hasText(keyword) || StringUtils.hasText(apiAssertionExpression)) {
                ResponseBody responseBody = response.body();
                text = responseBody == null ? "" : responseBody.string();
            }
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
            return new CheckResult(statusOk && keywordOk && apiRuleOk ? "UP" : "DOWN", elapsed, message);
        } catch (Exception ex) {
            return new CheckResult("DOWN", elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
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
}
