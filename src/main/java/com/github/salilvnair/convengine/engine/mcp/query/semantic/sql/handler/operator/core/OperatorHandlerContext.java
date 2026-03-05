package com.github.salilvnair.convengine.engine.mcp.query.semantic.sql.handler.operator.core;

import lombok.RequiredArgsConstructor;
import org.jooq.Param;
import org.jooq.impl.DSL;

import java.util.Map;
import java.util.function.Function;

@RequiredArgsConstructor
public class OperatorHandlerContext {
    private final Map<String, Object> params;
    private final int[] paramIdx;
    private final Function<Object, Object> normalizer;

    public String nextParamKey(Object rawValue) {
        String key = "p" + paramIdx[0]++;
        params.put(key, normalize(rawValue));
        return key;
    }

    public Param<Object> nextParam(Object rawValue) {
        String key = nextParamKey(rawValue);
        return DSL.param(key, params.get(key));
    }

    public Object normalize(Object rawValue) {
        return normalizer == null ? rawValue : normalizer.apply(rawValue);
    }
}

