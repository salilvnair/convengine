package com.github.salilvnair.convengine.engine.helper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.salilvnair.convengine.engine.context.EngineContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionContextHelperTest {

    private final SessionContextHelper helper = new SessionContextHelper();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void readRootReturnsEmptyObjectForBlankContext() {
        EngineSession session = newSession();

        ObjectNode root = helper.readRoot(session);

        assertNotNull(root);
        assertTrue(root.isEmpty());
    }

    @Test
    void ensureObjectCreatesAndReusesNestedNode() {
        ObjectNode root = mapper.createObjectNode();

        ObjectNode created = helper.ensureObject(root, "pending_action");
        created.put("status", "OPEN");
        ObjectNode reused = helper.ensureObject(root, "pending_action");

        assertEquals("OPEN", reused.get("status").asText());
        assertEquals(created, reused);
    }

    @Test
    void writeRootPersistsJsonBackToSession() {
        EngineSession session = newSession();
        ObjectNode root = mapper.createObjectNode();
        root.put("customerId", "1234");

        helper.writeRoot(session, root);

        assertEquals("{\"customerId\":\"1234\"}", session.getContextJson());
    }

    private EngineSession newSession() {
        return new EngineSession(EngineContext.builder()
                .conversationId(UUID.randomUUID().toString())
                .userText("hello")
                .build(), mapper);
    }
}
