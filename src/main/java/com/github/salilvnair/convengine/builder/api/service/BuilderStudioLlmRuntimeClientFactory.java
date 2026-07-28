package com.github.salilvnair.convengine.builder.api.service;

import com.github.salilvnair.convengine.llm.core.LlmClient;

import java.util.Map;

/**
 * Factory that creates an ephemeral {@link LlmClient} for a specific LLM
 * provider at runtime — with model / temperature overrides supplied by the
 * Builder Studio UI.
 *
 * <p>Each provider (openai, lmstudio, …) supplies one implementation.
 * The runtime service discovers all factories via Spring injection and
 * indexes them by {@link #providerKey()}.
 *
 * <p>Implementations should delegate HTTP calls through the existing
 * {@code RestWebServiceFacade} / handler / delegate infrastructure from
 * the {@code api-processor} package rather than using raw RestTemplate.
 */
public interface BuilderStudioLlmRuntimeClientFactory {

    /** Unique provider identifier, e.g. {@code "openai"}, {@code "lmstudio"}. */
    String providerKey();

    /**
     * Create a short-lived {@link LlmClient} configured for the given
     * runtime overrides.
     *
     * @param runtimeConfig provider-specific config map — at minimum contains
     *                      {@code model} (String), {@code temperature} (Double),
     *                      {@code apiKey} (String, nullable), {@code baseUrl} (String).
     * @return a ready-to-use LlmClient
     */
    LlmClient create(Map<String, Object> runtimeConfig);
}
