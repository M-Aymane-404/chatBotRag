package com.chat.chatbot.rag;

public record RagSource(
        int citation,
        String sourceName,
        Integer page,
        double score,
        String snippet
) {
}
