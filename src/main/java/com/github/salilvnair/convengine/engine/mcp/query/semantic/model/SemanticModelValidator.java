package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
public class SemanticModelValidator {

    public List<String> validate(SemanticModel model) {
        List<String> errors = new ArrayList<>();
        if (model == null) {
            errors.add("semantic model is null");
            return errors;
        }
        Map<String, SemanticEntity> entities = model.entities();
        Map<String, SemanticTable> tables = model.tables();
        if (entities == null || entities.isEmpty()) {
            errors.add("semantic model has no entities");
            return errors;
        }
        for (Map.Entry<String, SemanticEntity> entry : entities.entrySet()) {
            String entityName = entry.getKey();
            SemanticEntity entity = entry.getValue();
            if (entity == null || entity.tables() == null || entity.tables().primary() == null || entity.tables().primary().isBlank()) {
                errors.add("entity " + entityName + " is missing tables.primary");
                continue;
            }
            if (tables != null && !tables.isEmpty()) {
                if (!tables.containsKey(entity.tables().primary())) {
                    errors.add("entity " + entityName + " primary table not found in tables: " + entity.tables().primary());
                }
                for (String rel : entity.tables().related()) {
                    if (!tables.containsKey(rel)) {
                        errors.add("entity " + entityName + " related table not found in tables: " + rel);
                    }
                }
            }
        }
        return errors;
    }
}
