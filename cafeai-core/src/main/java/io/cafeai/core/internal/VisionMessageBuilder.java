package io.cafeai.core.internal;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import io.cafeai.core.ai.VisionRequest;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Builds a LangChain4j {@link ChatMessage} list for a vision (multimodal) call.
 *
 * <p>Isolates the LangChain4j-specific message construction so it can be
 * tested independently and updated when LangChain4j changes its multimodal API.
 *
 * <p>Supported MIME types:
 * <ul>
 *   <li>{@code application/pdf} → {@link PdfFileContent} with data URI prefix</li>
 *   <li>{@code image/*} (jpeg, png, gif, webp) → {@link ImageContent} with base64</li>
 * </ul>
 *
 * <p><strong>Critical implementation note for PDFs:</strong>
 * {@link PdfFileContent#from(String)} requires a {@code data:} URI prefix —
 * raw base64 alone causes a 400 error from the OpenAI API:
 * <pre>
 *   "Expected a base64-encoded data URL with an application/pdf MIME type
 *    (e.g. 'data:application/pdf;base64,...'), but got a value without the 'data:' prefix."
 * </pre>
 *
 * <p>Package-private — internal to {@code CafeAIApp}.
 */
final class VisionMessageBuilder {

    private VisionMessageBuilder() {}

    /**
     * Builds the complete message list for a vision call.
     *
     * @param prompt       the text instruction
     * @param content      the binary content (PDF or image bytes)
     * @param mimeType     MIME type of the content
     * @param systemPrompt the system prompt, or {@code null} if none
     * @param history      prior conversation messages (may be empty)
     * @return ordered list: system → history → user multimodal message
     * @throws VisionRequest.UnsupportedContentTypeException for unsupported MIME types
     */
    static List<ChatMessage> build(String prompt, byte[] content, String mimeType,
                                   String systemPrompt, List<ChatMessage> history) {
        List<ChatMessage> messages = new ArrayList<>();

        // 1. System message first
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }

        // 2. Prior conversation history
        messages.addAll(history);

        // 3. Multimodal user message — binary content + text prompt
        messages.add(buildUserMessage(prompt, content, mimeType));

        return messages;
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private static UserMessage buildUserMessage(String prompt, byte[] content,
                                                String mimeType) {
        String lower = mimeType.toLowerCase();

        if (lower.equals("application/pdf")) {
            return buildPdfMessage(prompt, content, mimeType);
        }

        if (lower.startsWith("image/")) {
            return buildImageMessage(prompt, content, mimeType);
        }

        throw new VisionRequest.UnsupportedContentTypeException(
            "Unsupported MIME type for vision: '" + mimeType + "'. " +
            "Supported types: application/pdf, image/jpeg, image/png, image/gif, image/webp. " +
            "Check app.vision() documentation for the full list.");
    }

    private static UserMessage buildPdfMessage(String prompt, byte[] content, String mimeType) {
        // PdfFileContent.from() requires a data: URI — raw base64 is rejected by the API.
        // Error message from OpenAI if omitted:
        //   "Expected a base64-encoded data URL with an application/pdf MIME type
        //    (e.g. 'data:application/pdf;base64,...'), but got a value without the 'data:' prefix."
        String base64  = Base64.getEncoder().encodeToString(content);
        String dataUri = "data:" + mimeType + ";base64," + base64;
        return UserMessage.from(
            PdfFileContent.from(dataUri),
            TextContent.from(prompt));
    }

    private static UserMessage buildImageMessage(String prompt, byte[] content, String mimeType) {
        String base64 = Base64.getEncoder().encodeToString(content);
        return UserMessage.from(
            ImageContent.from(base64, mimeType),
            TextContent.from(prompt));
    }
}
