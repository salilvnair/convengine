package com.github.salilvnair.convengine.template;

import com.github.salilvnair.convengine.engine.session.EngineSession;
import org.springframework.stereotype.Component;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.StringTemplateResolver;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ThymeleafTemplateRenderer {

    private static final Pattern LEGACY_VAR_PATTERN = Pattern.compile("\\{\\{\\s*([^{}]+?)\\s*}}");
    private static final Pattern LEGACY_EXPR_PATTERN = Pattern.compile("#\\{\\s*([^{}]+?)\\s*}");
    private static final Pattern SINGLE_BRACKET_EXPR_PATTERN = Pattern.compile("(?<!\\[)\\[\\s*\\$\\{\\s*([^{}]+?)\\s*}\\s*](?!])");

    private final SpringTemplateEngine templateEngine;

    public ThymeleafTemplateRenderer() {
        StringTemplateResolver resolver = new StringTemplateResolver();
        resolver.setTemplateMode(TemplateMode.TEXT);
        resolver.setCacheable(false);

        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        engine.setEnableSpringELCompiler(true);
        this.templateEngine = engine;
    }

    public String render(String template, EngineSession session, Map<String, Object> variables) {
        String raw = template == null ? "" : template;
        if (raw.isBlank()) {
            return raw;
        }
        Context context = new Context();
        context.setVariables(buildVariables(session, variables));
        String normalized = normalizeTemplate(raw);
        String rendered = templateEngine.process(normalized, context);
        return rendered == null ? "" : rendered;
    }

    public Map<String, Object> buildVariables(EngineSession session, Map<String, Object> variables) {
        Map<String, Object> merged = new LinkedHashMap<>();

        if (session != null) {
            Map<String, Object> sessionMap = session.sessionDict();
            Map<String, Object> safeInputParams = session.safeInputParams();
            Map<String, Object> rawInputParams = session.getInputParams();
            Map<String, Object> contextMap = session.contextDict();
            Map<String, Object> schemaMap = session.schemaJson();
            Map<String, Object> promptVars = session.promptTemplateVars();

            merged.put("session", sessionMap);
            merged.put("inputParams", safeInputParams);
            merged.put("rawInputParams", rawInputParams);
            merged.put("context", contextMap);
            merged.put("schema", schemaMap);
            merged.put("schemaJson", schemaMap);
            merged.put("promptVars", promptVars);
            merged.put("safeInputParams", safeInputParams);
            merged.put("intent", session.getIntent());
            merged.put("state", session.getState());
            merged.put("conversationId", session.getConversationId() == null ? null : String.valueOf(session.getConversationId()));

            putFlattened(merged, sessionMap, Set.of());
            putFlattened(merged, safeInputParams, Set.of("session", "context", "schema", "schemaJson", "inputParams"));
            putFlattened(merged, contextMap, Set.of());
            putFlattened(merged, schemaMap, Set.of());
            putFlattened(merged, promptVars, Set.of());
        }

        if (variables != null && !variables.isEmpty()) {
            merged.put("metadata", variables);
            for (Map.Entry<String, Object> entry : variables.entrySet()) {
                merged.put(entry.getKey(), entry.getValue());
            }
        } else {
            merged.putIfAbsent("metadata", Map.of());
        }

        return merged;
    }

    public boolean hasVariable(String expression, EngineSession session, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return false;
        }
        String path = expression.trim();
        if (!path.matches("[A-Za-z0-9_.$\\[\\]\"'-]+")) {
            return true;
        }
        String normalized = path
                .replaceAll("\\[['\"]?([A-Za-z0-9_.-]+)['\"]?]", ".$1")
                .replaceAll("^\\.+", "");

        Object current = buildVariables(session, variables);
        for (String segment : normalized.split("\\.")) {
            if (segment.isBlank()) {
                continue;
            }
            if (!(current instanceof Map<?, ?> map) || !map.containsKey(segment)) {
                return false;
            }
            current = map.get(segment);
        }
        return true;
    }

    public static Matcher legacyVarMatcher(String template) {
        return LEGACY_VAR_PATTERN.matcher(template == null ? "" : template);
    }

    private void putFlattened(Map<String, Object> target, Map<String, Object> source, Set<String> reservedKeys) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            if (entry.getKey() == null || reservedKeys.contains(entry.getKey())) {
                continue;
            }
            target.putIfAbsent(entry.getKey(), entry.getValue());
        }
    }

    private String normalizeTemplate(String template) {
        String normalized = replacePattern(template, LEGACY_VAR_PATTERN, "[[${$1}]]");
        normalized = replacePattern(normalized, LEGACY_EXPR_PATTERN, "[[${$1}]]");
        normalized = replacePattern(normalized, SINGLE_BRACKET_EXPR_PATTERN, "[[${$1}]]");
        return normalized;
    }

    private String replacePattern(String input, Pattern pattern, String replacement) {
        Matcher matcher = pattern.matcher(input);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String resolvedReplacement = replacement.replace("$1", matcher.group(1).trim());
            matcher.appendReplacement(out, Matcher.quoteReplacement(resolvedReplacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }
}
