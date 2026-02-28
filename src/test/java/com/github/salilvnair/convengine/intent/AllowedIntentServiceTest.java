package com.github.salilvnair.convengine.intent;

import com.github.salilvnair.convengine.cache.StaticConfigurationCacheService;
import com.github.salilvnair.convengine.entity.CeIntent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AllowedIntentServiceTest {

    @Mock
    private StaticConfigurationCacheService cacheService;

    private AllowedIntentService service;

    @BeforeEach
    void setUp() {
        service = new AllowedIntentService(cacheService);
    }

    @Test
    void allowedIntentsFiltersBlankAndUnknown() {
        when(cacheService.findEnabledIntents()).thenReturn(List.of(
                CeIntent.builder().intentCode("LOAN_APPLICATION").description("Loan").priority(1).enabled(true).build(),
                CeIntent.builder().intentCode("UNKNOWN").priority(2).enabled(true).build(),
                CeIntent.builder().intentCode(" ").priority(3).enabled(true).build()
        ));

        List<AllowedIntent> intents = service.allowedIntents();

        assertEquals(1, intents.size());
        assertEquals("LOAN_APPLICATION", intents.getFirst().code());
    }

    @Test
    void isAllowedUsesFilteredIntentList() {
        when(cacheService.findEnabledIntents()).thenReturn(List.of(
                CeIntent.builder().intentCode("LOAN_APPLICATION").description("Loan").priority(1).enabled(true).build()
        ));

        assertTrue(service.isAllowed("loan_application"));
        assertFalse(service.isAllowed("UNKNOWN"));
        assertFalse(service.isAllowed("PAYMENT"));
    }
}
