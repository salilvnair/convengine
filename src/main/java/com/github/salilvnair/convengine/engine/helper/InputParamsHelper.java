package com.github.salilvnair.convengine.engine.helper;

import com.github.salilvnair.convengine.util.JsonUtil;

import java.util.LinkedHashMap;
import java.util.Map;

public final class InputParamsHelper {

    private InputParamsHelper() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> deepCopy(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            String json = JsonUtil.toJson(source);
            Map<String, Object> copied = JsonUtil.fromJson(json, LinkedHashMap.class);
            return copied == null ? new LinkedHashMap<>() : copied;
        }
        catch (Exception ignored) {
            return new LinkedHashMap<>(source);
        }
    }
}
