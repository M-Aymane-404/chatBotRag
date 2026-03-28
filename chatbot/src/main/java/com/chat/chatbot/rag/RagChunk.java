package com.chat.chatbot.rag;

public record RagChunk(
        String id,
        String sessionId,
        String sourceName,
        Integer page,
        String text
) {
}
