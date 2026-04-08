package io.cafeai.core.internal;

import io.cafeai.core.ai.VisionRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for VisionMessageBuilder — package-private, tested from the
 * internal package where it lives.
 */
class VisionMessageBuilderTest {

    @Nested
    @DisplayName("VisionMessageBuilder")
    class Tests {

        @Test
        @DisplayName("PDF content produces UserMessage with PdfFileContent and TextContent")
        void pdf_producesPdfFileContent() {
            byte[] pdf = {37, 80, 68, 70}; // %PDF
            var messages = VisionMessageBuilder.build(
                "is this an invoice?", pdf, "application/pdf", null, List.of());

            assertThat(messages).hasSize(1);
            var userMsg = (dev.langchain4j.data.message.UserMessage) messages.get(0);
            var contents = userMsg.contents();
            // Two content parts: PdfFileContent + TextContent
            assertThat(contents).hasSize(2);
            assertThat(contents.get(0))
                .isInstanceOf(dev.langchain4j.data.message.PdfFileContent.class);
            assertThat(contents.get(1))
                .isInstanceOf(dev.langchain4j.data.message.TextContent.class);
            // The data URI prefix is critical -- verify the PDF content is not empty
            var pdfContent = (dev.langchain4j.data.message.PdfFileContent) contents.get(0);
            assertThat(pdfContent.pdfFile()).isNotNull();
        }

        @Test
        @DisplayName("image content produces UserMessage with ImageContent")
        void image_producesImageContent() {
            byte[] image = {-119, 80, 78, 71}; // PNG header
            var messages = VisionMessageBuilder.build(
                "describe damage", image, "image/png", null, List.of());

            assertThat(messages).hasSize(1);
            var userMsg = (dev.langchain4j.data.message.UserMessage) messages.get(0);
            assertThat(userMsg.contents()).hasSize(2);
            assertThat(userMsg.contents().get(0))
                .isInstanceOf(dev.langchain4j.data.message.ImageContent.class);
        }

        @Test
        @DisplayName("system prompt added as first message when present")
        void systemPrompt_addedFirst() {
            byte[] img = {1};
            var messages = VisionMessageBuilder.build(
                "prompt", img, "image/jpeg", "You are an AP assistant.", List.of());

            assertThat(messages).hasSize(2);
            assertThat(messages.get(0))
                .isInstanceOf(dev.langchain4j.data.message.SystemMessage.class);
        }

        @Test
        @DisplayName("null system prompt produces no system message")
        void nullSystemPrompt_noSystemMessage() {
            byte[] img = {1};
            var messages = VisionMessageBuilder.build(
                "prompt", img, "image/jpeg", null, List.of());

            assertThat(messages).hasSize(1);
            assertThat(messages.get(0))
                .isInstanceOf(dev.langchain4j.data.message.UserMessage.class);
        }

        @Test
        @DisplayName("unsupported MIME type throws UnsupportedContentTypeException")
        void unsupportedMimeType_throws() {
            byte[] audio = {1, 2, 3};
            assertThatThrownBy(() ->
                VisionMessageBuilder.build("transcribe", audio, "audio/wav", null, List.of()))
                .isInstanceOf(VisionRequest.UnsupportedContentTypeException.class)
                .hasMessageContaining("audio/wav")
                .hasMessageContaining("Supported");
        }
    }
}
