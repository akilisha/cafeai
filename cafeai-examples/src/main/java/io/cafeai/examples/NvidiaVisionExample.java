package io.cafeai.examples;

import io.cafeai.core.CafeAI;
import io.cafeai.core.ai.Nvidia;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * NvidiaVisionExample — a multimodal, streamed call to a model on NVIDIA's
 * hosted API catalog (build.nvidia.com).
 *
 * <p>This is the CafeAI equivalent of NVIDIA's own LangChain (Python) snippet
 * for the same model, kept here as the reference the example was translated from:
 *
 * <pre>
 *   from langchain_nvidia_ai_endpoints import ChatNVIDIA
 *
 *   client = ChatNVIDIA(
 *     model="moonshotai/kimi-k3",
 *     api_key="nvapi-...",            # never commit a real key
 *     temperature=1,
 *     max_completion_tokens=16384,
 *   )
 *
 *   lc_messages = [
 *     {
 *       "role": "user",
 *       "content": [
 *         {"type": "text", "text": "What is in this image?"},
 *         {"type": "image_url", "image_url": {
 *           "url": "https://assets.ngc.nvidia.com/products/api-catalog/phi-3-5-vision/example1b.jpg",
 *         }},
 *       ],
 *     },
 *   ]
 *
 *   for chunk in client.stream(lc_messages):
 *     if chunk.additional_kwargs and "reasoning_content" in chunk.additional_kwargs:
 *       print(chunk.additional_kwargs["reasoning_content"], end="")
 *     print(chunk.content, end="")
 * </pre>
 *
 * <p>How each piece maps:
 * <ul>
 *   <li>{@code ChatNVIDIA(model=...)} → {@code app.ai(Nvidia.of("moonshotai/kimi-k3"))};
 *       the key is read from {@code $NVIDIA_API_KEY}, never passed in code.</li>
 *   <li>The text + {@code image_url} message → {@code app.vision(prompt, bytes, mimeType)}.
 *       CafeAI's vision API takes bytes, not a URL, so the image is fetched first.</li>
 *   <li>{@code client.stream(...)} → {@code .stream(chunk -> ...)}.</li>
 *   <li>The {@code reasoning_content} branch → {@code .onThinking(...)}. Reasoning
 *       goes to stderr here so it stays separate from the answer on stdout.</li>
 *   <li>{@code temperature=1} and {@code max_completion_tokens=16384} →
 *       {@code .withTemperature(1.0)} and {@code .withMaxTokens(16384)}. Those two
 *       are on every CafeAI provider; {@code withReasoningEffort} is NVIDIA's own.</li>
 *   <li>NVIDIA's curl variant also sends {@code "reasoning_effort":"max"} →
 *       {@code .withReasoningEffort("max")}.</li>
 *   <li>The run takes about two minutes end to end, so the provider's timeout
 *       matters: NVIDIA does not respond for a long stretch, which is longer than
 *       CafeAI's 60-second {@code cafeai.chat.timeout} default. {@code Nvidia}
 *       defaults to five minutes; override with {@code .withTimeout(Duration)}.
 *       What the two minutes is spent on is not established — the reasoning text
 *       it returned is short.</li>
 * </ul>
 *
 * <pre>
 *   export NVIDIA_API_KEY=nvapi-...
 *   ./gradlew :cafeai-examples:run -PmainClass=io.cafeai.examples.NvidiaVisionExample
 * </pre>
 */
public class NvidiaVisionExample {

    private static final String IMAGE_URL =
        "https://assets.ngc.nvidia.com/products/api-catalog/phi-3-5-vision/example1b.jpg";

    public static void main(String[] args) throws Exception {
        var app = CafeAI.create();
        app.ai(Nvidia.of("moonshotai/kimi-k3")
            .withTemperature(1.0)
            .withMaxTokens(16384)
            .withReasoningEffort("max"));

        HttpResponse<byte[]> image = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build()
            .send(HttpRequest.newBuilder(URI.create(IMAGE_URL)).build(),
                  HttpResponse.BodyHandlers.ofByteArray());
        String mimeType = image.headers().firstValue("Content-Type").orElse("image/jpeg");

        app.vision("What is in this image?", image.body(), mimeType)
           .onThinking(System.err::print)
           .stream(System.out::print);
        System.out.println();
    }
}
