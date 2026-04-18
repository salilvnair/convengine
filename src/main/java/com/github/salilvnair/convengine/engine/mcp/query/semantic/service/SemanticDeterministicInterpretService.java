package com.github.salilvnair.convengine.engine.mcp.query.semantic.service;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticInterpretRequest;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticInterpretResponse;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.contract.SemanticToolMeta;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticEntity;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModel;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.model.SemanticModelRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

// Minimal, LLM-free interpret stage used by the semantic debug page.
//
// Strategy: tokenize the question, then score every entity in the active
// SemanticModel by how many of its name tokens and synonym tokens appear
// in the question. Highest score wins; ties break alphabetically so the
// output is stable. Produces a CanonicalIntent shaped like the LLM path
// so SemanticLlmQueryService can compile SQL from it unchanged.
//
// This is intentionally dumb — it is NOT a replacement for the LLM
// interpreter. The debug UI uses it to answer "does the entity catalog
// already contain enough signal to route this question without calling
// the LLM?" and to exercise the compile stage in isolation.
@Slf4j
@Service
@RequiredArgsConstructor
public class SemanticDeterministicInterpretService {

    private final SemanticModelRegistry modelRegistry;

    public SemanticInterpretResponse interpret(SemanticInterpretRequest request) {
        String question = request == null ? null : request.question();
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);

        SemanticModel model = modelRegistry.getModel();
        Map<String, SemanticEntity> entities = model.entities();

        String bestEntity = null;
        int bestScore = 0;
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (Map.Entry<String, SemanticEntity> entry : entities.entrySet()) {
            int score = score(normalized, entry.getKey(), entry.getValue());
            scores.put(entry.getKey(), score);
            if (score > bestScore || (score == bestScore && bestEntity != null && entry.getKey().compareTo(bestEntity) < 0)) {
                bestScore = score;
                bestEntity = entry.getKey();
            }
        }

        boolean matched = bestEntity != null && bestScore > 0;
        CanonicalIntent intent = matched
                ? new CanonicalIntent("LIST", bestEntity, "LIST_REQUESTS", List.of(), null, List.of(), 50)
                : new CanonicalIntent("UNKNOWN", null, null, List.of(), null, List.of(), null);

        SemanticToolMeta meta = new SemanticToolMeta(
                "semantic.interpret.deterministic",
                "1.0",
                matched ? Math.min(1.0, bestScore / 5.0) : 0.0,
                !matched,
                matched ? null : "No entity in the semantic catalog matched the question.",
                List.of(),
                matched,
                !matched,
                matched ? null : "Deterministic interpret could not match any entity."
        );

        Map<String, Object> trace = new LinkedHashMap<>();
        trace.put("mode", "java");
        trace.put("bestEntity", bestEntity);
        trace.put("bestScore", bestScore);
        trace.put("scores", scores);
        trace.put("entityCount", entities.size());

        return new SemanticInterpretResponse(meta, question, intent, trace);
    }

    private static int score(String question, String name, SemanticEntity entity) {
        if (question.isEmpty()) return 0;
        int score = 0;
        score += tokenHits(question, name);
        for (String syn : entity.synonyms()) {
            score += tokenHits(question, syn);
        }
        return score;
    }

    private static int tokenHits(String haystack, String phrase) {
        if (phrase == null || phrase.isBlank()) return 0;
        String lc = phrase.toLowerCase(Locale.ROOT);
        if (haystack.contains(lc)) return 2; // whole-phrase match worth more
        int hits = 0;
        for (String tok : lc.split("[^a-z0-9]+")) {
            if (tok.length() < 3) continue;
            if (containsWord(haystack, tok)) hits++;
        }
        return hits;
    }

    private static boolean containsWord(String haystack, String word) {
        int idx = haystack.indexOf(word);
        while (idx >= 0) {
            boolean leftOk = idx == 0 || !Character.isLetterOrDigit(haystack.charAt(idx - 1));
            int end = idx + word.length();
            boolean rightOk = end == haystack.length() || !Character.isLetterOrDigit(haystack.charAt(end));
            if (leftOk && rightOk) return true;
            idx = haystack.indexOf(word, idx + 1);
        }
        return false;
    }
}
