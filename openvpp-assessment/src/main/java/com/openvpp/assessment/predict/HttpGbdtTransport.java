package com.openvpp.assessment.predict;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * GBDT 推理副车的 HTTP 传输实现 —— 专栏第 47 篇「裸 HttpURLConnection 不丢人」。
 *
 * 需求只有三个：GET /health、POST 预测、失败抛异常（由 GbdtClient 统一转 null）。
 * 连接 3 秒快速失败、读 8 秒容忍大批量（单批最多 288 个 15 分钟点）。
 */
public class HttpGbdtTransport implements GbdtTransport {

    private final String baseUrl;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;

    public HttpGbdtTransport(String baseUrl, int timeoutMs) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1)
                : baseUrl;
        this.connectTimeoutMs = Math.min(3000, timeoutMs);
        this.readTimeoutMs = timeoutMs;
    }

    @Override
    public String get(String path) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("health http " + code);
            }
            return readBody(conn);
        } finally {
            conn.disconnect();
        }
    }

    @Override
    public String post(String path, String jsonBody) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        conn.setConnectTimeout(connectTimeoutMs);
        conn.setReadTimeout(readTimeoutMs);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
        }
        try {
            int code = conn.getResponseCode();
            if (code != 200) {
                throw new IOException("predict http " + code);
            }
            return readBody(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        byte[] buf = new byte[8192];
        int len;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.io.InputStream in = conn.getInputStream()) {
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
