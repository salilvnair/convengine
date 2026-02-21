package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.constants.ConvEnginePayloadKey;
import com.github.salilvnair.convengine.engine.helper.CeConfigResolver;
import com.github.salilvnair.convengine.engine.history.core.ConversationHistoryProvider;
import com.github.salilvnair.convengine.engine.history.model.ConversationTurn;
import com.github.salilvnair.convengine.engine.response.type.factory.ResponseTypeResolverFactory;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.engine.type.OutputType;
import com.github.salilvnair.convengine.engine.type.ResponseType;
import com.github.salilvnair.convengine.model.JsonPayload;
import com.github.salilvnair.convengine.model.PromptTemplate;
import com.github.salilvnair.convengine.model.ResponseTemplate;
import com.github.salilvnair.convengine.model.TextPayload;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

@RequiredArgsConstructor
@Component
public class AgentIntentCollisionResolver implements IntentCollisionResolver {
    private String SYSTEM_PROMPT;
    private String USER_PROMPT;
    private String DERIVATION_HINT;
    private final CeConfigResolver configResolver;
    private final ConversationHistoryProvider historyProvider;
    private final ResponseTypeResolverFactory typeFactory;
    private final AuditService audit;

    @PostConstruct
    public void init() {
        SYSTEM_PROMPT = configResolver.resolveString(this, "SYSTEM_PROMPT", """
                You are a workflow assistant handling ambiguous intent collisions.
                Use followups first when present.
                Ask one concise disambiguation question.
                If followups is empty, ask user to choose from top intents.
                
                """);
        USER_PROMPT = configResolver.resolveString(this, "USER_PROMPT", """
                User message:
                {{user_input}}

                Followups:
                {{followups}}

                Top intent scores:
                {{intent_top3}}

                Session:
                {{session}}

                Context:
                {{context}}
                """);
        DERIVATION_HINT = configResolver.resolveString(this, "DERIVATION_HINT", """
                When multiple intents have similar scores, derive a new intent to disambiguate.
                Consider followup questions, top intent scores, and conversation history.
                """);
    }

    @Override
    public void resolve(EngineSession session) {
        List<ConversationTurn> conversationTurns = historyProvider.lastTurns(session.getConversationId(), 10);
        session.setConversationHistory(conversationTurns);
        typeFactory
                .get(ResponseType.DERIVED.name())
                .resolve(session, PromptTemplate.initFrom(SYSTEM_PROMPT, USER_PROMPT, OutputType.TEXT.name(), "templateFromCeConfig (AgentIntentCollisionResolver)"), ResponseTemplate.initFrom(DERIVATION_HINT, OutputType.TEXT.name()));
        Object payloadValue = switch (session.getPayload()) {
            case TextPayload(String text) -> text;
            case JsonPayload(String json) -> json;
            case null -> null;
        };

        Map<String, Object> outputPayload = new LinkedHashMap<>();
        outputPayload.put(ConvEnginePayloadKey.OUTPUT, payloadValue);
        outputPayload.put(ConvEnginePayloadKey.INTENT, session.getIntent());
        outputPayload.put(ConvEnginePayloadKey.STATE, session.getState());
        outputPayload.put(ConvEnginePayloadKey.CONTEXT, session.contextDict());
        outputPayload.put(ConvEnginePayloadKey.SCHEMA_JSON, session.schemaJson());
        audit.audit(ConvEngineAuditStage.INTENT_COLLISION_RESOLVED, session.getConversationId(), outputPayload);
    }
}
