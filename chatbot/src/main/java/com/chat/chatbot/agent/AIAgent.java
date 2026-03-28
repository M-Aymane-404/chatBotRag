package com.chat.chatbot.agent;

import com.chat.chatbot.tools.AgentTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class AIAgent {

    private final ChatClient chatClient;

    public AIAgent(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory, AgentTools agentTools) {
        this.chatClient = chatClientBuilder
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(agentTools)
                .build();
    }

    public Flux<String> onQuestion(String question) {
        return chatClient.prompt()
                .user(question)

                .stream()
                .content();
    }

    public String answerWithContext(String question, String context) {
        String system = """
                Tu es un assistant pour répondre aux questions d'un étudiant.
                Utilise le CONTEXTE fourni (extraits de fichiers) quand il est pertinent.
                Quand tu utilises le contexte, cite tes sources avec [1], [2], etc. exactement comme dans le CONTEXTE.
                Si le contexte ne contient pas la réponse, dis-le clairement et propose une alternative.
                Ne fabrique pas des informations.
                """;

        String user = (context == null || context.isBlank())
                ? question
                : context + "\n\nQUESTION: " + question;

        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .content();
    }

    public String answerDocumentRequest(String request, String context) {
        String system = """
                Tu es un assistant qui analyse des documents uploadés.
                Si l'utilisateur demande de lire/résumer/analyser le document, fais un résumé structuré (points clés)
                en t'appuyant uniquement sur le CONTEXTE fourni et en citant les sources [1], [2], etc.
                Si le CONTEXTE est insuffisant ou vide, explique que tu ne peux pas extraire le texte (PDF scanné / format non supporté)
                et propose une solution (convertir en PDF texte / copier-coller / OCR).
                """;

        String user = (context == null || context.isBlank())
                ? request
                : context + "\n\nDEMANDE: " + request;

        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .content();
    }

    public String answerForSpeech(String question, String context) {
        String system = """
                Tu réponds pour être lu à haute voix.
                Réponds en 1 à 2 phrases maximum, sans markdown, idéalement <= 180 caractères.
                """;

        String user = (context == null || context.isBlank())
                ? question
                : context + "\n\nQUESTION: " + question;

        return chatClient.prompt()
                .system(system)
                .user(user)
                .call()
                .content();
    }
}
