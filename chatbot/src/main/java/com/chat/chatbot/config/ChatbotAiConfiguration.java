package com.chat.chatbot.config;

import com.chat.chatbot.audio.OpenAiCompatibleAudioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration
@EnableConfigurationProperties(ChatbotAiProperties.class)
public class ChatbotAiConfiguration {

    @Bean
    @ConditionalOnProperty(prefix = "chatbot.ai", name = "enabled", havingValue = "true", matchIfMissing = true)
    public OpenAiCompatibleAudioClient openAiCompatibleAudioClient(ChatbotAiProperties props, Environment env) {
        String baseUrl = firstNonBlank(
                props.getBaseUrl(),
                env.getProperty("spring.ai.openai.base-url")
        );
        String apiKey = firstNonBlank(
                props.getApiKey(),
                env.getProperty("spring.ai.openai.api-key"),
                env.getProperty("spring.ai.openai.chat.api-key")
        );

        requireText(baseUrl, "chatbot.ai.base-url (ou spring.ai.openai.base-url)");
        requireText(apiKey, "chatbot.ai.api-key (ou spring.ai.openai.api-key)");

        return new OpenAiCompatibleAudioClient(
                baseUrl,
                apiKey,
                props.getSttModel(),
                props.getTtsModel(),
                props.getTtsVoice(),
                props.getTtsResponseFormat()
        );
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Propriété requise manquante: " + name);
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String v : values) {
            if (v != null && !v.trim().isEmpty()) {
                return v;
            }
        }
        return null;
    }
}

