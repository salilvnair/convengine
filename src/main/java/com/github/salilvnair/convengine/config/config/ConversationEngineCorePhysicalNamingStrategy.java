package com.github.salilvnair.convengine.config.config;

import com.github.salilvnair.convengine.config.ConvEngineEntityConfig;
import lombok.RequiredArgsConstructor;
import org.hibernate.boot.model.naming.Identifier;
import org.hibernate.boot.model.naming.PhysicalNamingStrategy;
import org.hibernate.boot.model.naming.PhysicalNamingStrategySnakeCaseImpl;
import org.hibernate.engine.jdbc.env.spi.JdbcEnvironment;
import org.springframework.stereotype.Component;

import java.util.Map;

@RequiredArgsConstructor
@Component
public class ConversationEngineCorePhysicalNamingStrategy extends PhysicalNamingStrategySnakeCaseImpl implements PhysicalNamingStrategy {

    private final ConvEngineEntityConfig config;

    @Override
    public Identifier toPhysicalTableName(Identifier identifier, JdbcEnvironment env) {
        if(identifier == null) {
            return null;
        }
        String text = identifier.getText();
        if(text.toUpperCase().startsWith("CE_")) {
            String entityNameKey = text.substring(3);
            Map<String, String> tables = config.getTables();
            if(tables.containsKey(entityNameKey.toUpperCase())) {
                String dynamicTableName = tables.get(entityNameKey.toUpperCase());
                return Identifier.toIdentifier(dynamicTableName, identifier.isQuoted());
            }
        }
        return identifier;
    }

    @Override public Identifier toPhysicalCatalogName(Identifier i, JdbcEnvironment e) { return null; }
    @Override public Identifier toPhysicalSchemaName(Identifier i, JdbcEnvironment e) { return i; }
    @Override public Identifier toPhysicalSequenceName(Identifier i, JdbcEnvironment e) { return i; }
    @Override public Identifier toPhysicalColumnName(Identifier i, JdbcEnvironment e) { return i; }
}