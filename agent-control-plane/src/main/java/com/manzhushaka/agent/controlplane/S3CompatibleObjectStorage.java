package com.manzhushaka.agent.controlplane;

import java.io.IOException;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal S3-compatible object storage client using AWS Signature Version 4. Endpoint, bucket and
 * credentials are deployment configuration only; object keys are validated server-generated keys.
 */
public final class S3CompatibleObjectStorage implements ObjectStoragePort {
    private static final int MAX_OBJECT_BYTES = 10 * 1024 * 1024;
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);

    private final HttpClient client;
    private final URI endpoint;
    private final String bucket;
    private final String region;
    private final String accessKeyId;
    private final String secretAccessKey;
    private final Duration timeout;
    private final Clock clock;
    private final Set<String> allowedHosts;
    private final boolean insecureTestTransport;

    public S3CompatibleObjectStorage(
            URI endpoint,
            String bucket,
            String region,
            String accessKeyId,
            String secretAccessKey,
            Duration timeout
    ) {
        this(endpoint, bucket, region, accessKeyId, secretAccessKey, timeout, Set.of(configuredHost(endpoint)));
    }

    public S3CompatibleObjectStorage(
            URI endpoint,
            String bucket,
            String region,
            String accessKeyId,
            String secretAccessKey,
            Duration timeout,
            Set<String> allowedHosts
    ) {
        this(HttpClient.newBuilder().connectTimeout(normalizeTimeout(timeout)).build(), endpoint, bucket, region,
                accessKeyId, secretAccessKey, timeout, Clock.systemUTC(), false, allowedHosts);
    }

    S3CompatibleObjectStorage(
            HttpClient client,
            URI endpoint,
            String bucket,
            String region,
            String accessKeyId,
            String secretAccessKey,
            Duration timeout,
            Clock clock,
            boolean allowInsecureEndpointForTest
    ) {
        this(client, endpoint, bucket, region, accessKeyId, secretAccessKey, timeout, clock,
                allowInsecureEndpointForTest, Set.of(configuredHost(endpoint)));
    }

    S3CompatibleObjectStorage(
            HttpClient client,
            URI endpoint,
            String bucket,
            String region,
            String accessKeyId,
            String secretAccessKey,
            Duration timeout,
            Clock clock,
            boolean allowInsecureEndpointForTest,
            Set<String> allowedHosts
    ) {
        this.client = client;
        this.allowedHosts = normalizeHosts(allowedHosts);
        this.endpoint = validateEndpoint(endpoint, allowInsecureEndpointForTest, this.allowedHosts);
        this.bucket = requireBucket(bucket);
        this.region = requireText(region, "region");
        this.accessKeyId = requireText(accessKeyId, "access key");
        this.secretAccessKey = requireText(secretAccessKey, "secret key");
        this.timeout = normalizeTimeout(timeout);
        this.clock = clock;
        this.insecureTestTransport = allowInsecureEndpointForTest;
    }

    @Override
    public StoredObject put(String objectKey, String contentType, byte[] content) {
        validateObject(objectKey, content);
        String normalizedType = requireText(contentType, "content type");
        RawResponse response = send("PUT", objectKey, normalizedType, content);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw storageFailure(response.statusCode());
        }
        return new StoredObject(objectKey, normalizedType, content.length, sha256(content));
    }

    @Override
    public byte[] get(String objectKey) {
        validateObjectKey(objectKey);
        RawResponse response = send("GET", objectKey, null, new byte[0]);
        if (response.statusCode() == 404) {
            throw new IllegalStateException("OBJECT_NOT_FOUND");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw storageFailure(response.statusCode());
        }
        return response.body();
    }

    @Override
    public void delete(String objectKey) {
        validateObjectKey(objectKey);
        RawResponse response = send("DELETE", objectKey, null, new byte[0]);
        if ((response.statusCode() < 200 || response.statusCode() >= 300) && response.statusCode() != 404) {
            throw storageFailure(response.statusCode());
        }
    }

    private RawResponse send(String method, String objectKey, String contentType, byte[] body) {
        try {
            Instant now = clock.instant();
            String payloadHash = sha256(body);
            String canonicalPath = "/" + encodePathSegment(bucket) + "/" + encodeObjectKey(objectKey);
            String date = AMZ_DATE.format(now);
            String host = endpoint.getRawAuthority();
            StringBuilder headers = new StringBuilder();
            StringBuilder signedHeaders = new StringBuilder();
            if (contentType != null) {
                headers.append("content-type:").append(contentType.trim()).append('\n');
                signedHeaders.append("content-type;");
            }
            headers.append("host:").append(host).append('\n')
                    .append("x-amz-content-sha256:").append(payloadHash).append('\n')
                    .append("x-amz-date:").append(date).append('\n');
            signedHeaders.append("host;x-amz-content-sha256;x-amz-date");
            String scope = DATE_STAMP.format(now) + "/" + region + "/s3/aws4_request";
            String canonicalRequest = method + '\n' + canonicalPath + "\n\n" + headers + '\n' + signedHeaders + '\n' + payloadHash;
            String stringToSign = "AWS4-HMAC-SHA256\n" + date + '\n' + scope + '\n' + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8));
            String signature = signature(DATE_STAMP.format(now), stringToSign);
            String authorization = "AWS4-HMAC-SHA256 Credential=" + accessKeyId + "/" + scope
                    + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;
            Map<String, String> requestHeaders = new LinkedHashMap<>();
            requestHeaders.put("Host", host);
            requestHeaders.put("x-amz-content-sha256", payloadHash);
            requestHeaders.put("x-amz-date", date);
            requestHeaders.put("Authorization", authorization);
            if (contentType != null) requestHeaders.put("Content-Type", contentType.trim());
            if (insecureTestTransport) {
                HttpRequest.Builder request = HttpRequest.newBuilder(endpoint.resolve(canonicalPath)).timeout(timeout)
                        .method(method, method.equals("GET") || method.equals("DELETE") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
                requestHeaders.forEach((name, value) -> { if (!"Host".equalsIgnoreCase(name)) request.header(name, value); });
                HttpResponse<byte[]> response = client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
                if (response.body().length > MAX_OBJECT_BYTES) throw new IllegalStateException("OBJECT_SIZE_LIMIT_EXCEEDED");
                return new RawResponse(response.statusCode(), response.body());
            }
            return pinnedExchange(method, canonicalPath, requestHeaders, body);
        } catch (IOException exception) {
            throw new IllegalStateException("OBJECT_STORAGE_UNAVAILABLE", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OBJECT_STORAGE_UNAVAILABLE", exception);
        }
    }

    private String signature(String dateStamp, String stringToSign) {
        byte[] dateKey = hmac(("AWS4" + secretAccessKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] regionKey = hmac(dateKey, region);
        byte[] serviceKey = hmac(regionKey, "s3");
        return HexFormat.of().formatHex(hmac(hmac(serviceKey, "aws4_request"), stringToSign));
    }

    private static byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException | InvalidKeyException exception) {
            throw new IllegalStateException("OBJECT_STORAGE_SIGNING_FAILED", exception);
        }
    }

    private RawResponse pinnedExchange(String method, String path, Map<String, String> headers, byte[] body) throws IOException {
        List<InetAddress> addresses = resolvePublicAddresses(endpoint.getHost());
        IOException failure = null;
        for (InetAddress address : addresses) {
            try (Socket plain = new Socket()) {
                int timeoutMillis = Math.toIntExact(Math.min(Integer.MAX_VALUE, timeout.toMillis()));
                plain.connect(new InetSocketAddress(address, 443), timeoutMillis);
                plain.setSoTimeout(timeoutMillis);
                SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
                try (SSLSocket socket = (SSLSocket) factory.createSocket(plain, endpoint.getHost(), 443, true)) {
                    SSLParameters parameters = socket.getSSLParameters();
                    parameters.setEndpointIdentificationAlgorithm("HTTPS");
                    parameters.setServerNames(List.of(new SNIHostName(endpoint.getHost())));
                    socket.setSSLParameters(parameters);
                    socket.setSoTimeout(timeoutMillis);
                    socket.startHandshake();
                    writeRequest(socket.getOutputStream(), method, path, headers, body);
                    return readResponse(socket.getInputStream());
                }
            } catch (IOException exception) {
                failure = exception;
            }
        }
        throw failure == null ? new IOException("对象存储 endpoint 不可用。") : failure;
    }

    private static URI validateEndpoint(URI value, boolean allowInsecureEndpointForTest, Set<String> allowedHosts) {
        if (value == null || value.getHost() == null || value.getUserInfo() != null || value.getQuery() != null
                || value.getFragment() != null || (value.getRawPath() != null && !value.getRawPath().isBlank() && !"/".equals(value.getRawPath()))
                || (!allowInsecureEndpointForTest && value.getPort() > 0) || (!"https".equalsIgnoreCase(value.getScheme()) && !(allowInsecureEndpointForTest && "http".equalsIgnoreCase(value.getScheme())))
                || (!allowInsecureEndpointForTest && !hostAllowed(value.getHost(), allowedHosts))) {
            throw new IllegalArgumentException("无效的对象存储 endpoint。");
        }
        return URI.create(value.getScheme().toLowerCase(Locale.ROOT) + "://" + value.getRawAuthority());
    }

    private static Set<String> normalizeHosts(Set<String> hosts) {
        Set<String> values = hosts == null ? Set.of() : hosts.stream().filter(value -> value != null && !value.isBlank())
                .map(value -> value.trim().toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        if (values.isEmpty()) throw new IllegalArgumentException("对象存储 endpoint 白名单不能为空。");
        return values;
    }

    private static String configuredHost(URI endpoint) {
        return endpoint == null || endpoint.getHost() == null ? "" : endpoint.getHost();
    }

    private static boolean hostAllowed(String host, Set<String> allowedHosts) {
        if (host == null || host.isBlank() || host.matches("[0-9a-fA-F:.]+")) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        return allowedHosts.stream().anyMatch(candidate -> normalized.equals(candidate) || normalized.endsWith("." + candidate));
    }

    private static String requireBucket(String value) {
        if (value == null || !value.matches("[a-z0-9](?:[a-z0-9-]{1,61}[a-z0-9])")) {
            throw new IllegalArgumentException("无效的对象存储 bucket。");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException("无效的对象存储 " + name + "。");
        }
        return value.trim();
    }

    private static Duration normalizeTimeout(Duration value) {
        if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalArgumentException("无效的对象存储超时。");
        }
        return value;
    }

    private static void validateObject(String objectKey, byte[] content) {
        validateObjectKey(objectKey);
        if (content == null || content.length > MAX_OBJECT_BYTES) {
            throw new IllegalArgumentException("对象超过大小限制。");
        }
    }

    private static void validateObjectKey(String objectKey) {
        if (objectKey == null || !objectKey.matches("[A-Za-z0-9][A-Za-z0-9._/-]{0,499}")
                || objectKey.contains("//") || objectKey.contains("..")) {
            throw new IllegalArgumentException("无效的对象键。");
        }
    }

    private static String encodeObjectKey(String objectKey) {
        return String.join("/", java.util.Arrays.stream(objectKey.split("/", -1)).map(S3CompatibleObjectStorage::encodePathSegment).toList());
    }

    private static String encodePathSegment(String value) {
        StringBuilder result = new StringBuilder();
        for (byte item : value.getBytes(StandardCharsets.UTF_8)) {
            int unsigned = Byte.toUnsignedInt(item);
            if ((unsigned >= 'a' && unsigned <= 'z') || (unsigned >= 'A' && unsigned <= 'Z')
                    || (unsigned >= '0' && unsigned <= '9') || unsigned == '-' || unsigned == '_' || unsigned == '.' || unsigned == '~') {
                result.append((char) unsigned);
            } else {
                result.append('%').append(String.format(Locale.ROOT, "%02X", unsigned));
            }
        }
        return result.toString();
    }

    private static List<InetAddress> resolvePublicAddresses(String host) throws IOException {
        InetAddress[] resolved = InetAddress.getAllByName(host);
        if (resolved.length == 0 || java.util.Arrays.stream(resolved).anyMatch(address -> !isPublicAddress(address))) {
            throw new IOException("对象存储 endpoint 必须解析到公网地址。");
        }
        return List.of(resolved);
    }

    private static boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            return first != 0 && first < 224 && !(first == 100 && second >= 64 && second <= 127)
                    && !(first == 192 && second == 0) && !(first == 198 && (second == 18 || second == 19));
        }
        if (address instanceof Inet6Address) {
            int first = Byte.toUnsignedInt(bytes[0]);
            int second = Byte.toUnsignedInt(bytes[1]);
            boolean uniqueLocal = (first & 0xfe) == 0xfc;
            boolean documentation = first == 0x20 && second == 0x01 && Byte.toUnsignedInt(bytes[2]) == 0x0d && Byte.toUnsignedInt(bytes[3]) == 0xb8;
            return !uniqueLocal && !documentation;
        }
        return false;
    }

    private static void writeRequest(OutputStream output, String method, String path, Map<String, String> headers, byte[] body) throws IOException {
        StringBuilder request = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\n");
        headers.forEach((name, value) -> request.append(name).append(": ").append(value).append("\r\n"));
        request.append("Accept-Encoding: identity\r\nConnection: close\r\n");
        if (!method.equals("GET") && !method.equals("DELETE")) request.append("Content-Length: ").append(body.length).append("\r\n");
        request.append("\r\n");
        output.write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (body.length > 0) output.write(body);
        output.flush();
    }

    private static RawResponse readResponse(InputStream source) throws IOException {
        BufferedInputStream input = new BufferedInputStream(source);
        String statusLine = readLine(input, 32_768);
        String[] status = statusLine.split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/")) throw new IOException("对象存储响应无效。");
        int statusCode = Integer.parseInt(status[1]);
        Map<String, String> headers = new LinkedHashMap<>();
        while (true) {
            String line = readLine(input, 32_768);
            if (line.isEmpty()) break;
            int separator = line.indexOf(':');
            if (separator <= 0) throw new IOException("对象存储响应头无效。");
            headers.put(line.substring(0, separator).trim().toLowerCase(Locale.ROOT), line.substring(separator + 1).trim());
        }
        byte[] body;
        if (headers.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked")) {
            body = readChunked(input);
        } else if (headers.containsKey("content-length")) {
            long size = Long.parseLong(headers.get("content-length"));
            if (size < 0 || size > MAX_OBJECT_BYTES) throw new IOException("对象超过大小限制。");
            body = readExact(input, Math.toIntExact(size));
        } else {
            body = readUntilEof(input);
        }
        return new RawResponse(statusCode, body);
    }

    private static byte[] readChunked(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        while (true) {
            String line = readLine(input, 128);
            int extension = line.indexOf(';');
            int size = Integer.parseInt((extension < 0 ? line : line.substring(0, extension)).trim(), 16);
            if (size == 0) { while (!readLine(input, 32_768).isEmpty()) { } return output.toByteArray(); }
            if (size < 0 || output.size() + (long) size > MAX_OBJECT_BYTES) throw new IOException("对象超过大小限制。");
            output.write(readExact(input, size));
            if (input.read() != '\r' || input.read() != '\n') throw new IOException("对象分块响应无效。");
        }
    }

    private static byte[] readExact(InputStream input, int length) throws IOException {
        byte[] body = input.readNBytes(length);
        if (body.length != length) throw new IOException("对象响应提前结束。");
        return body;
    }

    private static byte[] readUntilEof(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + (long) read > MAX_OBJECT_BYTES) throw new IOException("对象超过大小限制。");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static String readLine(BufferedInputStream input, int limit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int previous = -1;
        while (output.size() < limit) {
            int current = input.read();
            if (current == -1) throw new IOException("对象响应提前结束。");
            if (previous == '\r' && current == '\n') {
                byte[] bytes = output.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.ISO_8859_1);
            }
            output.write(current);
            previous = current;
        }
        throw new IOException("对象响应头过大。");
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static IllegalStateException storageFailure(int status) {
        return new IllegalStateException(status >= 400 && status < 500 ? "OBJECT_STORAGE_REJECTED" : "OBJECT_STORAGE_UNAVAILABLE");
    }

    private record RawResponse(int statusCode, byte[] body) {
        private RawResponse {
            body = body.clone();
        }
    }
}
