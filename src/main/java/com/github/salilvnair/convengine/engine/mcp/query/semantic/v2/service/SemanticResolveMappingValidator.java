package com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.service;

import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.CanonicalIntent;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.ResolvedSemanticPlan;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.v2.contract.SemanticUnresolvedItem;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class SemanticResolveMappingValidator {

    public List<SemanticUnresolvedItem> validateCanonicalIntent(CanonicalIntent canonicalIntent) {
        List<SemanticUnresolvedItem> unresolved = new ArrayList<>();
        if (canonicalIntent == null) {
            unresolved.add(new SemanticUnresolvedItem("CANONICAL_INTENT", "canonicalIntent", "Canonical intent is required."));
            return unresolved;
        }
        if (canonicalIntent.intent() == null || canonicalIntent.intent().isBlank()) {
            unresolved.add(new SemanticUnresolvedItem("INTENT", "intent", "Intent is missing."));
        }
        if (canonicalIntent.entity() == null || canonicalIntent.entity().isBlank()) {
            unresolved.add(new SemanticUnresolvedItem("ENTITY", "entity", "Entity is missing."));
        }
        return unresolved;
    }

    public List<SemanticUnresolvedItem> validateResolvedPlan(ResolvedSemanticPlan resolvedPlan) {
        List<SemanticUnresolvedItem> unresolved = new ArrayList<>();
        if (resolvedPlan == null) {
            unresolved.add(new SemanticUnresolvedItem("RESOLVED_PLAN", "resolvedPlan", "Resolved plan is missing."));
            return unresolved;
        }
        if (resolvedPlan.baseTable() == null || resolvedPlan.baseTable().isBlank()) {
            unresolved.add(new SemanticUnresolvedItem("BASE_TABLE", "baseTable", "Base table could not be resolved."));
        }
        if (resolvedPlan.select() != null) {
            resolvedPlan.select().forEach(item -> {
                if (item == null || item.column() == null || item.column().isBlank()) {
                    unresolved.add(new SemanticUnresolvedItem("SELECT", item == null ? "unknown" : item.field(), "Select field is missing column mapping."));
                }
            });
        }
        if (resolvedPlan.filters() != null) {
            resolvedPlan.filters().forEach(filter -> {
                if (filter == null || filter.column() == null || filter.column().isBlank()) {
                    unresolved.add(new SemanticUnresolvedItem("FILTER", filter == null ? "unknown" : filter.field(), "Filter field is missing column mapping."));
                }
            });
        }
        return unresolved;
    }
}
