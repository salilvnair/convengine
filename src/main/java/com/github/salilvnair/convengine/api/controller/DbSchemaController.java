package com.github.salilvnair.convengine.api.controller;

import com.github.salilvnair.convengine.api.dto.DbSchemaAgentGenerateRequest;
import com.github.salilvnair.convengine.api.dto.DbSchemaAgentGenerateResponse;
import com.github.salilvnair.convengine.api.dto.SemanticModelGenerateRequest;
import com.github.salilvnair.convengine.api.dto.SemanticModelGenerateResponse;
import com.github.salilvnair.convengine.api.dto.SemanticModelReloadRequest;
import com.github.salilvnair.convengine.api.dto.SemanticModelReloadResponse;
import com.github.salilvnair.convengine.api.dto.SemanticModelSaveRequest;
import com.github.salilvnair.convengine.api.dto.SemanticModelSaveResponse;
import com.github.salilvnair.convengine.api.dto.SemanticModelValidateRequest;
import com.github.salilvnair.convengine.api.dto.SemanticModelValidateResponse;
import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingRebuildRequest;
import com.github.salilvnair.convengine.api.dto.SemanticEmbeddingRebuildResponse;
import com.github.salilvnair.convengine.api.dto.SemanticQueryDebugRequest;
import com.github.salilvnair.convengine.api.dto.SemanticQueryDebugResponse;
import com.github.salilvnair.convengine.api.service.SemanticQueryDebugService;
import com.github.salilvnair.convengine.api.service.SemanticQueryModelAdminService;
import com.github.salilvnair.convengine.cache.DbSchemaAgentService;
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
    private final DbSchemaAgentService dbSchemaAgentService;
    private final SemanticEmbeddingService semanticEmbeddingService;
    private final SemanticQueryModelAdminService semanticQueryModelAdminService;
    private final SemanticQueryDebugService semanticQueryDebugService;

    @GetMapping("/inspect-schema")
    public ResponseEntity<Map<String, Object>> inspectSchema(
            @RequestParam(name = "prefix", required = false) String prefix,
            @RequestParam(name = "schema", required = false) String schema
    ) {
        return ResponseEntity.ok(dbSchemaInspectorService.inspect(schema, prefix));
    }

    @PostMapping("/agent")
    public ResponseEntity<DbSchemaAgentGenerateResponse> generateSchemaSeed(
            @RequestBody DbSchemaAgentGenerateRequest request
    ) {
        return ResponseEntity.ok(dbSchemaAgentService.generateSeedSql(request));
    }

    @PostMapping("/semantic/embeddings/rebuild")
    public ResponseEntity<SemanticEmbeddingRebuildResponse> rebuildSemanticEmbeddings(
            @RequestBody(required = false) SemanticEmbeddingRebuildRequest request
    ) {
        SemanticEmbeddingRebuildRequest safeRequest =
                request == null ? new SemanticEmbeddingRebuildRequest() : request;
        return ResponseEntity.ok(semanticEmbeddingService.rebuildFromSemanticModel(safeRequest));
    }

    @PostMapping("/semantic-query/generate-model")
    public ResponseEntity<SemanticModelGenerateResponse> generateSemanticModel(
            @RequestBody(required = false) SemanticModelGenerateRequest request
    ) {
        SemanticModelGenerateRequest safeRequest = request == null ? new SemanticModelGenerateRequest() : request;
        return ResponseEntity.ok(semanticQueryModelAdminService.generateDraft(safeRequest));
    }

    @PostMapping("/semantic-query/validate-model")
    public ResponseEntity<SemanticModelValidateResponse> validateSemanticModel(
            @RequestBody(required = false) SemanticModelValidateRequest request
    ) {
        SemanticModelValidateRequest safeRequest = request == null ? new SemanticModelValidateRequest() : request;
        return ResponseEntity.ok(semanticQueryModelAdminService.validate(safeRequest));
    }

    @PostMapping("/semantic-query/save-model")
    public ResponseEntity<SemanticModelSaveResponse> saveSemanticModel(
            @RequestBody(required = false) SemanticModelSaveRequest request
    ) {
        SemanticModelSaveRequest safeRequest = request == null ? new SemanticModelSaveRequest() : request;
        return ResponseEntity.ok(semanticQueryModelAdminService.save(safeRequest));
    }

    @PostMapping("/semantic-query/reload-model")
    public ResponseEntity<SemanticModelReloadResponse> reloadSemanticModel(
            @RequestBody(required = false) SemanticModelReloadRequest request
    ) {
        SemanticModelReloadRequest safeRequest = request == null ? new SemanticModelReloadRequest() : request;
        return ResponseEntity.ok(semanticQueryModelAdminService.reload(safeRequest));
    }

    @GetMapping("/semantic-query/current-model-yaml")
    public ResponseEntity<Map<String, String>> currentSemanticModelYaml() {
        return ResponseEntity.ok(Map.of("yaml", semanticQueryModelAdminService.currentModelYaml()));
    }

    @PostMapping("/semantic-query/debug-analyze")
    public ResponseEntity<SemanticQueryDebugResponse> debugAnalyzeSemanticQuery(
            @RequestBody(required = false) SemanticQueryDebugRequest request
    ) {
        SemanticQueryDebugRequest safeRequest = request == null ? new SemanticQueryDebugRequest() : request;
        return ResponseEntity.ok(semanticQueryDebugService.analyze(safeRequest));
    }
}
