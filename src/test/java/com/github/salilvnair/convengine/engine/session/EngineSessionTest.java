package com.github.salilvnair.convengine.engine.session;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.engine.constants.ConvEngineInputParamKey;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static com.github.salilvnair.convengine.support.TestConstants.CUSTOMER_ID;
import static com.github.salilvnair.convengine.support.TestConstants.CUSTOMER_ID_KEY;
import static com.github.salilvnair.convengine.support.TestConstants.REWRITTEN_QUERY;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_FOLLOW_UP;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_HELLO;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_NEED_LOAN;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EngineSessionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void constructorSeedsResolvedUserInputFromUserText() {
        EngineSession session = new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(USER_TEXT_NEED_LOAN)
                .build(), mapper);

        assertEquals(USER_TEXT_NEED_LOAN, session.getResolvedUserInput());
        assertNull(session.getStandaloneQuery());
        assertEquals(USER_TEXT_NEED_LOAN, session.getInputParams().get(ConvEngineInputParamKey.RESOLVED_USER_INPUT));
        assertNull(session.getInputParams().get(ConvEngineInputParamKey.STANDALONE_QUERY));
    }

    @Test
    void standaloneQueryOverridesResolvedUserInput() {
        EngineSession session = new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(USER_TEXT_FOLLOW_UP)
                .build(), mapper);

        session.setStandaloneQuery(REWRITTEN_QUERY);

        assertEquals(REWRITTEN_QUERY, session.getResolvedUserInput());
        assertEquals(REWRITTEN_QUERY, session.getInputParams().get(ConvEngineInputParamKey.STANDALONE_QUERY));
        assertEquals(REWRITTEN_QUERY, session.getInputParams().get(ConvEngineInputParamKey.RESOLVED_USER_INPUT));
    }

    @Test
    void safeInputParamsDropsReservedPayloadsButKeepsUserValues() {
        EngineSession session = new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText(USER_TEXT_HELLO)
                .inputParams(Map.of(
                        ConvEngineInputParamKey.CONTEXT, Map.of(CUSTOMER_ID_KEY, CUSTOMER_ID),
                        CUSTOMER_ID_KEY, CUSTOMER_ID))
                .build(), mapper);

        Map<String, Object> safe = session.safeInputParams();

        assertEquals(CUSTOMER_ID, safe.get(CUSTOMER_ID_KEY));
        assertEquals(USER_TEXT_HELLO, safe.get(ConvEngineInputParamKey.RESOLVED_USER_INPUT));
        assertNull(safe.get(ConvEngineInputParamKey.CONTEXT));
    }
}
