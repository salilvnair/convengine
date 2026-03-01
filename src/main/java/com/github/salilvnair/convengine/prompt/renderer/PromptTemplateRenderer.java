package com.github.salilvnair.convengine.prompt.renderer;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.prompt.annotation.PromptVar;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.template.ThymeleafTemplateRenderer;
import com.github.salilvnair.convengine.util.JsonUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;

@RequiredArgsConstructor
@Component
public class PromptTemplateRenderer {

    private final AuditService audit;
    private final ThymeleafTemplateRenderer templateRenderer;

    public String render(String template, PromptTemplateContext ctx) {

        String out = template == null ? "" : template;

        Map<String, Object> rawVars = extractRawVars(ctx);
        Map<String, String> resolvedVars = stringifyVars(rawVars);
        validateRequiredLegacyVars(template, ctx, rawVars, resolvedVars);

        out = templateRenderer.render(out, ctx == null ? null : ctx.getSession(), rawVars);

        Map<String, Object> auditData = new LinkedHashMap<>();
        auditData.put(ConvEnginePayloadKey.RENDERED_TEMPLATE, out);
        auditData.put(ConvEnginePayloadKey.PROMPT_VARS, resolvedVars);
        if (ctx != null && ctx.getSession() != null && ctx.getSession().getConversationId() != null) {
            audit.audit(ConvEngineAuditStage.PROMPT_RENDERING, ctx.getSession().getConversationId(), auditData);
        }

        if (out.contains("{{")) {
            Map<String, Object> meta = new LinkedHashMap<>();
            if (ctx != null && ctx.getTemplateName() != null) {
                meta.put(ConvEnginePayloadKey.PROMPT_SELECTED, ctx.getTemplateName());
            }
            if (ctx != null && ctx.getSystemPrompt() != null) {
                meta.put(ConvEnginePayloadKey.SYSTEM_PROMPT, ctx.getSystemPrompt());
            }
            if (ctx != null && ctx.getUserPrompt() != null) {
                meta.put(ConvEnginePayloadKey.USER_PROMPT, ctx.getUserPrompt());
            }
            if (ctx != null && ctx.getSession() != null) {
                meta.put(ConvEnginePayloadKey.SESSION, ctx.getSession().eject());
            }
            meta.put(ConvEnginePayloadKey.PROMPT_VARS, resolvedVars);

            throw new ConversationEngineException(
                    ConversationEngineErrorCode.UNRESOLVED_PROMPT_VARIABLE).withMetaData(meta);
        }

        return out;
    }

    private Map<String, Object> extractRawVars(PromptTemplateContext ctx) {

        Map<String, Object> vars = new HashMap<>();

        if (ctx == null) {
            return vars;
        }

        for (Field field : ctx.getClass().getDeclaredFields()) {

            PromptVar ann = field.getAnnotation(PromptVar.class);
            if (ann == null)
                continue;

            field.setAccessible(true);

            try {
                Object value = field.get(ctx);
                for (String alias : ann.value()) {
                    vars.put(alias, value);
                }

            } catch (IllegalAccessException e) {
                throw new ConversationEngineException(
                        ConversationEngineErrorCode.PROMPT_VAR_ACCESS_FAILED,
                        "Failed to read @PromptVar field: " + field.getName() + ", e:" + e.getLocalizedMessage());
            }
        }

        if (ctx.getExtra() != null) {
            vars.putAll(ctx.getExtra());
        }

        return vars;
    }

    private void validateRequiredLegacyVars(String template, PromptTemplateContext ctx, Map<String, Object> rawVars,
            Map<String, String> resolvedVars) {
        if (template == null || template.isBlank()) {
            return;
        }
        Set<String> missing = new LinkedHashSet<>();
        Matcher matcher = ThymeleafTemplateRenderer.legacyVarMatcher(template);
        while (matcher.find()) {
            String expression = matcher.group(1) == null ? "" : matcher.group(1).trim();
            if (expression.isBlank()) {
                continue;
            }
            if (!templateRenderer.hasVariable(expression, ctx == null ? null : ctx.getSession(), rawVars)) {
                missing.add(expression);
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        if (ctx != null && ctx.getTemplateName() != null) {
            meta.put(ConvEnginePayloadKey.PROMPT_SELECTED, ctx.getTemplateName());
        }
        if (ctx != null && ctx.getSystemPrompt() != null) {
            meta.put(ConvEnginePayloadKey.SYSTEM_PROMPT, ctx.getSystemPrompt());
        }
        if (ctx != null && ctx.getUserPrompt() != null) {
            meta.put(ConvEnginePayloadKey.USER_PROMPT, ctx.getUserPrompt());
        }
        if (ctx != null && ctx.getSession() != null) {
            meta.put(ConvEnginePayloadKey.SESSION, ctx.getSession().eject());
        }
        meta.put(ConvEnginePayloadKey.PROMPT_VARS, resolvedVars);
        meta.put("missingPromptVars", missing);
        throw new ConversationEngineException(
                ConversationEngineErrorCode.UNRESOLVED_PROMPT_VARIABLE).withMetaData(meta);
    }

    private Map<String, String> stringifyVars(Map<String, Object> rawVars) {
        Map<String, String> vars = new HashMap<>();
        for (Map.Entry<String, Object> entry : rawVars.entrySet()) {
            vars.put(entry.getKey(), toSafeString(entry.getValue()));
        }
        return vars;
    }

    private String toSafeString(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof String s) {
            return s;
        }
        return JsonUtil.toJson(value);
    }
}
