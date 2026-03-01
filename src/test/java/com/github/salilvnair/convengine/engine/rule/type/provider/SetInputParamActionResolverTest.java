package com.github.salilvnair.convengine.engine.rule.type.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.audit.AuditService;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SetInputParamActionResolverTest {

    @Mock
    private AuditService auditService;

    private SetInputParamActionResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new SetInputParamActionResolver(auditService, new ObjectMapper());
    }

    @Test
    void resolvesBulkJsonObjectAssignments() {
        EngineSession session = newSession();
        CeRule rule = rule("{\"awaiting_confirmation\":true,\"confirmation_key\":\"LOAN_CONFIRM\"}");

        resolver.resolve(session, rule);

        assertEquals(true, session.getInputParams().get("awaiting_confirmation"));
        assertEquals("LOAN_CONFIRM", session.getInputParams().get("confirmation_key"));
        verify(auditService).audit(eq("SET_INPUT_PARAM"), eq(session.getConversationId()), anyMap());
    }

    @Test
    void resolvesExplicitKeyValueJsonArrayAssignments() {
        EngineSession session = newSession();
        CeRule rule = rule("[{\"key\":\"amount\",\"value\":350000},{\"key\":\"approved\",\"value\":true}]");

        resolver.resolve(session, rule);

        assertEquals(350000, session.getInputParams().get("amount"));
        assertEquals(true, session.getInputParams().get("approved"));
    }

    @Test
    void coercesScalarKeyValueAssignments() {
        EngineSession session = newSession();

        resolver.resolve(session, rule("amount:350000"));
        resolver.resolve(session, rule("approved:true"));
        resolver.resolve(session, rule("comment:null"));

        assertEquals(350000L, session.getInputParams().get("amount"));
        assertEquals(true, session.getInputParams().get("approved"));
        assertNull(session.getInputParams().get("comment"));
    }

    @Test
    void treatsBareKeyAsBooleanTrue() {
        EngineSession session = newSession();

        resolver.resolve(session, rule("awaiting_confirmation"));

        assertEquals(true, session.getInputParams().get("awaiting_confirmation"));
    }

    private EngineSession newSession() {
        return new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText("hello")
                .inputParams(Map.of())
                .build(), new ObjectMapper());
    }

    private CeRule rule(String actionValue) {
        CeRule rule = new CeRule();
        rule.setRuleId(99L);
        rule.setActionValue(actionValue);
        return rule;
    }
}
