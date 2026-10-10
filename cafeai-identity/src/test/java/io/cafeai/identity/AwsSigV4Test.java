package io.cafeai.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The signer against AWS's own Signature Version 4 test suite (src/test/resources/aws-sigv4):
 * for each request, the canonical request, the string to sign and the signature must be exactly
 * what AWS published.
 */
@DisplayName("AWS Signature Version 4, against AWS's published test suite")
class AwsSigV4Test {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String resource(String vector, String file) throws IOException {
        try (InputStream in = AwsSigV4Test.class.getResourceAsStream("/aws-sigv4/" + vector + "/" + file)) {
            assertThat(in).as(vector + "/" + file).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"get-vanilla", "post-vanilla", "post-x-www-form-urlencoded",
            "get-vanilla-query-order-key-case", "get-vanilla-query-unreserved", "post-sts-header-after"})
    @DisplayName("canonical request, string to sign and signature exactly as AWS publishes them")
    void vector(String vector) throws IOException {
        JsonNode context = JSON.readTree(resource(vector, "context.json"));
        JsonNode credentials = context.get("credentials");
        var key = new AwsSigV4.Key(credentials.get("access_key_id").asText(), credentials.get("secret_access_key").asText(),
                credentials.has("token") ? credentials.get("token").asText() : null);

        // The request: a request line, headers to a blank line, then the body.
        String request = resource(vector, "request.txt");
        int split = request.indexOf("\n\n");
        String head = split < 0 ? request.stripTrailing() : request.substring(0, split);
        String body = split < 0 ? "" : request.substring(split + 2);
        String[] lines = head.split("\n");
        String[] requestLine = lines[0].split(" ");
        String target = requestLine[1];
        int q = target.indexOf('?');
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            headers.put(lines[i].substring(0, colon), lines[i].substring(colon + 1));
        }

        var signed = AwsSigV4.sign(requestLine[0], q < 0 ? target : target.substring(0, q), q < 0 ? null : target.substring(q + 1),
                headers, body.getBytes(StandardCharsets.UTF_8), key,
                context.get("region").asText(), context.get("service").asText(),
                Instant.parse(context.get("timestamp").asText()),
                !context.path("omit_session_token").asBoolean(false), context.path("sign_body").asBoolean(false));

        assertThat(signed.canonicalRequest()).isEqualTo(resource(vector, "header-canonical-request.txt").stripTrailing());
        assertThat(signed.stringToSign()).isEqualTo(resource(vector, "header-string-to-sign.txt").stripTrailing());
        assertThat(signed.signature()).isEqualTo(resource(vector, "header-signature.txt").strip());
        assertThat(signed.headers().get("Authorization")).endsWith("Signature=" + signed.signature());
    }
}
