package com.chat.chatbot.rag;

import com.chat.chatbot.embed.OpenAiCompatibleEmbeddingsClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class EmbeddingsRagService {
    private final OpenAiCompatibleEmbeddingsClient embeddingsClient;

    private final Map<String, List<Entry>> entriesBySession = new ConcurrentHashMap<>();

    public EmbeddingsRagService(OpenAiCompatibleEmbeddingsClient embeddingsClient) {
        this.embeddingsClient = embeddingsClient;
    }

    public void index(String sessionId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        List<Entry> entries = entriesBySession.computeIfAbsent(sessionId, ignored -> new ArrayList<>());
        List<RagChunk> chunksToEmbed = new ArrayList<>();

        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String sourceName = safeName(file.getOriginalFilename());
            try {
                collectChunks(sessionId, file, sourceName, chunksToEmbed);
            } catch (IOException e) {
                throw new IllegalArgumentException("Impossible de lire le fichier: " + sourceName, e);
            }
        }

        if (chunksToEmbed.isEmpty()) {
            return;
        }

        // Batch embeddings calls
        int batchSize = 16;
        for (int i = 0; i < chunksToEmbed.size(); i += batchSize) {
            int end = Math.min(chunksToEmbed.size(), i + batchSize);
            List<RagChunk> batch = chunksToEmbed.subList(i, end);
            List<String> texts = batch.stream().map(RagChunk::text).toList();
            List<float[]> vectors;
            try {
                vectors = embeddingsClient.embed(texts);
            } catch (Exception e) {
                throw new IllegalStateException("Embeddings indisponibles: " + e.getMessage(), e);
            }
            if (vectors.size() != batch.size()) {
                throw new IllegalStateException("Embeddings: taille réponse != taille batch.");
            }
            for (int j = 0; j < batch.size(); j++) {
                entries.add(new Entry(batch.get(j), vectors.get(j)));
            }
        }
    }

    public List<RagSource> retrieveSources(String sessionId, String question, int k) {
        List<Entry> entries = entriesBySession.getOrDefault(sessionId, List.of());
        if (entries.isEmpty()) {
            return List.of();
        }
        float[] q;
        try {
            q = embeddingsClient.embedOne(question);
        } catch (Exception e) {
            throw new IllegalStateException("Embeddings indisponibles: " + e.getMessage(), e);
        }

        List<Scored> scored = entries.stream()
                .map(e -> new Scored(e, cosine(q, e.embedding)))
                .sorted(Comparator.<Scored>comparingDouble(s -> s.score).reversed())
                .limit(k)
                .toList();

        if (scored.isEmpty()) {
            return List.of();
        }

        List<RagSource> out = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            RagChunk c = scored.get(i).entry.chunk;
            out.add(new RagSource(
                    i + 1,
                    c.sourceName(),
                    c.page(),
                    scored.get(i).score,
                    snippet(c.text(), 500)
            ));
        }
        return out;
    }

    public List<RagSource> retrieveAnySources(String sessionId, int k) {
        List<Entry> entries = entriesBySession.getOrDefault(sessionId, List.of());
        if (entries.isEmpty()) {
            return List.of();
        }
        int limit = Math.min(k, entries.size());
        List<RagSource> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            RagChunk c = entries.get(i).chunk;
            out.add(new RagSource(
                    i + 1,
                    c.sourceName(),
                    c.page(),
                    0.0,
                    snippet(c.text(), 500)
            ));
        }
        return out;
    }

    public String buildContext(List<RagSource> sources) {
        if (sources == null || sources.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("CONTEXTE (extraits de fichiers):\n");
        for (RagSource s : sources) {
            sb.append("- [").append(s.citation()).append("] ")
                    .append(s.sourceName());
            if (s.page() != null) {
                sb.append(" (page ").append(s.page()).append(")");
            }
            sb.append(": ").append(s.snippet()).append("\n");
        }
        return sb.toString();
    }

    private static void collectChunks(String sessionId, MultipartFile file, String sourceName, List<RagChunk> out) throws IOException {
        String name = safeName(file.getOriginalFilename()).toLowerCase();
        if (name.endsWith(".pdf")) {
            try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                int pages = document.getNumberOfPages();
                for (int p = 1; p <= pages; p++) {
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);
                    String pageText = stripper.getText(document);
                    addChunks(sessionId, sourceName, p, pageText, out);
                }
            }
            return;
        }
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        addChunks(sessionId, sourceName, null, text, out);
    }

    private static void addChunks(String sessionId, String sourceName, Integer page, String text, List<RagChunk> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String chunkText : chunk(text, 1200, 200)) {
            String trimmed = chunkText.trim();
            if (trimmed.isBlank()) {
                continue;
            }
            out.add(new RagChunk(UUID.randomUUID().toString(), sessionId, sourceName, page, trimmed));
        }
    }

    private static List<String> chunk(String text, int size, int overlap) {
        String normalized = text.replace("\r", "");
        List<String> chunks = new ArrayList<>();
        int start = 0;
        while (start < normalized.length()) {
            int end = Math.min(normalized.length(), start + size);
            String part = normalized.substring(start, end).trim();
            if (!part.isBlank()) {
                chunks.add(part);
            }
            if (end == normalized.length()) {
                break;
            }
            start = Math.max(0, end - overlap);
        }
        return chunks;
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) {
            return 0;
        }
        int n = Math.min(a.length, b.length);
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < n; i++) {
            dot += (double) a[i] * (double) b[i];
            na += (double) a[i] * (double) a[i];
            nb += (double) b[i] * (double) b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static String snippet(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        String t = text.replaceAll("\\s+", " ").trim();
        if (t.length() <= maxLen) {
            return t;
        }
        return t.substring(0, maxLen).trim() + "...";
    }

    private static String safeName(String original) {
        if (original == null || original.isBlank()) {
            return "fichier";
        }
        return original.replace("\\", "/").replaceAll(".*/", "");
    }

    private record Entry(RagChunk chunk, float[] embedding) {
    }

    private record Scored(Entry entry, double score) {
    }
}
