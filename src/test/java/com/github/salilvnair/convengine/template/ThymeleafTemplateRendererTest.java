package com.github.salilvnair.convengine.template;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static com.github.salilvnair.convengine.support.TestConstants.CUSTOMER_ID_KEY;
import static com.github.salilvnair.convengine.support.TestConstants.USER_TEXT_HELLO;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ThymeleafTemplateRendererTest {

    private final ThymeleafTemplateRenderer renderer = new ThymeleafTemplateRenderer();

    @Test
    void rendersLegacyDoubleBraceVariables() {
        String rendered = renderer.render("User: {{user_input}}", null, Map.of("user_input", USER_TEXT_HELLO));

        assertEquals("User: " + USER_TEXT_HELLO, rendered);
    }

    @Test
    void rendersLegacyHashExpressions() {
        String rendered = renderer.render("#{standalone_query ?: user_input}", null,
                Map.of("user_input", "fallback", "standalone_query", "rewritten"));

        assertEquals("rewritten", rendered);
    }

    @Test
    void rendersSingleBracketThymeleafStyleExpressions() {
        String rendered = renderer.render("Customer: [${context.customerId}]", null,
                Map.of("context", Map.of(CUSTOMER_ID_KEY, "CUST-1001")));

        assertEquals("Customer: CUST-1001", rendered);
    }

    @Test
    void preservesSpecialCharactersInResolvedValues() {
        String value = "amt$3500 {approved}\\path";
        String rendered = renderer.render("Value: {{user_input}}", null, Map.of("user_input", value));

        assertEquals("Value: " + value, rendered);
    }
}
