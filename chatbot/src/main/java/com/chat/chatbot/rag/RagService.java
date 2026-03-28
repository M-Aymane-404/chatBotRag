package com.chat.chatbot.rag;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

@Service
public class RagService {
    private static final Pattern WORD_SPLIT = Pattern.compile("\\W+");
    private static final double BM25_K1 = 1.5;
    private static final double BM25_B = 0.75;

    private final Map<String, SessionIndex> indexBySession = new ConcurrentHashMap<>();

    public void index(String sessionId, List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            return;
        }
        SessionIndex sessionIndex = indexBySession.computeIfAbsent(sessionId, ignored -> new SessionIndex());
        for (MultipartFile file : files) {
            if (file == null || file.isEmpty()) {
                continue;
            }
            String sourceName = safeName(file.getOriginalFilename());
            try {
                indexFile(sessionIndex, sessionId, file, sourceName);
            } catch (IOException e) {
                throw new IllegalArgumentException("Impossible de lire le fichier: " + sourceName, e);
            }
        }
    }

    public List<RagSource> retrieveSources(String sessionId, String question, int k) {
        SessionIndex sessionIndex = indexBySession.get(sessionId);
        if (sessionIndex == null || sessionIndex.entries.isEmpty()) {
            return List.of();
        }
        List<String> queryTerms = terms(question);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        int n = sessionIndex.entries.size();
        double avgDocLen = sessionIndex.avgDocLen();

        List<ScoredEntry> scored = sessionIndex.entries.stream()
                .map(e -> new ScoredEntry(e, bm25Score(e, sessionIndex.docFreq, n, avgDocLen, queryTerms)))
                .filter(s -> s.score > 0)
                .sorted(Comparator.<ScoredEntry>comparingDouble(s -> s.score).reversed())
                .limit(k)
                .toList();

        if (scored.isEmpty()) {
            return List.of();
        }

        List<RagSource> out = new ArrayList<>(scored.size());
        for (int i = 0; i < scored.size(); i++) {
            ScoredEntry s = scored.get(i);
            RagChunk c = s.entry.chunk;
            out.add(new RagSource(
                    i + 1,
                    c.sourceName(),
                    c.page(),
                    s.score,
                    snippet(c.text(), 500)
            ));
        }
        return out;
    }

    public List<RagSource> retrieveAnySources(String sessionId, int k) {
        SessionIndex sessionIndex = indexBySession.get(sessionId);
        if (sessionIndex == null || sessionIndex.entries.isEmpty()) {
            return List.of();
        }
        int limit = Math.min(k, sessionIndex.entries.size());
        List<RagSource> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            RagChunk c = sessionIndex.entries.get(i).chunk;
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

    private void indexFile(SessionIndex sessionIndex, String sessionId, MultipartFile file, String sourceName) throws IOException {
        String name = safeName(file.getOriginalFilename()).toLowerCase();
        if (name.endsWith(".pdf")) {
            try (PDDocument document = Loader.loadPDF(file.getBytes())) {
                PDFTextStripper stripper = new PDFTextStripper();
                int pages = document.getNumberOfPages();
                for (int p = 1; p <= pages; p++) {
                    stripper.setStartPage(p);
                    stripper.setEndPage(p);
                    String pageText = stripper.getText(document);
                    indexText(sessionIndex, sessionId, sourceName, p, pageText);
                }
            }
            return;
        }
        String text = new String(file.getBytes(), StandardCharsets.UTF_8);
        indexText(sessionIndex, sessionId, sourceName, null, text);
    }

    private void indexText(SessionIndex sessionIndex, String sessionId, String sourceName, Integer page, String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String chunkText : chunk(text, 1200, 200)) {
            RagChunk chunk = new RagChunk(UUID.randomUUID().toString(), sessionId, sourceName, page, chunkText);
            ChunkEntry entry = toEntry(chunk);
            if (entry.docLen == 0) {
                continue;
            }
            sessionIndex.add(entry);
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

    private static List<String> terms(String question) {
        if (question == null) {
            return List.of();
        }
        String q = question.toLowerCase();
        String[] raw = WORD_SPLIT.split(q);
        List<String> terms = new ArrayList<>(raw.length);
        for (String t : raw) {
            if (t == null) {
                continue;
            }
            String term = t.trim();
            if (term.length() >= 3) {
                terms.add(term);
            }
        }
        return terms;
    }

    private static ChunkEntry toEntry(RagChunk chunk) {
        List<String> tokens = terms(chunk.text());
        Map<String, Integer> tf = new HashMap<>();
        for (String t : tokens) {
            tf.merge(t, 1, Integer::sum);
        }
        return new ChunkEntry(chunk, tf, tokens.size());
    }

    private static double bm25Score(ChunkEntry entry, Map<String, Integer> docFreq, int n, double avgDocLen, List<String> queryTerms) {
        if (avgDocLen <= 0) {
            return 0;
        }
        double score = 0;
        int docLen = entry.docLen;
        for (String term : queryTerms) {
            Integer df = docFreq.get(term);
            if (df == null || df == 0) {
                continue;
            }
            Integer tf = entry.tf.get(term);
            if (tf == null || tf == 0) {
                continue;
            }
            double idf = Math.log(1.0 + ((n - df + 0.5) / (df + 0.5)));
            double denom = tf + BM25_K1 * (1.0 - BM25_B + BM25_B * (docLen / avgDocLen));
            score += idf * (tf * (BM25_K1 + 1.0)) / denom;
        }
        return score;
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

    private static class SessionIndex {
        private final List<ChunkEntry> entries = new ArrayList<>();
        private final Map<String, Integer> docFreq = new HashMap<>();
        private long totalDocLen = 0;

        void add(ChunkEntry entry) {
            entries.add(entry);
            totalDocLen += entry.docLen;
            for (String term : entry.tf.keySet()) {
                docFreq.merge(term, 1, Integer::sum);
            }
        }

        double avgDocLen() {
            if (entries.isEmpty()) {
                return 0;
            }
            return (double) totalDocLen / (double) entries.size();
        }
    }

    private record ChunkEntry(RagChunk chunk, Map<String, Integer> tf, int docLen) {
    }

    private record ScoredEntry(ChunkEntry entry, double score) {
    }
}
