# ConvEngine Flow Consumer Contract

This document defines strict payload shapes expected by flow steps.

## `context_json`

```json
{
  "type": "object",
  "properties": {
    "pending_action_key": { "type": "string" },
    "pending_action": {
      "type": "object",
      "properties": {
        "action_key": { "type": "string" }
      },
      "additionalProperties": true
    },
    "pending_action_runtime": {
      "type": "object",
      "properties": {
        "action_key": { "type": "string" },
        "action_ref": { "type": "string" },
        "status": {
          "type": "string",
          "enum": ["OPEN", "IN_PROGRESS", "EXECUTED", "REJECTED", "EXPIRED"]
        },
        "created_turn": { "type": "integer" },
        "created_at_epoch_ms": { "type": "integer" },
        "expires_turn": { "type": "integer" },
        "expires_at_epoch_ms": { "type": "integer" }
      },
      "additionalProperties": true
    },
    "pending_slot": {
      "type": ["object", "string", "array"]
    },
    "approval": {
      "type": "object",
      "properties": {
        "granted": { "type": "boolean" }
      },
      "additionalProperties": true
    },
    "memory": {
      "type": "object",
      "properties": {
        "session_summary": { "type": "string" },
        "recalled_summary": { "type": "string" }
      },
      "additionalProperties": true
    }
  },
  "additionalProperties": true
}
```

## `inputParams`

```json
{
  "type": "object",
  "properties": {
    "pending_action_key": { "type": "string" },
    "approval_granted": { "type": "boolean" },
    "tool_request": {
      "$ref": "#/$defs/toolRequest"
    }
  },
  "additionalProperties": true,
  "$defs": {
    "toolRequest": {
      "type": "object",
      "required": ["args"],
      "properties": {
        "tool_code": { "type": "string" },
        "tool_group": {
          "type": "string",
          "enum": [
            "DB",
            "HTTP_API",
            "WORKFLOW_ACTION",
            "DOCUMENT_RETRIEVAL",
            "CALCULATOR_TRANSFORM",
            "NOTIFICATION",
            "FILES"
          ]
        },
        "args": { "type": "object" }
      },
      "additionalProperties": true
    }
  }
}
```

## `pending_action` runtime semantics

- `OPEN`: action candidate discovered and waiting for user affirmation.
- `IN_PROGRESS`: execution started on current turn.
- `EXECUTED`: successful completion.
- `REJECTED`: user denied.
- `EXPIRED`: TTL exceeded by turns or time.

TTL settings come from:

- `convengine.flow.action-lifecycle.ttl-turns`
- `convengine.flow.action-lifecycle.ttl-minutes`

## `tool_request` payload

```json
{
  "tool_code": "crm.lookup",
  "tool_group": "HTTP_API",
  "args": {
    "customerId": "C123"
  }
}
```

`tool_code` may be omitted when `tool_group` and executor-specific args are enough for routing.

## `tool_result` payload

```json
{
  "status": "SUCCESS",
  "tool_code": "crm.lookup",
  "tool_group": "HTTP_API",
  "result": {
    "id": "C123",
    "name": "Robert King"
  }
}
```

Error shape:

```json
{
  "status": "ERROR",
  "tool_code": "crm.lookup",
  "tool_group": "HTTP_API",
  "error": "No HttpApiToolHandler or HttpApiExecutorAdapter configured for tool_code=CRM.LOOKUP"
}
```

## HTTP_API consumer extension model (2.0.7)

Consumers now have three ways to execute `HTTP_API` MCP tools:

1. `HttpApiToolHandler` (recommended): one Spring bean per `tool_code`.
2. `HttpApiRequestingToolHandler`: framework-managed `HttpClient` with policy/retry/auth/mapping.
3. `HttpApiApiProcessorToolHandler`: `RestWebServiceFacade` + delegate + handler processResponse pattern.

Recommended implementation pattern:

```java
@Component
public class CrmLookupToolHandler implements HttpApiToolHandler {
    @Override
    public String toolCode() {
        return "crm.lookup";
    }

    @Override
    public Object execute(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        // call downstream API and return either:
        // - Map/List POJO (preferred; auto-serialized to JSON)
        // - raw JSON string
        // - plain text (wrapped as {"text":"..."})
        return Map.of("customerId", args.get("customerId"), "status", "ACTIVE");
    }
}
```

Execution resolution order in `McpHttpApiToolExecutor`:

1. Match `HttpApiToolHandler.toolCode()` to `ce_mcp_tool.tool_code` (case-insensitive).
2. If matched handler is `HttpApiApiProcessorToolHandler`, framework executes `RestWebServiceFacade`.
3. If matched handler is `HttpApiRequestingToolHandler`, framework executes built-in `HttpApiToolInvoker`.
4. If matched handler is plain `HttpApiToolHandler`, framework calls `execute(...)` directly.
5. If no handler matches, fallback to `HttpApiExecutorAdapter` (if configured).
6. If neither exists, fail safely with descriptive error in `tool_result`.

## HTTP_API advanced execution model (2.0.7)

For retry/backoff/circuit/auth/mapping support, implement `HttpApiRequestingToolHandler`:

```java
@Component
public class OrderStatusApiTool implements HttpApiRequestingToolHandler {
    @Override
    public String toolCode() {
        return "order.status.api";
    }

    @Override
    public HttpApiRequestSpec requestSpec(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        return new HttpApiRequestSpec(
                "GET",
                "https://api.company.com/orders/status",
                Map.of("Accept", "application/json"),
                Map.of("orderId", args.get("orderId")),
                null,
                new HttpApiAuthSpec(HttpApiAuthType.API_KEY, "X-API-KEY", "secret-key", null),
                null, // null => use convengine.mcp.http-api.defaults
                new HttpApiResponseMapping(
                        HttpApiResponseMappingMode.FIELD_TEMPLATE,
                        null,
                        Map.of(
                                "orderId", "$.data.orderId",
                                "status", "$.data.status",
                                "eta", "$.data.eta")));
    }
}
```

Mapper-class example:

```java
new HttpApiResponseMapping(
        HttpApiResponseMappingMode.MAPPER_CLASS,
        null,
        Map.of(),
        "com.acme.loan.api.LoanRatingResponse");
```

`McpHttpApiToolExecutor` behavior for `HttpApiRequestingToolHandler`:

1. Build request spec from handler.
2. Execute via framework default `HttpApiToolInvoker` transport (`HttpClient`).
3. Apply timeout/retry/backoff/circuit/auth/response mapping.
4. Return normalized JSON observation.

`HttpApiResponseMappingMode` options:

- `RAW_JSON`
- `JSON_PATH`
- `FIELD_TEMPLATE`
- `MAPPER_CLASS` (maps parsed JSON to `mapperClassName` using Jackson `ObjectMapper.convertValue`)
- `TEXT`

## HTTP_API [api-processor](https://github.com/salilvnair/api-processor) handler model (2.0.7)

If you want `RestWebServiceFacade` flow, implement `HttpApiApiProcessorToolHandler`:

```java
import com.github.salilvnair.api.processor.rest.handler.RestWebServiceHandler;
import com.github.salilvnair.convengine.engine.mcp.executor.adapter.HttpApiApiProcessorToolHandler;
import com.github.salilvnair.convengine.engine.mcp.executor.http.ApiProcessorInvocationContext;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.entity.CeMcpTool;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class LoanCreditRatingWsToolHandler implements HttpApiApiProcessorToolHandler {

    private final LoanCreditRatingRestWebServiceHandler wsHandler;

    public LoanCreditRatingWsToolHandler(LoanCreditRatingRestWebServiceHandler wsHandler) {
        this.wsHandler = wsHandler;
    }

    @Override
    public String toolCode() {
        return "loan.credit.rating.check";
    }

    @Override
    public RestWebServiceHandler wsHandler(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        return wsHandler;
    }

    @Override
    public ApiProcessorInvocationContext wsContext(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        LoanCreditRatingContext ctx = new LoanCreditRatingContext();
        ctx.setCustomerId(String.valueOf(args.get("customerId")));
        ctx.setRequestAmount(args.get("requestedAmount"));
        return ctx;
    }

    @Override
    public Class<?> responseMapperClass(CeMcpTool tool, Map<String, Object> args, EngineSession session) {
        return LoanCreditRatingMcpResponse.class;
    }

    @Override
    public Object extractResponse(
            CeMcpTool tool,
            Map<String, Object> args,
            EngineSession session,
            Map<String, Object> wsMap,
            ApiProcessorInvocationContext context
    ) {
        LoanCreditRatingContext ctx = (LoanCreditRatingContext) context;
        return ctx.getMappedResponse();
    }
}
```

Related library:
- [ccf-core](https://github.com/salilvnair/ccf-core)

Execution flow for this mode:

1. ConvEngine resolves tool handler by `tool_code`.
2. `RestWebServiceFacade.initiate(...)` is invoked by framework.
3. Your `RestWebServiceHandler.prepareRequest(...)` runs.
4. Your delegate `invoke(...)` runs.
5. Your `RestWebServiceHandler.processResponse(...)` runs and writes mapped data to context.
6. ConvEngine reads mapped object via `extractResponse(...)`, optionally converts to `responseMapperClass`, and stores MCP observation JSON.

This matches the same handler/delegate/facade pattern used by your LLM provider integration.

## DB advanced knowledge model (2.0.7)

`DB` tools now support custom per-tool handlers (`DbToolHandler`) before SQL-template fallback.

Built-in option: `DbKnowledgeGraphToolHandler` (enable with `convengine.mcp.db.knowledge.enabled=true`).

This tool supports:

1. Use case 1: query knowledge table (store detailed business scenarios + prepared queries/API hints).
2. Use case 2: schema knowledge table (store per-table/per-column meaning and semantics).

Required args:

```json
{
  "question": "Why order submitted by admin stays async null on api4?"
}
```

Response shape:

```json
{
  "question": "...",
  "queryKnowledge": [
    {
      "score": 0.76,
      "queryText": "...",
      "description": "...",
      "preparedSql": "SELECT ...",
      "tags": ["order", "admin"],
      "apiHints": ["api3", "api4"]
    }
  ],
  "schemaKnowledge": [
    {
      "score": 0.61,
      "tableName": "orders",
      "columnName": "async_status",
      "description": "null means no async callback received",
      "tags": ["api4", "status"]
    }
  ],
  "insights": {
    "suggestedPreparedQueries": ["SELECT ..."],
    "suggestedTables": ["orders"]
  }
}
```

Configuration:

```yaml
convengine:
  mcp:
    db:
      knowledge:
        enabled: true
        tool-code: db.knowledge.graph
        query-catalog-table: ce_mcp_query_knowledge
        query-text-column: query_text
        query-description-column: description
        prepared-sql-column: prepared_sql
        tags-column: tags
        api-hints-column: api_hints
        schema-catalog-table: ce_mcp_schema_knowledge
        schema-table-name-column: table_name
        schema-column-name-column: column_name
        schema-description-column: description
        schema-tags-column: tags
```

## MCP planner prompt scoping (2.0.7)

Planner prompts can be stored in `ce_mcp_planner` and selected by `intent_code` + `state_code`:

```sql
CREATE TABLE ce_mcp_planner (
  planner_id BIGINT PRIMARY KEY,
  intent_code VARCHAR(255) NOT NULL,
  state_code VARCHAR(255) NOT NULL,
  system_prompt TEXT NOT NULL,
  user_prompt TEXT NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT true
);
```

Runtime selection behavior:

1. Match `ce_mcp_planner` by enabled + eligible intent/state.
2. Prefer most specific row (exact intent/state over `ANY`).
3. Fallback to legacy `ce_config` keys (`DB_SYSTEM_PROMPT`, `DB_USER_PROMPT`, then `SYSTEM_PROMPT`, `USER_PROMPT`).

Sample tables (consumer-owned):

```sql
CREATE TABLE ce_mcp_query_knowledge (
  id BIGINT PRIMARY KEY,
  query_text VARCHAR(1000) NOT NULL,
  description VARCHAR(2000),
  prepared_sql VARCHAR(4000),
  tags VARCHAR(1000),
  api_hints VARCHAR(1000)
);

CREATE TABLE ce_mcp_schema_knowledge (
  id BIGINT PRIMARY KEY,
  table_name VARCHAR(255) NOT NULL,
  column_name VARCHAR(255),
  description VARCHAR(2000),
  tags VARCHAR(1000)
);
```

Ready-to-run seed packs:

- `src/main/resources/sql/mcp_planner_seed.sql` (Postgres)
- `src/main/resources/sql/mcp_planner_seed_postgres.sql` (Postgres alias)
- `src/main/resources/sql/mcp_planner_seed_sqlite.sql` (SQLite)
- `src/main/resources/sql/seed_mcp_advanced_postgres.sql`
- `src/main/resources/sql/seed_mcp_advanced_sqlite.sql`
