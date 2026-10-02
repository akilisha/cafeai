package io.cafeai.test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import io.cafeai.core.ai.AiProvider;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * One directory of recordings ("cassettes"), one JSON file per model call.
 *
 * <p>A call is identified by a hash of everything that decides its answer: the
 * provider and model, the provider's temperature and max tokens, every message,
 * and the request parameters (tools, response format, sampling). The file holds
 * that request in readable form, the response, and — for a streamed call — the
 * chunks in the order they arrived, so a replayed stream streams.
 */
final class Cassettes {

    private static final int VERSION = 1;
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    private final Path dir;
    private final AiProvider provider;
    private final UnaryOperator<String> normalizer;

    Cassettes(Path dir, AiProvider provider, UnaryOperator<String> normalizer) {
        this.dir = dir;
        this.provider = provider;
        this.normalizer = normalizer;
    }

    /** One streamed chunk: {@code thinking} is true for a reasoning model's thinking text. */
    record Chunk(String text, boolean thinking) { }

    /** A recorded call: its response and, if it was streamed, its chunks. */
    record Recording(ChatResponse response, List<Chunk> chunks) { }

    /** The request as recorded: what is hashed into the key, in readable form. */
    ObjectNode describe(ChatRequest request) {
        ObjectNode node = JSON.createObjectNode();
        node.put("provider", provider.name());
        node.put("model", provider.modelId());
        if (provider.temperature() != null) node.put("providerTemperature", provider.temperature());
        if (provider.maxTokens() != null) node.put("providerMaxTokens", provider.maxTokens());
        node.set("messages", readTree(ChatMessageSerializer.messagesToJson(request.messages())));

        ChatRequestParameters p = request.parameters();
        ObjectNode params = node.putObject("parameters");
        if (p != null) {
            putIfSet(params, "modelName", p.modelName());
            putIfSet(params, "temperature", p.temperature());
            putIfSet(params, "topP", p.topP());
            putIfSet(params, "topK", p.topK());
            putIfSet(params, "frequencyPenalty", p.frequencyPenalty());
            putIfSet(params, "presencePenalty", p.presencePenalty());
            putIfSet(params, "maxOutputTokens", p.maxOutputTokens());
            putIfSet(params, "stopSequences", p.stopSequences());
            putIfSet(params, "toolSpecifications", p.toolSpecifications());
            putIfSet(params, "toolChoice", p.toolChoice());
            putIfSet(params, "responseFormat", p.responseFormat());
        }
        return node;
    }

    /** The recording key for {@code request}: hex SHA-256 of its normalised description. */
    String key(ChatRequest request) {
        String canonical = normalizer.apply(write(describe(request), false));
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 24);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Optional<Recording> read(String key) {
        Path file = dir.resolve(key + ".json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            JsonNode root = JSON.readTree(file.toFile());
            JsonNode r = root.path("response");
            AiMessage message = (AiMessage) ChatMessageDeserializer.messageFromJson(r.path("message").toString());
            ChatResponse.Builder response = ChatResponse.builder().aiMessage(message);
            if (r.hasNonNull("id")) response.id(r.get("id").asText());
            if (r.hasNonNull("modelName")) response.modelName(r.get("modelName").asText());
            if (r.hasNonNull("finishReason")) response.finishReason(FinishReason.valueOf(r.get("finishReason").asText()));
            JsonNode usage = r.path("tokenUsage");
            if (usage.isObject()) {
                response.tokenUsage(new TokenUsage(intOrNull(usage, "input"), intOrNull(usage, "output"), intOrNull(usage, "total")));
            }
            List<Chunk> chunks = new ArrayList<>();
            for (JsonNode c : root.path("stream")) {
                if (c.has("thinking")) chunks.add(new Chunk(c.get("thinking").asText(), true));
                else chunks.add(new Chunk(c.path("text").asText(), false));
            }
            return Optional.of(new Recording(response.build(), chunks));
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("Cannot read cassette " + file + ": " + e.getMessage(), e);
        }
    }

    void write(String key, ChatRequest request, ChatResponse response, List<Chunk> chunks) {
        ObjectNode root = JSON.createObjectNode();
        root.put("version", VERSION);
        root.put("key", key);
        root.set("request", describe(request));

        ObjectNode r = root.putObject("response");
        r.set("message", readTree(ChatMessageSerializer.messageToJson(response.aiMessage())));
        putIfSet(r, "id", response.id());
        putIfSet(r, "modelName", response.modelName());
        if (response.finishReason() != null) r.put("finishReason", response.finishReason().name());
        TokenUsage usage = response.tokenUsage();
        if (usage != null) {
            ObjectNode u = r.putObject("tokenUsage");
            putIfSet(u, "input", usage.inputTokenCount());
            putIfSet(u, "output", usage.outputTokenCount());
            putIfSet(u, "total", usage.totalTokenCount());
        }
        if (chunks != null) {
            ArrayNode stream = root.putArray("stream");
            for (Chunk c : chunks) stream.addObject().put(c.thinking() ? "thinking" : "text", c.text());
        }

        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(key + ".json");
            Path tmp = Files.createTempFile(dir, key, ".tmp");
            Files.writeString(tmp, write(root, true) + "\n");
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write cassette for key " + key + " in " + dir, e);
        }
    }

    /** The last user message's text, to name a request in an error. */
    static String lastUserText(ChatRequest request) {
        List<ChatMessage> messages = request.messages();
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user && user.hasSingleText()) {
                String text = user.singleText();
                return text.length() > 120 ? text.substring(0, 117) + "..." : text;
            }
        }
        return null;
    }

    private static void putIfSet(ObjectNode node, String name, Object value) {
        if (value == null) return;
        if (value instanceof List<?> list && list.isEmpty()) return;
        if (value instanceof String s) node.put(name, s);
        else if (value instanceof Integer i) node.put(name, i);
        else if (value instanceof Double d) node.put(name, d);
        else if (value instanceof List<?> list) {
            ArrayNode array = node.putArray(name);
            for (Object o : list) array.add(String.valueOf(o));
        } else node.put(name, String.valueOf(value));
    }

    private static Integer intOrNull(JsonNode node, String name) {
        return node.hasNonNull(name) ? node.get(name).asInt() : null;
    }

    private static JsonNode readTree(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String write(JsonNode node, boolean pretty) {
        try {
            return pretty ? JSON.writeValueAsString(node) : JSON.writer().without(SerializationFeature.INDENT_OUTPUT).writeValueAsString(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
