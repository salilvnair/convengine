package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeRule;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static com.github.salilvnair.convengine.support.TestConstants.REWRITTEN_QUERY;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_HELLO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SetDialogueActActionResolverTest {

    @Mock
    private AuditService auditService;

    private SetDialogueActActionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SetDialogueActActionResolver(auditService, new ObjectMapper());
    }

    @Test
    void resolvesJsonOverrideAndSyncsStandaloneQuery() {
        EngineSession session = newSession();
        CeRule rule = new CeRule();
        rule.setRuleId(501L);
        rule.setActionValue("""
                {"dialogueAct":"EDIT","confidence":0.99,"source":"POST_DIALOGUE_ACT_RULE","standaloneQuery":"%s"}
                """.formatted(REWRITTEN_QUERY));

        resolver.resolve(session, rule);

        assertEquals("EDIT", session.inputParamAsString(ConvEngineInputParamKey.DIALOGUE_ACT));
        assertEquals("0.99", session.inputParamAsString(ConvEngineInputParamKey.DIALOGUE_ACT_CONFIDENCE));
        assertEquals("POST_DIALOGUE_ACT_RULE", session.inputParamAsString(ConvEngineInputParamKey.DIALOGUE_ACT_SOURCE));
        assertEquals(REWRITTEN_QUERY, session.getStandaloneQuery());
        assertEquals(REWRITTEN_QUERY, session.getResolvedUserInput());
        verify(auditService).audit(eq("SET_DIALOGUE_ACT"), eq(session.getConversationId()), anyMap());
    }

    private EngineSession newSession() {
        return new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(USER_TEXT_HELLO)
                .inputParams(Map.of())
                .build(), new ObjectMapper());
    }
}
