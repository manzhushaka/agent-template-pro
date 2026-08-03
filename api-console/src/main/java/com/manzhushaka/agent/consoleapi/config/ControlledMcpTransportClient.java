package com.manzhushaka.agent.consoleapi.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.manzhushaka.agent.controlplane.McpDiscoveredTool;
import com.manzhushaka.agent.controlplane.McpServerConnection;
import com.manzhushaka.agent.controlplane.McpTransportCallResult;
import com.manzhushaka.agent.controlplane.McpTransportClient;
import com.manzhushaka.agent.controlplane.McpTransportResult;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * HTTPS-only MCP client. DNS results are checked and then used as the socket destination, while
 * TLS SNI and hostname verification retain the configured host. This closes DNS rebinding between
 * policy validation and connection establishment.
 */
final class ControlledMcpTransportClient implements McpTransportClient {
    private static final int MAX_RESPONSE_BYTES = 1_000_000;
    private static final int MAX_HEADER_BYTES = 32_768;
    private static final String DISCOVERY_BODY = "{\"jsonrpc\":\"2.0\",\"id\":\"catalog\",\"method\":\"tools/list\",\"params\":{}}";
    private static final ScheduledExecutorService TIMEOUT_EXECUTOR = Executors.newSingleThreadScheduledExecutor(task ->
            Thread.ofPlatform().daemon(true).name("mcp-transport-timeout").unstarted(task)
    );

    private final ObjectMapper objectMapper;

    ControlledMcpTransportClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public McpTransportResult test(McpServerConnection server, Duration timeout) {
        if ("STDIO".equals(server.transport())) {
            return new McpTransportResult("PROBE_UNAVAILABLE", "STDIO 仅可由部署受控的 MCP Adapter 执行。");
        }
        try {
            HttpResponse response = exchange(server, timeout);
            return response.statusCode() >= 200 && response.statusCode() < 300
                    ? new McpTransportResult("CONNECTED", "受控 MCP transport 已连接。")
                    : new McpTransportResult("CONNECTION_FAILED", "MCP transport 返回 HTTP " + response.statusCode() + "。");
        } catch (Exception exception) {
            return new McpTransportResult("CONNECTION_FAILED", "MCP transport 连接失败。");
        }
    }

    @Override
    public List<McpDiscoveredTool> discover(McpServerConnection server, Duration timeout) {
        if ("STDIO".equals(server.transport())) {
            throw new IllegalStateException("当前部署未配置可执行受控 STDIO 的 MCP Adapter。");
        }
        try {
            HttpResponse response = exchange(server, timeout);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("MCP Tool discovery 失败。");
            }
            String body = new String(response.body(), StandardCharsets.UTF_8);
            return parseTools("SSE".equals(server.transport()) ? sseData(body) : body);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("MCP Tool discovery 失败。", exception);
        }
    }

    @Override
    public McpTransportCallResult call(
            McpServerConnection server,
            String toolName,
            Map<String, Object> arguments,
            Duration timeout
    ) {
        if ("STDIO".equals(server.transport()) || "SSE".equals(server.transport())) {
            return new McpTransportCallResult(
                    McpTransportCallResult.UNAVAILABLE,
                    Map.of(),
                    "TRANSPORT_CALL_UNAVAILABLE"
            );
        }
        try {
            String callBody = objectMapper.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", "call-" + java.util.UUID.randomUUID(),
                    "method", "tools/call",
                    "params", Map.of("name", toolName, "arguments", arguments == null ? Map.of() : arguments)
            ));
            HttpResponse response = exchange(server, timeout, callBody);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new McpTransportCallResult(
                        McpTransportCallResult.FAILED, Map.of(), "MCP_CALL_HTTP_" + response.statusCode()
                );
            }
            String body = new String(response.body(), StandardCharsets.UTF_8);
            JsonNode root = objectMapper.readTree(body);
            JsonNode error = root.get("error");
            if (error != null && !error.isNull()) {
                String code = error.has("code") ? String.valueOf(error.get("code").asInt()) : "MCP_TOOL_ERROR";
                return new McpTransportCallResult(McpTransportCallResult.FAILED, Map.of(), "MCP_TOOL_ERROR_" + code);
            }
            JsonNode content = root.get("result");
            Map<String, Object> output = new LinkedHashMap<>();
            if (content != null && content.isObject()) {
                output.put("isError", content.path("isError").asBoolean(false));
                JsonNode structured = content.get("structuredContent");
                if (structured != null && structured.isObject()) {
                    output.put("structuredContent", sanitize(structured));
                }
                JsonNode textParts = content.get("content");
                if (textParts != null && textParts.isArray()) {
                    List<String> texts = new ArrayList<>();
                    for (JsonNode part : textParts) {
                        if (part.isObject() && "text".equals(part.path("type").asText())) {
                            String text = part.path("text").asText();
                            if (text.length() <= 4000) {
                                texts.add(text);
                            }
                        }
                    }
                    output.put("contentText", String.join("\n", texts).substring(0,
                            Math.min(String.join("\n", texts).length(), 4000)));
                }
            }
            return new McpTransportCallResult(McpTransportCallResult.OK, Map.copyOf(output), null);
        } catch (Exception exception) {
            return new McpTransportCallResult(
                    McpTransportCallResult.RESULT_UNKNOWN,
                    Map.of(),
                    "MCP_CALL_TRANSPORT_FAILED"
            );
        }
    }

    private HttpResponse exchange(McpServerConnection server, Duration timeout) throws Exception {
        return exchange(server, timeout, DISCOVERY_BODY);
    }

    private HttpResponse exchange(McpServerConnection server, Duration timeout, String bodyText) throws Exception {
        URI endpoint = URI.create(server.endpoint());
        String host = endpoint.getHost();
        if (host == null || !"https".equalsIgnoreCase(endpoint.getScheme()) || endpoint.getPort() != -1
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("MCP endpoint 必须是受控的标准 HTTPS 地址。");
        }
        List<InetAddress> addresses = resolvePublicAddresses(host);
        long timeoutMillis = Math.min(Integer.MAX_VALUE, Math.max(250L, timeout.toMillis()));
        long deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        Exception lastFailure = null;
        for (InetAddress address : addresses) {
            try {
                return exchange(endpoint, address, server, remainingTimeoutMillis(deadlineNanos), bodyText);
            } catch (Exception exception) {
                lastFailure = exception;
            }
        }
        throw lastFailure == null ? new IOException("MCP endpoint 没有可用地址。") : lastFailure;
    }

    private HttpResponse exchange(
            URI endpoint,
            InetAddress address,
            McpServerConnection server,
            int timeoutMillis,
            String bodyText
    ) throws Exception {
        try (Socket plainSocket = new Socket()) {
            ScheduledFuture<?> timeoutTask = TIMEOUT_EXECUTOR.schedule(
                    () -> closeQuietly(plainSocket), timeoutMillis, TimeUnit.MILLISECONDS
            );
            try {
                plainSocket.connect(new InetSocketAddress(address, 443), timeoutMillis);
                plainSocket.setSoTimeout(timeoutMillis);
                SSLSocketFactory socketFactory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket socket = (SSLSocket) socketFactory.createSocket(plainSocket, endpoint.getHost(), 443, true)) {
                    socket.setSoTimeout(timeoutMillis);
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setServerNames(List.of(new SNIHostName(endpoint.getHost())));
                    socket.setSSLParameters(parameters);
                    socket.startHandshake();
                    writeRequest(socket.getOutputStream(), endpoint, server, bodyText);
                    return readResponse(socket.getInputStream());
                }
            } finally {
                timeoutTask.cancel(false);
            }
        }
    }

    private static int remainingTimeoutMillis(long deadlineNanos) throws SocketTimeoutException {
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw new SocketTimeoutException("MCP transport 请求超时。");
        }
        long remainingMillis = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
        return Math.toIntExact(Math.min(Integer.MAX_VALUE, remainingMillis));
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // The request path reports the original timeout or transport failure.
        }
    }

    private void writeRequest(OutputStream output, URI endpoint, McpServerConnection server) throws IOException {
        boolean sse = "SSE".equals(server.transport());
        byte[] body = sse ? new byte[0] : DISCOVERY_BODY.getBytes(StandardCharsets.UTF_8);
        String path = endpoint.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        StringBuilder request = new StringBuilder();
        request.append(sse ? "GET " : "POST ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(endpoint.getHost()).append("\r\n")
                .append("Accept: ").append(sse ? "text/event-stream" : "application/json").append("\r\n")
                .append("Accept-Encoding: identity\r\n")
                .append("Connection: close\r\n");
        if (!server.credential().isBlank()) {
            if (server.credential().chars().anyMatch(character -> character <= 31 || character == 127)) {
                throw new IOException("MCP credential 格式无效。");
            }
            request.append("Authorization: Bearer ").append(server.credential()).append("\r\n");
        }
        if (!sse) {
            request.append("Content-Type: application/json\r\n")
                    .append("Content-Length: ").append(body.length).append("\r\n");
        }
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private void writeRequest(
            OutputStream output,
            URI endpoint,
            McpServerConnection server,
            String bodyText
    ) throws IOException {
        if ("SSE".equals(server.transport())) {
            writeRequest(output, endpoint, server);
            return;
        }
        byte[] body = bodyText.getBytes(StandardCharsets.UTF_8);
        String path = endpoint.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        StringBuilder request = new StringBuilder();
        request.append("POST ").append(path).append(" HTTP/1.1\r\n")
                .append("Host: ").append(endpoint.getHost()).append("\r\n")
                .append("Accept: application/json\r\n")
                .append("Accept-Encoding: identity\r\n")
                .append("Connection: close\r\n");
        if (!server.credential().isBlank()) {
            if (server.credential().chars().anyMatch(character -> character <= 31 || character == 127)) {
                throw new IOException("MCP credential 格式无效。");
            }
            request.append("Authorization: Bearer ").append(server.credential()).append("\r\n");
        }
        request.append("Content-Type: application/json\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n\r\n");
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        output.write(body);
        output.flush();
    }

    private Map<String, Object> sanitize(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        node.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            if (value.isValueNode()) {
                result.put(entry.getKey(), value.asText());
            } else if (value.isArray() && value.size() <= 50) {
                List<String> items = new ArrayList<>();
                value.forEach(item -> {
                    if (item.isValueNode()) {
                        items.add(item.asText());
                    }
                });
                result.put(entry.getKey(), items);
            }
        });
        return result;
    }

    private HttpResponse readResponse(InputStream source) throws IOException {
        BufferedInputStream input = new BufferedInputStream(source);
        String statusLine = readLine(input, MAX_HEADER_BYTES);
        String[] statusParts = statusLine.split(" ", 3);
        if (statusParts.length < 2 || !statusParts[0].startsWith("HTTP/")) {
            throw new IOException("MCP transport 响应状态行无效。");
        }
        int statusCode = Integer.parseInt(statusParts[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        int headerBytes = statusLine.length() + 2;
        while (true) {
            String line = readLine(input, MAX_HEADER_BYTES - headerBytes);
            headerBytes += line.length() + 2;
            if (line.isEmpty()) {
                break;
            }
            int separator = line.indexOf(':');
            if (separator <= 0) {
                throw new IOException("MCP transport 响应头无效。");
            }
            headers.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT), line.substring(separator + 1).trim());
        }
        String transferEncoding = headers.getOrDefault("transfer-encoding", "");
        byte[] body;
        if (transferEncoding.toLowerCase(Locale.ROOT).contains("chunked")) {
            body = readChunked(input);
        } else if (headers.containsKey("content-length")) {
            long contentLength = Long.parseLong(headers.get("content-length"));
            if (contentLength < 0 || contentLength > MAX_RESPONSE_BYTES) {
                throw new IOException("MCP transport 响应过大。");
            }
            body = readExact(input, Math.toIntExact(contentLength));
        } else {
            body = readUntilEof(input, MAX_RESPONSE_BYTES);
        }
        return new HttpResponse(statusCode, body);
    }

    private byte[] readChunked(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(input, 128);
            int extension = sizeLine.indexOf(';');
            String sizeText = extension < 0 ? sizeLine : sizeLine.substring(0, extension);
            int size = Integer.parseInt(sizeText.trim(), 16);
            if (size == 0) {
                while (!readLine(input, MAX_HEADER_BYTES).isEmpty()) {
                    // Consume trailers without exposing them.
                }
                break;
            }
            if (size < 0 || output.size() + (long) size > MAX_RESPONSE_BYTES) {
                throw new IOException("MCP transport 响应过大。");
            }
            output.write(readExact(input, size));
            if (input.read() != '\r' || input.read() != '\n') {
                throw new IOException("MCP transport chunk 格式无效。");
            }
        }
        return output.toByteArray();
    }

    private byte[] readExact(InputStream input, int length) throws IOException {
        byte[] bytes = input.readNBytes(length);
        if (bytes.length != length) {
            throw new IOException("MCP transport 响应提前结束。");
        }
        return bytes;
    }

    private byte[] readUntilEof(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + (long) read > limit) {
                throw new IOException("MCP transport 响应过大。");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private String readLine(BufferedInputStream input, int limit) throws IOException {
        if (limit <= 0) {
            throw new IOException("MCP transport 响应头过大。");
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        while (output.size() < limit) {
            int current = input.read();
            if (current == -1) {
                throw new IOException("MCP transport 响应提前结束。");
            }
            if (previous == '\r' && current == '\n') {
                byte[] bytes = output.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            output.write(current);
            previous = current;
        }
        throw new IOException("MCP transport 响应头过大。");
    }

    static List<InetAddress> resolvePublicAddresses(String host) throws IOException {
        InetAddress[] resolved = InetAddress.getAllByName(host);
        if (resolved.length == 0) {
            throw new IOException("MCP endpoint DNS 解析为空。");
        }
        List<InetAddress> addresses = List.of(resolved);
        if (addresses.stream().anyMatch(address -> !isPublicAddress(address))) {
            throw new IOException("MCP endpoint DNS 指向非公网地址。");
        }
        return addresses;
    }

    static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first != 0
                    && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0)
                    && !(first == 198 && (second == 18 || second == 19))
                    && first < 224;
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20 && second == 0x01
                    && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            return !uniqueLocal && !documentation;
        }
        return false;
    }

    private List<McpDiscoveredTool> parseTools(String body) throws Exception {
        JsonNode tools = objectMapper.readTree(body).path("result").path("tools");
        if (!tools.isArray()) {
            throw new IllegalStateException("MCP Tool discovery 响应格式无效。");
        }
        List<McpDiscoveredTool> discovered = new ArrayList<>();
        for (JsonNode tool : tools) {
            Map<String, Object> input = objectMapper.convertValue(tool.path("inputSchema"), Map.class);
            Map<String, Object> output = objectMapper.convertValue(tool.path("outputSchema"), Map.class);
            boolean write = tool.path("annotations").path("writeTool").asBoolean(false);
            String risk = tool.path("annotations").path("riskLevel").asText(write ? "HIGH" : "LOW");
            discovered.add(new McpDiscoveredTool(tool.path("name").asText(), tool.path("description").asText(), input, output, risk, write));
        }
        return List.copyOf(discovered);
    }

    private String sseData(String body) {
        StringBuilder json = new StringBuilder();
        for (String line : body.split("\\R")) {
            if (line.startsWith("data:")) {
                json.append(line.substring(5).trim());
            }
        }
        return json.toString();
    }

    private record HttpResponse(int statusCode, byte[] body) {
        private HttpResponse {
            body = body.clone();
        }
    }
}
