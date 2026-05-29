package com.live.monitor.service;

import com.live.monitor.dto.CheckResult;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class WebMonitorService {
    public CheckResult check(String url, String method, Integer expectedStatusCode, String keyword, double timeoutSeconds) {
        if (!StringUtils.hasText(url)) {
            return new CheckResult("UNKNOWN", null, "URL is required for web service checks");
        }
        long started = System.nanoTime();
        OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS)
            .readTimeout((long) (timeoutSeconds * 1000), TimeUnit.MILLISECONDS)
            .build();
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
            if (StringUtils.hasText(keyword)) {
                ResponseBody responseBody = response.body();
                String text = responseBody == null ? "" : responseBody.string();
                keywordOk = text.contains(keyword);
            }
            String message = "HTTP " + code + (StringUtils.hasText(keyword) ? ", keyword " + (keywordOk ? "matched" : "missing") : "");
            return new CheckResult(statusOk && keywordOk ? "UP" : "DOWN", elapsed, message);
        } catch (Exception ex) {
            return new CheckResult("DOWN", elapsedMs(started), ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
    }

    private int elapsedMs(long started) {
        return (int) TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }
}
