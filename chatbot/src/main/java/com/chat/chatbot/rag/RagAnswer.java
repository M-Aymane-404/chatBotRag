package com.chat.chatbot.rag;

import java.util.List;

public record RagAnswer(
        String sessionId,
        String question,
        String answer,
        String transcription,
        List<RagSource> sources,
        RagAudio audio,
        String audioError
) {
}
