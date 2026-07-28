package com.github.salilvnair.convengine.engine.agent.executor.http;

public record HttpApiAuthSpec(
        HttpApiAuthType type,
        String headerName,
        String value,
        String prefix
) {
    public static HttpApiAuthSpec none() {
        return new HttpApiAuthSpec(HttpApiAuthType.NONE, null, null, null);
    }
}
