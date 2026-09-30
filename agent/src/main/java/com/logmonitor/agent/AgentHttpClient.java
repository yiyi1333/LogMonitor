package com.logmonitor.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.logmonitor.agent.AgentModels.ApiError;
import com.logmonitor.agent.AgentModels.BatchAck;
import com.logmonitor.agent.AgentModels.BatchMetadata;
import com.logmonitor.agent.AgentModels.EnrollRequest;
import com.logmonitor.agent.AgentModels.EnrollResponse;
import com.logmonitor.agent.AgentModels.Heartbeat;
import com.logmonitor.agent.AgentModels.LocalConfig;
import com.logmonitor.agent.AgentModels.RemoteConfig;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

final class AgentHttpClient {
    private final ObjectMapper json = AgentFiles.JSON;
    private final String serverUrl;
    private final String token;

    AgentHttpClient(String serverUrl, String token) {
        this(serverUrl, token, false);
    }

    AgentHttpClient(String serverUrl, String token, boolean allowHttp) {
        validateServerUrl(serverUrl, allowHttp);
        this.serverUrl = trimSlash(serverUrl);
        this.token = token;
    }

    static void validateServerUrl(String value) {
        validateServerUrl(value, false);
    }

    static void validateServerUrl(String value, boolean allowHttp) {
        URI uri = URI.create(value);
        String host = uri.getHost();
        boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
        boolean http = "http".equalsIgnoreCase(uri.getScheme());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !(http && (local || allowHttp))) {
            throw new IllegalArgumentException("中心地址必须使用 HTTPS（远程 HTTP 需在 configure 时显式指定 --allow-http）");
        }
    }

    EnrollResponse enroll(EnrollRequest request) throws IOException {
        HttpURLConnection connection = open("/api/agent/v1/enroll", "POST", false);
        connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        write(connection, json.writeValueAsBytes(request));
        return response(connection, EnrollResponse.class);
    }

    RemoteConfig configuration(long revision) throws IOException {
        HttpURLConnection connection = open("/api/agent/v1/config", "GET", true);
        if (revision > 0) connection.setRequestProperty("If-None-Match", "\"" + revision + "\"");
        int status = connection.getResponseCode();
        if (status == HttpURLConnection.HTTP_NOT_MODIFIED) return null;
        return response(connection, RemoteConfig.class, status);
    }

    void heartbeat(Heartbeat heartbeat) throws IOException {
        HttpURLConnection connection = open("/api/agent/v1/heartbeat", "POST", true);
        connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        write(connection, json.writeValueAsBytes(heartbeat));
        response(connection, Void.class);
    }

    BatchAck upload(BatchMetadata metadata, Path payload) throws IOException, UploadException {
        String boundary = "----LogMonitor" + UUID.randomUUID().toString().replace("-", "");
        HttpURLConnection connection = open("/api/agent/v1/batches", "POST", true);
        connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
        connection.setChunkedStreamingMode(64 * 1024);
        connection.setDoOutput(true);
        OutputStream output = connection.getOutputStream();
        byte[] newline = "\r\n".getBytes(StandardCharsets.US_ASCII);
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\n" +
                "Content-Type: application/json\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        output.write(json.writeValueAsBytes(metadata));
        output.write(newline);
        output.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload\"; filename=\"batch.gz\"\r\n" +
                "Content-Type: application/gzip\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        Files.copy(payload, output);
        output.write(newline);
        output.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        output.close();
        int status = connection.getResponseCode();
        if (status >= 200 && status < 300) return response(connection, BatchAck.class, status);
        ApiError error = readError(connection);
        throw new UploadException(status, error, retryAfterMillis(connection.getHeaderField("Retry-After")));
    }

    private HttpURLConnection open(String path, String method, boolean authenticated) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(serverUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setUseCaches(false);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("User-Agent", "LogMonitor-Agent/" + AgentMain.VERSION);
        if (authenticated) connection.setRequestProperty("Authorization", "Bearer " + token);
        return connection;
    }

    private void write(HttpURLConnection connection, byte[] body) throws IOException {
        connection.setDoOutput(true);
        connection.setFixedLengthStreamingMode(body.length);
        OutputStream output = connection.getOutputStream();
        output.write(body);
        output.close();
    }

    private <T> T response(HttpURLConnection connection, Class<T> type) throws IOException {
        return response(connection, type, connection.getResponseCode());
    }

    private <T> T response(HttpURLConnection connection, Class<T> type, int status) throws IOException {
        if (status < 200 || status >= 300) {
            ApiError error = readError(connection);
            throw new IOException(error.message == null ? "HTTP " + status : error.message);
        }
        if (type == Void.class || status == HttpURLConnection.HTTP_NO_CONTENT) return null;
        InputStream input = connection.getInputStream();
        try { return json.readValue(input, type); } finally { input.close(); }
    }

    private ApiError readError(HttpURLConnection connection) throws IOException {
        InputStream input = connection.getErrorStream();
        if (input == null) {
            ApiError error = new ApiError();
            error.message = "HTTP " + connection.getResponseCode();
            return error;
        }
        try { return json.readValue(input, ApiError.class); }
        finally { input.close(); }
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static long retryAfterMillis(String value) {
        if (value == null) return 0;
        try { return Math.max(0, Math.min(3600000L, Long.parseLong(value) * 1000)); }
        catch (NumberFormatException ignored) {
            try { return Math.max(0, Math.min(3600000L, java.time.ZonedDateTime.parse(value,
                    java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()-System.currentTimeMillis())); }
            catch (RuntimeException invalid) { return 0; }
        }
    }

    static final class UploadException extends Exception {
        final int status;
        final ApiError error;
        final long retryAfterMillis;
        UploadException(int status, ApiError error) { this(status, error, 0); }
        UploadException(int status, ApiError error, long retryAfterMillis) {
            super(error.message == null ? "HTTP " + status : error.message);
            this.status = status;
            this.error = error;
            this.retryAfterMillis = retryAfterMillis;
        }
    }
}
