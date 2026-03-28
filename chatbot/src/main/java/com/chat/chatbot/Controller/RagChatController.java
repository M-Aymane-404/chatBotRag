package com.chat.chatbot.Controller;

import com.chat.chatbot.agent.AIAgent;
import com.chat.chatbot.audio.OpenAiCompatibleAudioClient;
import com.chat.chatbot.audio.OpenAiCompatibleHttpException;
import com.chat.chatbot.rag.RagAnswer;
import com.chat.chatbot.rag.EmbeddingsRagService;
import com.chat.chatbot.rag.RagAudio;
import com.chat.chatbot.rag.RagService;
import com.chat.chatbot.rag.RagSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Base64;
import java.util.List;
import java.util.UUID;

@CrossOrigin("*")
@RestController
@RequestMapping("/api")
public class RagChatController {
    private static final Logger log = LoggerFactory.getLogger(RagChatController.class);

    private final AIAgent aiAgent;
    private final RagService bm25RagService;
    private final EmbeddingsRagService embeddingsRagService;
    private final ObjectProvider<OpenAiCompatibleAudioClient> audioClientProvider;
    private final String ragMode;

    public RagChatController(
            AIAgent aiAgent,
            RagService bm25RagService,
            EmbeddingsRagService embeddingsRagService,
            ObjectProvider<OpenAiCompatibleAudioClient> audioClientProvider,
            @Value("${chatbot.rag.mode:bm25}") String ragMode
    ) {
        this.aiAgent = aiAgent;
        this.bm25RagService = bm25RagService;
        this.embeddingsRagService = embeddingsRagService;
        this.audioClientProvider = audioClientProvider;
        this.ragMode = ragMode;
    }

    @PostMapping(value = "/chat", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> chat(
            @RequestParam(required = false) String sessionId,
            @RequestParam(defaultValue = "text") String format,
            @RequestParam(required = false) String responseMode,
            @RequestPart(required = false) String question,
            @RequestPart(required = false) MultipartFile audio,
            @RequestPart(required = false, name = "files") List<MultipartFile> files
    ) {
        String sid = (sessionId == null || sessionId.isBlank()) ? UUID.randomUUID().toString() : sessionId;
        int filesCount = files == null ? 0 : files.size();
        String effectiveResponseMode = normalizeResponseMode(responseMode, format);
        log.info("POST /api/chat sid={} format={} ragMode={} hasQuestion={} hasAudio={} files={}",
                sid, effectiveResponseMode, ragMode, question != null && !question.isBlank(), audio != null && !audio.isEmpty(), filesCount);

        String transcription = null;
        String finalQuestion = question == null ? null : question.trim();
        if ((finalQuestion == null || finalQuestion.isBlank()) && audio != null && !audio.isEmpty()) {
            OpenAiCompatibleAudioClient audioClient = audioClientProvider.getIfAvailable();
            if (audioClient == null) {
                return ResponseEntity.status(400)
                        .header("X-Session-Id", sid)
                        .body("Audio désactivé (chatbot.ai.enabled=false).");
            }
            try {
                transcription = audioClient.transcribe(audio);
                finalQuestion = transcription;
                log.info("STT ok sid={} transcriptionLen={}", sid, transcription == null ? 0 : transcription.length());
            } catch (Exception e) {
                return stage502(sid, Stage.STT, "Transcription audio impossible (STT).", e);
            }
        }
        if ((finalQuestion == null || finalQuestion.isBlank()) && filesCount > 0) {
            finalQuestion = defaultDocumentQuestion(files);
        }
        if (finalQuestion == null || finalQuestion.isBlank()) {
            return ResponseEntity.badRequest().body("Question manquante (texte ou audio).");
        }

        List<RagSource> sources;
        String context;
        try {
            if ("embeddings".equalsIgnoreCase(ragMode)) {
                embeddingsRagService.index(sid, files);
                sources = embeddingsRagService.retrieveSources(sid, finalQuestion, 4);
                if (sources.isEmpty() && filesCount > 0 && looksLikeReadTheDocRequest(finalQuestion)) {
                    sources = embeddingsRagService.retrieveAnySources(sid, 4);
                }
                context = embeddingsRagService.buildContext(sources);
            } else {
                bm25RagService.index(sid, files);
                sources = bm25RagService.retrieveSources(sid, finalQuestion, 4);
                if (sources.isEmpty() && filesCount > 0 && looksLikeReadTheDocRequest(finalQuestion)) {
                    sources = bm25RagService.retrieveAnySources(sid, 4);
                }
                context = bm25RagService.buildContext(sources);
            }
            log.info("RAG ok sid={} sources={}", sid, sources == null ? 0 : sources.size());
        } catch (Exception e) {
            return stage502(sid, Stage.RAG, "RAG impossible (" + ragMode + ").", e);
        }

        String answer;
        try {
            if (filesCount > 0 && looksLikeReadTheDocRequest(finalQuestion)) {
                answer = aiAgent.answerDocumentRequest(finalQuestion, context);
            } else {
                answer = aiAgent.answerWithContext(finalQuestion, context);
            }
            log.info("LLM ok sid={} answerLen={}", sid, answer == null ? 0 : answer.length());
        } catch (Exception e) {
            if (e instanceof NonTransientAiException ne) {
                String msg = ne.getMessage() == null ? "" : ne.getMessage();
                if (msg.contains("HTTP 429") || msg.toLowerCase().contains("rate_limit_exceeded")) {
                    Integer retryAfter = parseRetryAfterSeconds(msg);

                    HttpHeaders h = new HttpHeaders();
                    h.add("X-Session-Id", sid);
                    h.add("X-Error-Stage", Stage.LLM.name());
                    if (retryAfter != null) h.add("Retry-After", String.valueOf(retryAfter));

                    log.warn("stage=LLM sid={} rate_limited retryAfter={}s", sid, retryAfter);
                    ne.printStackTrace();

                    return ResponseEntity.status(429)
                            .headers(h)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(new ChatError(
                                    Stage.LLM.name(),
                                    retryAfter == null
                                            ? "Rate limit Groq atteint. Reessaie dans quelques secondes."
                                            : ("Rate limit Groq atteint. Reessaie dans " + retryAfter + "s."),
                                    ne.getClass().getName(),
                                    safe(msg)
                            ));
                }
            }
            return stage502(sid, Stage.LLM, "Réponse IA impossible (LLM/Groq).", e);
        }

        HttpHeaders headers = new HttpHeaders();
        headers.add("X-Session-Id", sid);
        headers.add("X-Rag-Mode", ragMode);

        if ("audio".equalsIgnoreCase(effectiveResponseMode) && (responseMode == null || responseMode.isBlank())) {
            OpenAiCompatibleAudioClient audioClient = audioClientProvider.getIfAvailable();
            if (audioClient == null) {
                return ResponseEntity.status(400)
                        .headers(headers)
                        .body("Audio désactivé (chatbot.ai.enabled=false).");
            }
            String speechText;
            try {
                speechText = aiAgent.answerForSpeech(finalQuestion, context);
                log.info("Speech text ok sid={} speechLen={}", sid, speechText == null ? 0 : speechText.length());
            } catch (Exception e) {
                return stage502(sid, Stage.LLM, "Génération du texte pour audio impossible.", e);
            }

            byte[] audioBytes;
            try {
                audioBytes = audioClient.textToSpeech(speechText);
            } catch (OpenAiCompatibleHttpException e) {
                if (e.getStatusCode() == 400 && looksLikeModelTermsRequired(e.getResponseBody())) {
                    log.warn("TTS model terms required sid={} body={}", sid, e.getResponseBody());
                    e.printStackTrace();
                    return ResponseEntity.status(409)
                            .headers(headers)
                            .header("X-Error-Stage", Stage.TTS.name())
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(new ChatError(
                                    Stage.TTS.name(),
                                    "TTS bloqué: acceptation des conditions du modèle requise côté Groq (model_terms_required).",
                                    e.getClass().getName(),
                                    e.getResponseBody()
                            ));
                }
                return stage502(sid, Stage.TTS, "Synthèse vocale impossible (TTS).", e);
            } catch (Exception e) {
                return stage502(sid, Stage.TTS, "Synthèse vocale impossible (TTS).", e);
            }
            MediaType mt = audioClient.speechMediaType();
            return ResponseEntity.ok()
                    .headers(headers)
                    .contentType(mt)
                    .body(audioBytes);
        }

        RagAudio ragAudio = null;
        String audioError = null;
        if (requiresAudio(effectiveResponseMode)) {
            try {
                ragAudio = buildAudioPayload(finalQuestion, context);
            } catch (Exception e) {
                log.warn("stage=TTS sid={} audio_json_failed={}", sid, e.getMessage(), e);
                audioError = audioErrorMessage(e);
            }
        }

        RagAnswer payload = new RagAnswer(sid, finalQuestion, answer, transcription, sources, ragAudio, audioError);
        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(payload);
    }

    private RagAudio buildAudioPayload(String question, String context) throws Exception {
        OpenAiCompatibleAudioClient audioClient = audioClientProvider.getIfAvailable();
        if (audioClient == null) {
            throw new IllegalStateException("Audio désactivé (chatbot.ai.enabled=false).");
        }
        String speechText = aiAgent.answerForSpeech(question, context);
        byte[] audioBytes = audioClient.textToSpeech(speechText);
        return new RagAudio(
                audioClient.speechMediaType().toString(),
                Base64.getEncoder().encodeToString(audioBytes),
                speechText
        );
    }

    private ResponseEntity<ChatError> stage502(String sid, Stage stage, String message, Exception e) {
        log.error("stage={} sid={} msg={}", stage, sid, message, e);
        e.printStackTrace();

        return ResponseEntity.status(502)
                .header("X-Session-Id", sid)
                .header("X-Error-Stage", stage.name())
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ChatError(stage.name(), message, e.getClass().getName(), safe(e.getMessage())));
    }

    private static String normalizeResponseMode(String responseMode, String format) {
        String candidate = responseMode == null || responseMode.isBlank() ? format : responseMode;
        if (candidate == null || candidate.isBlank()) {
            return "text";
        }
        return switch (candidate.trim().toLowerCase()) {
            case "text", "audio", "both" -> candidate.trim().toLowerCase();
            default -> "text";
        };
    }

    private static boolean requiresAudio(String responseMode) {
        return "audio".equalsIgnoreCase(responseMode) || "both".equalsIgnoreCase(responseMode);
    }

    private static String defaultDocumentQuestion(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return "Analyse le document.";
        }
        String joinedNames = files.stream()
                .filter(file -> file != null && !file.isEmpty())
                .map(MultipartFile::getOriginalFilename)
                .filter(name -> name != null && !name.isBlank())
                .limit(3)
                .map(String::trim)
                .reduce((left, right) -> left + ", " + right)
                .orElse("les fichiers envoyés");
        return "Analyse " + joinedNames + " et réponds à partir de leur contenu.";
    }

    private static String audioErrorMessage(Exception e) {
        if (e instanceof OpenAiCompatibleHttpException httpException) {
            if (httpException.getStatusCode() == 400 && looksLikeModelTermsRequired(httpException.getResponseBody())) {
                return "TTS bloqué: accepte les conditions du modèle audio côté fournisseur.";
            }
            return "Réponse audio indisponible: " + safe(httpException.getResponseBody());
        }
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            return "Réponse audio indisponible.";
        }
        return "Réponse audio indisponible: " + safe(message);
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.length() > 2000 ? s.substring(0, 2000) + "..." : s;
    }

    private static boolean looksLikeReadTheDocRequest(String q) {
        if (q == null) return false;
        String s = q.toLowerCase();
        return s.contains("document")
                || s.contains("fichier")
                || s.contains("lire")
                || s.contains("résume")
                || s.contains("resume")
                || s.contains("résumer")
                || s.contains("analy")
                || s.contains("analyse")
                || s.contains("summar");
    }

    private static boolean looksLikeModelTermsRequired(String body) {
        if (body == null) return false;
        String s = body.toLowerCase();
        return s.contains("model_terms_required") || s.contains("requires terms acceptance") || s.contains("accept the terms");
    }

    private static Integer parseRetryAfterSeconds(String text) {
        if (text == null) return null;
        // Example: "Please try again in 18.525s."
        String lower = text.toLowerCase();
        int idx = lower.indexOf("try again in ");
        if (idx < 0) return null;
        String tail = lower.substring(idx + "try again in ".length());
        int sIdx = tail.indexOf('s');
        if (sIdx <= 0) return null;
        String num = tail.substring(0, sIdx).trim();
        try {
            double v = Double.parseDouble(num);
            int ceil = (int) Math.ceil(v);
            return ceil <= 0 ? 1 : ceil;
        } catch (Exception ignored) {
            return null;
        }
    }

    private enum Stage { STT, RAG, LLM, TTS }

    private record ChatError(String stage, String message, String exception, String detail) {}
}
