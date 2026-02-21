package com.github.salilvnair.convengine.prompt.renderer;

import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.prompt.annotation.PromptVar;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;
import com.github.salilvnair.convengine.util.JsonUtil;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class PromptTemplateRenderer {

    public String render(String template, PromptTemplateContext ctx) {

        String out = template == null ? "" : template;

        Map<String, String> resolvedVars = extractVars(ctx);

        for (Map.Entry<String, String> e : resolvedVars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }

        if (out.contains("{{")) {
            Map<String, Object> meta = new LinkedHashMap<>();
            if (ctx != null) {
                if (ctx.getTemplateName() != null) {
                    meta.put(ConvEnginePayloadKey.PROMPT_SELECTED, ctx.getTemplateName());
                }
                if (ctx.getSystemPrompt() != null) {
                    meta.put(ConvEnginePayloadKey.SYSTEM_PROMPT, ctx.getSystemPrompt());
                }
                if (ctx.getUserPrompt() != null) {
                    meta.put(ConvEnginePayloadKey.USER_PROMPT, ctx.getUserPrompt());
                }
                meta.put(ConvEnginePayloadKey.SESSION, ctx.getSession().eject());
            }
            meta.put(ConvEnginePayloadKey.PROMPT_VARS, resolvedVars);

            throw new ConversationEngineException(
                    ConversationEngineErrorCode.UNRESOLVED_PROMPT_VARIABLE).withMetaData(meta);
        }

        return out;
    }

    private Map<String, String> extractVars(PromptTemplateContext ctx) {

        Map<String, String> vars = new HashMap<>();

        if (ctx == null) {
            return vars;
        }

        if (ctx.getExtra() != null) {
            for (Map.Entry<String, Object> entry : ctx.getExtra().entrySet()) {
                vars.put(entry.getKey(), toSafeString(entry.getValue()));
            }
        }

        for (Field field : ctx.getClass().getDeclaredFields()) {

            PromptVar ann = field.getAnnotation(PromptVar.class);
            if (ann == null)
                continue;

            field.setAccessible(true);

            try {
                Object value = field.get(ctx);
                String safeValue = toSafeString(value);

                for (String alias : ann.value()) {
                    vars.put(alias, safeValue);
                }

            } catch (IllegalAccessException e) {
                throw new ConversationEngineException(
                        ConversationEngineErrorCode.PROMPT_VAR_ACCESS_FAILED,
                        "Failed to read @PromptVar field: " + field.getName() + ", e:" + e.getLocalizedMessage());
            }
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
