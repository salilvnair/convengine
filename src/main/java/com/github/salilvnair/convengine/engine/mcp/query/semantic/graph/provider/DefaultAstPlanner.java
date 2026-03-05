package com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.provider;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.graph.core.*;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.retrieval.core.RetrievalResult;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order()
@RequiredArgsConstructor
public class DefaultAstPlanner implements AstPlanner {

    private final ObjectProvider<List<JoinPathResolver>> joinResolversProvider;

    @Override
    public JoinPathPlan plan(RetrievalResult retrievalResult, EngineSession session) {
        List<JoinPathResolver> resolvers = joinResolversProvider.getIfAvailable(List::of);
        AnnotationAwareOrderComparator.sort(resolvers);
        for (JoinPathResolver resolver : resolvers) {
            if (resolver != null && resolver.supports(session)) {
                return resolver.resolve(retrievalResult, session);
            }
        }
        throw new IllegalStateException("No JoinPathResolver available.");
    }
}
