package com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.*;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order()
public class NoopSemanticVectorSearchAdapter implements SemanticVectorSearchAdapter {
}
