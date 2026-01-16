package com.github.salilvnair.convengine.prompt.renderer;

import com.github.salilvnair.convengine.engine.exception.ConversationEngineErrorCode;
import com.github.salilvnair.convengine.engine.exception.ConversationEngineException;
import com.github.salilvnair.convengine.prompt.annotation.PromptVar;
import com.github.salilvnair.convengine.prompt.context.PromptTemplateContext;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

public class PromptTemplateRenderer {

    public String render(String template, PromptTemplateContext ctx) {

        String out = template == null ? "" : template;

        Map<String, String> resolvedVars = extractVars(ctx);

        for (Map.Entry<String, String> e : resolvedVars.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }

        if (out.contains("{{")) {
            throw new ConversationEngineException(
                    ConversationEngineErrorCode.UNRESOLVED_PROMPT_VARIABLE
            );
        }

        return out;
    }

    private Map<String, String> extractVars(PromptTemplateContext ctx) {

        Map<String, String> vars = new HashMap<>();

        if (ctx == null) {
            return vars;
        }

        for (Field field : ctx.getClass().getDeclaredFields()) {

            PromptVar ann = field.getAnnotation(PromptVar.class);
            if (ann == null) continue;

            field.setAccessible(true);

            try {
                Object value = field.get(ctx);
                String safeValue = value == null ? "" : value.toString();

                for (String alias : ann.value()) {
                    vars.put(alias, safeValue);
                }

            } catch (IllegalAccessException e) {
                throw new ConversationEngineException(
                        ConversationEngineErrorCode.PROMPT_VAR_ACCESS_FAILED,
                        "Failed to read @PromptVar field: " + field.getName() + ", e:" + e.getLocalizedMessage()
                );
            }
        }

        return vars;
    }
}
