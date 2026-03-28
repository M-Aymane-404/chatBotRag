package com.chat.chatbot.rag;

public record RagAudio(
        String contentType,
        String base64,
        String text
) {
}
