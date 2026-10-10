package io.cafeai.identity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * AWS Signature Version 4: the signature AWS checks on every request it serves, worked out from
 * the request itself and the caller's secret key, so the key is never sent.
 *
 * <ol>
 *   <li>The <b>canonical request</b>: method, the URI-encoded path, the query sorted by name, the
 *       signed headers (names lowercased, values trimmed, sorted), their names, and the SHA-256
 *       of the body.</li>
 *   <li>The <b>string to sign</b>: the algorithm, the time, the scope (day, region, service,
 *       {@code aws4_request}) and the SHA-256 of the canonical request.</li>
 *   <li>The <b>signing key</b>: HMAC-SHA256 of the day, then region, service and
 *       {@code aws4_request}, starting from {@code "AWS4" + secret}; the signature is the
 *       HMAC-SHA256 of the string to sign with it.</li>
 * </ol>
 * Checked against AWS's own published test suite.
 */
final class AwsSigV4 {

    static final String ALGORITHM = "AWS4-HMAC-SHA256";
    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    /** A key pair, and the session token of temporary credentials ({@code null} for long-term ones). */
    record Key(String accessKeyId, String secretAccessKey, String sessionToken) {
        @Override public String toString() { return "Key[" + accessKeyId + "]"; }
    }

    /** What signing produced: the headers to add, and the steps, for tests. */
    record Signed(Map<String, String> headers, String canonicalRequest, String stringToSign, String signature) { }

    private AwsSigV4() {}

    /**
     * Signs a request.
     *
     * @param headers        the headers to sign, including {@code host}; {@code x-amz-date}, and
     *                       the session token when {@code signSessionToken}, are added here
     * @param signSessionToken whether the session token header is signed (it normally is)
     * @param contentSha256  whether to send, and sign, {@code x-amz-content-sha256}
     */
    static Signed sign(String method, String rawPath, String rawQuery, Map<String, String> headers, byte[] body,
                       Key key, String region, String service, Instant now,
                       boolean signSessionToken, boolean contentSha256) {
        String amzDate = AMZ_DATE.format(now);
        String day = amzDate.substring(0, 8);
        String payloadHash = hex(sha256(body));

        Map<String, String> added = new LinkedHashMap<>();
        added.put("X-Amz-Date", amzDate);
        if (contentSha256) added.put("x-amz-content-sha256", payloadHash);
        if (key.sessionToken() != null && signSessionToken) added.put("X-Amz-Security-Token", key.sessionToken());

        TreeMap<String, String> signedHeaders = new TreeMap<>();
        headers.forEach((name, value) -> signedHeaders.put(name.toLowerCase(), canonicalValue(value)));
        added.forEach((name, value) -> signedHeaders.put(name.toLowerCase(), canonicalValue(value)));
        String headerNames = String.join(";", signedHeaders.keySet());

        String canonicalRequest = method + "\n"
                + canonicalPath(rawPath) + "\n"
                + canonicalQuery(rawQuery) + "\n"
                + signedHeaders.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue() + "\n").collect(Collectors.joining())
                + "\n"
                + headerNames + "\n"
                + payloadHash;
        String scope = day + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = ALGORITHM + "\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

        byte[] signingKey = hmac(("AWS4" + key.secretAccessKey()).getBytes(StandardCharsets.UTF_8), day);
        signingKey = hmac(signingKey, region);
        signingKey = hmac(signingKey, service);
        signingKey = hmac(signingKey, "aws4_request");
        String signature = hex(hmac(signingKey, stringToSign));

        Map<String, String> out = new LinkedHashMap<>(added);
        if (key.sessionToken() != null && !signSessionToken) out.put("X-Amz-Security-Token", key.sessionToken());
        out.put("Authorization", ALGORITHM + " Credential=" + key.accessKeyId() + "/" + scope
                + ", SignedHeaders=" + headerNames + ", Signature=" + signature);
        return new Signed(out, canonicalRequest, stringToSign, signature);
    }

    /** The path, each segment URI-encoded once (as the services other than S3 take it, already normalised). */
    private static String canonicalPath(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) return "/";
        List<String> segments = new ArrayList<>();
        for (String segment : rawPath.split("/", -1)) {
            segments.add(encode(URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8)));
        }
        return String.join("/", segments);
    }

    /** Name=value pairs, each part URI-encoded, sorted by name then value. */
    private static String canonicalQuery(String rawQuery) {
        if (rawQuery == null || rawQuery.isEmpty()) return "";
        List<String[]> pairs = new ArrayList<>();
        for (String pair : rawQuery.split("&")) {
            if (pair.isEmpty()) continue;
            int eq = pair.indexOf('=');
            String name = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            pairs.add(new String[]{
                    encode(URLDecoder.decode(name.replace("+", "%2B"), StandardCharsets.UTF_8)),
                    encode(URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8))});
        }
        pairs.sort((a, b) -> a[0].equals(b[0]) ? a[1].compareTo(b[1]) : a[0].compareTo(b[0]));
        return pairs.stream().map(p -> p[0] + "=" + p[1]).collect(Collectors.joining("&"));
    }

    /** RFC 3986 encoding as SigV4 wants it: every byte but the unreserved characters, upper-case hex. */
    static String encode(String s) {
        StringBuilder out = new StringBuilder();
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xff);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '-' || c == '_' || c == '.' || c == '~') {
                out.append(c);
            } else {
                out.append('%').append(String.format("%02X", b & 0xff));
            }
        }
        return out.toString();
    }

    /** A header value as it is signed: trimmed, inner runs of spaces collapsed. */
    private static String canonicalValue(String value) {
        return value.trim().replaceAll("\\s+", " ");
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
