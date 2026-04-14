package com.github.salilvnair.convengine.api.controller;

import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingCatalogRebuildRequest;
import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingCatalogRebuildResponse;
import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingRebuildRequest;
import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingRebuildResponse;
import com.github.salilvnair.convengine.cache.DbSchemaInspectorService;
import com.github.salilvnair.convengine.engine.mcp.query.semantic.embedding.SemanticEmbeddingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/db")
public class DbSchemaController {

    private final DbSchemaInspectorService dbSchemaInspectorService;
    private final SemanticEmbeddingService semanticEmbeddingService;

    @GetMapping("/inspect-schema")
    public ResponseEntity<Map<String, Object>> inspectSchema(
            @RequestParam(name = "prefix", required = false) String prefix,
            @RequestParam(name = "schema", required = false) String schema,
            @RequestParam(name = "matchMode", required = false) String matchMode
    ) {
        return ResponseEntity.ok(dbSchemaInspectorService.inspect(schema, prefix, matchMode));
    }

    @PostMapping("/semantic/embeddings/rebuild")
    public ResponseEntity<SemanticEmbeddingRebuildResponse> rebuildSemanticEmbeddings(
            @RequestBody(required = false) SemanticEmbeddingRebuildRequest request
    ) {
        SemanticEmbeddingRebuildRequest safeRequest =
                request == null ? new SemanticEmbeddingRebuildRequest() : request;
        return ResponseEntity.ok(semanticEmbeddingService.rebuildFromSemanticModel(safeRequest));
    }

    @PostMapping("/semantic/embeddings/catalog/rebuild")
    public ResponseEntity<SemanticEmbeddingCatalogRebuildResponse> rebuildSemanticEmbeddingCatalog(
            @RequestBody(required = false) SemanticEmbeddingCatalogRebuildRequest request
    ) {
        SemanticEmbeddingCatalogRebuildRequest safeRequest =
                request == null ? new SemanticEmbeddingCatalogRebuildRequest() : request;
        return ResponseEntity.ok(semanticEmbeddingService.rebuildEmbeddingCatalog(safeRequest));
    }

    @PostMapping("/semantic/embeddings/query-failures/rebuild")
    public ResponseEntity<SemanticEmbeddingCatalogRebuildResponse> rebuildQueryFailureEmbeddings(
            @RequestBody(required = false) SemanticEmbeddingCatalogRebuildRequest request
    ) {
        SemanticEmbeddingCatalogRebuildRequest safeRequest =
                request == null ? new SemanticEmbeddingCatalogRebuildRequest() : request;
        return ResponseEntity.ok(semanticEmbeddingService.rebuildQueryFailureEmbeddings(safeRequest));
    }
}
