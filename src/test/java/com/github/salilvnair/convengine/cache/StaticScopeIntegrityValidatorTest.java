package com.github.salilvnair.convengine.cache;

import com.github.salilvnair.convengine.entity.CeContainerConfig;
import com.github.salilvnair.convengine.entity.CeIntent;
import com.github.salilvnair.convengine.entity.CeIntentClassifier;
import com.github.salilvnair.convengine.entity.CeMcpPlanner;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import com.github.salilvnair.convengine.entity.CeOutputSchema;
import com.github.salilvnair.convengine.entity.CePendingAction;
import com.github.salilvnair.convengine.entity.CePromptTemplate;
import com.github.salilvnair.convengine.entity.CeResponse;
import com.github.salilvnair.convengine.entity.CeRule;
import com.github.salilvnair.convengine.entity.CeVerbose;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaticScopeIntegrityValidatorTest {

    @Mock
    private StaticConfigurationCacheService staticCacheService;

    private StaticScopeIntegrityValidator validator;

    @BeforeEach
    void setUp() {
        validator = new StaticScopeIntegrityValidator(staticCacheService);
        stubEmptyTables();
    }

    @Test
    void validateOrThrow_skipsRowsThatReferenceDisabledIntents() {
        when(staticCacheService.getAllIntents()).thenReturn(List.of(
                CeIntent.builder().intentCode("FAQ").enabled(true).priority(1).build()
        ));

        CeRule danglingRule = new CeRule();
        danglingRule.setRuleId(1L);
        danglingRule.setIntentCode("PAYMENT");
        danglingRule.setStateCode("START");
        danglingRule.setEnabled(true);
        danglingRule.setPriority(1);
        when(staticCacheService.getAllRules()).thenReturn(List.of(danglingRule));

        when(staticCacheService.getAllIntentClassifiers()).thenReturn(List.of(
                CeIntentClassifier.builder()
                        .classifierId(10L)
                        .intentCode("PAYMENT")
                        .stateCode("START")
                        .ruleType("CONTAINS")
                        .pattern("pay")
                        .priority(1)
                        .enabled(true)
                        .build()
        ));

        assertDoesNotThrow(() -> validator.validateOrThrow());
    }

    @Test
    void validateOrThrow_stillFailsForBlankScopeValues() {
        when(staticCacheService.getAllIntents()).thenReturn(List.of(
                CeIntent.builder().intentCode("FAQ").enabled(true).priority(1).build()
        ));

        CeRule invalidRule = new CeRule();
        invalidRule.setRuleId(2L);
        invalidRule.setIntentCode(" ");
        invalidRule.setStateCode("START");
        invalidRule.setEnabled(true);
        invalidRule.setPriority(1);
        when(staticCacheService.getAllRules()).thenReturn(List.of(invalidRule));

        assertThrows(IllegalStateException.class, () -> validator.validateOrThrow());
    }

    private void stubEmptyTables() {
        when(staticCacheService.getAllIntents()).thenReturn(List.of());
        when(staticCacheService.getAllRules()).thenReturn(List.of());
        when(staticCacheService.getAllPendingActions()).thenReturn(List.of());
        when(staticCacheService.getAllPromptTemplates()).thenReturn(List.of());
        when(staticCacheService.getAllResponses()).thenReturn(List.of());
        when(staticCacheService.getAllContainerConfigs()).thenReturn(List.of());
        when(staticCacheService.getAllOutputSchemas()).thenReturn(List.of());
        when(staticCacheService.getAllMcpTools()).thenReturn(List.of());
        when(staticCacheService.getAllMcpPlanners()).thenReturn(List.of());
        when(staticCacheService.getAllVerboses()).thenReturn(List.of());
        when(staticCacheService.getAllIntentClassifiers()).thenReturn(List.of());
    }
}
