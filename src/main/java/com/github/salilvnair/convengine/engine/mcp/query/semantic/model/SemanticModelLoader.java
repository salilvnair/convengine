package com.github.salilvnair.convengine.engine.mcp.query.semantic.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.github.salilvnair.convengine.config.ConvEngineMcpConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Slf4j
@Component
@RequiredArgsConstructor
public class SemanticModelLoader {

    private final ConvEngineMcpConfig mcpConfig;
    private final ResourceLoader resourceLoader;
    private volatile ObjectMapper semanticMapper;

    public SemanticModel loadOrEmpty() {
        ConvEngineMcpConfig.Db.Semantic cfg = mcpConfig.getDb() == null
                ? new ConvEngineMcpConfig.Db.Semantic()
                : (mcpConfig.getDb().getSemantic() == null ? new ConvEngineMcpConfig.Db.Semantic() : mcpConfig.getDb().getSemantic());
        String path = cfg.getModelPath();
        if (path == null || path.isBlank()) {
            return new SemanticModel(1, "", "", null, null, null, null);
        }
        try {
            Resource resource = resourceLoader.getResource(path);
            if (!resource.exists()) {
                log.warn("Semantic model not found at path={}. Using empty model.", path);
                return new SemanticModel(1, "", "", null, null, null, null);
            }
            try (InputStream in = resource.getInputStream()) {
                SemanticModel model = mapper().readValue(in, SemanticModel.class);
                log.info("Loaded semantic model from path={}", path);
                return model == null ? new SemanticModel(1, "", "", null, null, null, null) : model;
            }
        } catch (Exception ex) {
            log.warn("Failed to load semantic model from path={}. Using empty model. cause={}", path, ex.getMessage());
            return new SemanticModel(1, "", "", null, null, null, null);
        }
    }

    private ObjectMapper mapper() {
        ObjectMapper current = semanticMapper;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (semanticMapper != null) {
                return semanticMapper;
            }
            semanticMapper = createYamlPreferredMapper();
            return semanticMapper;
        }
    }

    private ObjectMapper createYamlPreferredMapper() {
        try {
            Class<?> yamlFactoryClass = Class.forName("com.fasterxml.jackson.dataformat.yaml.YAMLFactory");
            Class<?> jsonFactoryClass = Class.forName("com.fasterxml.jackson.core.JsonFactory");
            Object yamlFactory = yamlFactoryClass.getDeclaredConstructor().newInstance();
            ObjectMapper mapper = (ObjectMapper) ObjectMapper.class
                    .getConstructor(jsonFactoryClass)
                    .newInstance(yamlFactory);
            return mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        } catch (Throwable ignored) {
            log.warn("YAMLFactory not available on classpath. Semantic model loader will parse as JSON only.");
            return new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        }
    }
}
