package io.cafeai.core.internal;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import java.util.List;

class StreamingProbe {
    // This will fail to compile and reveal the actual parameter type of chat()
    void test(StreamingChatModel m, ChatRequest req) {
        m.chat(req, null); // What type does the compiler expect for param 2?
    }
}
