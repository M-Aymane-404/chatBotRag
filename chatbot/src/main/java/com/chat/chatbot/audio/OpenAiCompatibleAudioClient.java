package com.chat.chatbot.audio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

public class OpenAiCompatibleAudioClient {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final String baseUrl;
    private final String apiKey;
    private final String sttModel;
    private final String ttsModel;
    private final String ttsVoice;
    private final String ttsResponseFormat;

    public OpenAiCompatibleAudioClient(
            String baseUrl,
            String apiKey,
            String sttModel,
            String ttsModel,
            String ttsVoice,
            String ttsResponseFormat
    ) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.sttModel = sttModel;
        this.ttsModel = ttsModel;
        this.ttsVoice = ttsVoice;
        this.ttsResponseFormat = ttsResponseFormat;
    }

    public String transcribe(MultipartFile audioFile) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API manquante: définir GROQ_API_KEY (ou spring.ai.openai.api-key).");
        }
        if (audioFile == null || audioFile.isEmpty()) {
            throw new IllegalArgumentException("Audio vide");
        }
        String boundary = "----ChatbotBoundary" + UUID.randomUUID();
        byte[] body = buildMultipartTranscriptionBody(boundary, audioFile);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(v1("/audio/transcriptions")))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new OpenAiCompatibleHttpException("STT", resp.statusCode(), safe(resp.body()));
        }
        JsonNode json = objectMapper.readTree(resp.body());
        JsonNode text = json.get("text");
        if (text == null || text.asText().isBlank()) {
            throw new IllegalStateException("STT: réponse invalide");
        }
        return text.asText();
    }

    public byte[] textToSpeech(String text) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API manquante: définir GROQ_API_KEY (ou spring.ai.openai.api-key).");
        }
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Texte vide");
        }
        String payload = objectMapper.createObjectNode()
                .put("model", ttsModel)
                .put("input", text)
                .put("voice", ttsVoice)
                .put("response_format", ttsResponseFormat)
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(v1("/audio/speech")))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<byte[]> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() / 100 != 2) {
            String body = new String(resp.body(), StandardCharsets.UTF_8);
            throw new OpenAiCompatibleHttpException("TTS", resp.statusCode(), safe(body));
        }
        return resp.body();
    }

    public MediaType speechMediaType() {
        String fmt = ttsResponseFormat == null ? "" : ttsResponseFormat.trim().toLowerCase();
        return switch (fmt) {
            case "wav", "wave" -> MediaType.parseMediaType("audio/wav");
            case "mp3", "mpeg" -> MediaType.parseMediaType("audio/mpeg");
            case "ogg" -> MediaType.parseMediaType("audio/ogg");
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }

    private byte[] buildMultipartTranscriptionBody(String boundary, MultipartFile audioFile) throws IOException {
        String filename = audioFile.getOriginalFilename() == null ? "audio" : audioFile.getOriginalFilename();
        String contentType = audioFile.getContentType() == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : audioFile.getContentType();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writePart(out, boundary, "model", null, MediaType.TEXT_PLAIN_VALUE, sttModel.getBytes(StandardCharsets.UTF_8));
        writeFilePart(out, boundary, "file", filename, contentType, audioFile.getBytes());
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static void writePart(ByteArrayOutputStream out, String boundary, String name, String filename, String contentType, byte[] data) throws IOException {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (filename == null) {
            out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        } else {
            out.write(("Content-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename + "\"\r\n").getBytes(StandardCharsets.UTF_8));
        }
        out.write(("Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private static void writeFilePart(ByteArrayOutputStream out, String boundary, String name, String filename, String contentType, byte[] data) throws IOException {
        writePart(out, boundary, name, filename, contentType, data);
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private String v1(String path) {
        String base = trimTrailingSlash(baseUrl);
        if (base.endsWith("/v1")) {
            return base + path;
        }
        return base + "/v1" + path;
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
