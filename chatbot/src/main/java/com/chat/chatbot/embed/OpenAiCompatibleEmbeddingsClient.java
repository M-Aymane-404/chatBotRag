package com.chat.chatbot.embed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class OpenAiCompatibleEmbeddingsClient {
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiCompatibleEmbeddingsClient(
            @Value("${chatbot.embed.base-url:}") String baseUrl,
            @Value("${chatbot.embed.api-key:}") String apiKey,
            @Value("${chatbot.embed.model:}") String model
    ) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.model = model;
    }

    public List<float[]> embed(List<String> inputs) throws IOException, InterruptedException {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Clé API embeddings manquante: définir chatbot.embed.api-key (ou GROQ_API_KEY si même provider).");
        }
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        if (model == null || model.isBlank()) {
            throw new IllegalStateException("Embeddings: chatbot.embed.model est vide. Configure un modèle embeddings.");
        }

        String payload = objectMapper.createObjectNode()
                .put("model", model)
                .set("input", objectMapper.valueToTree(inputs))
                .toString();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(v1("/embeddings")))
                .timeout(Duration.ofMinutes(2))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() / 100 != 2) {
            throw new IllegalStateException("Embeddings erreur HTTP " + resp.statusCode() + ": " + safe(resp.body()));
        }

        JsonNode json = objectMapper.readTree(resp.body());
        JsonNode data = json.get("data");
        if (data == null || !data.isArray()) {
            throw new IllegalStateException("Embeddings: réponse invalide (data manquant).");
        }

        List<float[]> vectors = new ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode emb = item.get("embedding");
            if (emb == null || !emb.isArray()) {
                throw new IllegalStateException("Embeddings: embedding manquant.");
            }
            float[] v = new float[emb.size()];
            for (int i = 0; i < emb.size(); i++) {
                v[i] = (float) emb.get(i).asDouble();
            }
            vectors.add(v);
        }
        return vectors;
    }

    public float[] embedOne(String input) throws IOException, InterruptedException {
        List<float[]> v = embed(List.of(input));
        if (v.isEmpty()) {
            throw new IllegalStateException("Embeddings: vecteur vide.");
        }
        return v.get(0);
    }

    private String v1(String path) {
        String base = trimTrailingSlash(baseUrl);
        if (base.endsWith("/v1")) {
            return base + path;
        }
        return base + "/v1" + path;
    }

    private static String trimTrailingSlash(String s) {
        if (s == null) {
            return "";
        }
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private static String safe(String s) {
        if (s == null) {
            return "";
        }
        return s.length() > 500 ? s.substring(0, 500) + "..." : s;
    }
}
