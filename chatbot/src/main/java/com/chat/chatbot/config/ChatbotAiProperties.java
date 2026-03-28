package com.chat.chatbot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration pour les appels OpenAI-compatibles (Groq) utilisés par STT/TTS.
 */
@Validated
@ConfigurationProperties(prefix = "chatbot.ai")
public class ChatbotAiProperties {

    private boolean enabled = true;

    private String baseUrl;


    private String apiKey;

    private String sttModel = "whisper-large-v3-turbo";
    private String ttsModel = "canopylabs/orpheus-v1-english";
    private String ttsVoice = "autumn";
    private String ttsResponseFormat = "wav";

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getSttModel() {
        return sttModel;
    }

    public void setSttModel(String sttModel) {
        this.sttModel = sttModel;
    }

    public String getTtsModel() {
        return ttsModel;
    }

    public void setTtsModel(String ttsModel) {
        this.ttsModel = ttsModel;
    }

    public String getTtsVoice() {
        return ttsVoice;
    }

    public void setTtsVoice(String ttsVoice) {
        this.ttsVoice = ttsVoice;
    }

    public String getTtsResponseFormat() {
        return ttsResponseFormat;
    }

    public void setTtsResponseFormat(String ttsResponseFormat) {
        this.ttsResponseFormat = ttsResponseFormat;
    }

}
