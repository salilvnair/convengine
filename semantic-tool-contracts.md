# Semantic Tool Contracts (V2)

This document defines the JSON I/O contracts for the semantic V2 pipeline:

`db.semantic.interpret -> db.semantic.resolve -> db.semantic.query -> postgres.query`

## 1) Common Confidence + Ambiguity Schema

All V2 tool responses MUST include:

```json
{
  "confidence": 0.0,
  "needsClarification": false,
  "clarificationQuestion": null,
  "ambiguities": []
}
```

Rules:
- `confidence` is `0.0..1.0`.
- `needsClarification=true` when confidence is below threshold or any required ambiguity is unresolved.
- `clarificationQuestion` is required when `needsClarification=true`.
- `ambiguities` is required and uses this shape:

```json
{
  "type": "ENTITY|FIELD|VALUE|TIME_RANGE|JOIN_PATH|QUERY_CLASS",
  "code": "short_stable_code",
  "message": "human readable reason",
  "required": true,
  "options": [
    {
      "key": "option_key",
      "label": "option label",
      "confidence": 0.0
    }
  ]
}
```

## 2) `db.semantic.interpret`

Purpose:
- Convert user language into canonical business intent only.
- No DB table/column names allowed in output.

### Request

```json
{
  "question": "show rejected relinks for ups today",
  "conversationId": "uuid-string",
  "context": {},
  "hints": {
    "domain": "zapper",
    "timezone": "America/Chicago"
  }
}
```

### Response

```json
{
  "tool": "db.semantic.interpret",
  "version": "v2",
  "question": "show rejected relinks for ups today",
  "canonicalIntent": {
    "intent": "LIST_REQUESTS",
    "entity": "REQUEST",
    "queryClass": "LIST_REQUESTS",
    "filters": [
      { "field": "request_type", "op": "EQ", "value": "RELINK" },
      { "field": "status", "op": "EQ", "value": "REJECTED" },
      { "field": "customer", "op": "EQ", "value": "UPS" }
    ],
    "timeRange": {
      "kind": "RELATIVE",
      "value": "TODAY",
      "timezone": "America/Chicago"
    },
    "sort": [
      { "field": "created_at", "direction": "DESC" }
    ],
    "limit": 100
  },
  "confidence": 0.91,
  "needsClarification": false,
  "clarificationQuestion": null,
  "ambiguities": [],
  "trace": {
    "normalizations": ["rejected->REJECTED", "relinks->RELINK"],
    "notes": []
  }
}
```

## 3) `db.semantic.resolve`

Purpose:
- Deterministically map canonical intent to physical DB plan.
- Must return unresolved items instead of guessing.

### Request

```json
{
  "canonicalIntent": {
    "intent": "LIST_REQUESTS",
    "entity": "REQUEST",
    "queryClass": "LIST_REQUESTS",
    "filters": [
      { "field": "request_type", "op": "EQ", "value": "RELINK" }
    ],
    "timeRange": {
      "kind": "RELATIVE",
      "value": "TODAY",
      "timezone": "America/Chicago"
    }
  },
  "conversationId": "uuid-string",
  "context": {}
}
```

### Response

```json
{
  "tool": "db.semantic.resolve",
  "version": "v2",
  "resolvedPlan": {
    "queryClass": "LIST_REQUESTS",
    "baseEntity": "REQUEST",
    "baseTable": "zp_disco_request",
    "select": [
      { "field": "requestId", "column": "zp_disco_request.request_id" },
      { "field": "requestStatus", "column": "zp_disco_request.request_status" },
      { "field": "createdAt", "column": "zp_disco_request.created_at" }
    ],
    "filters": [
      {
        "field": "request_type",
        "column": "zp_disco_request.request_type",
        "op": "EQ",
        "value": "RELINK"
      }
    ],
    "joins": [
      {
        "leftTable": "zp_disco_request",
        "rightTable": "zp_disco_trans_data",
        "joinType": "LEFT",
        "on": "zp_disco_request.request_id = zp_disco_trans_data.request_id"
      }
    ],
    "timeRange": {
      "column": "zp_disco_request.created_at",
      "from": "2026-03-11T00:00:00-05:00",
      "to": "2026-03-11T23:59:59-05:00",
      "timezone": "America/Chicago"
    },
    "sort": [
      { "column": "zp_disco_request.created_at", "direction": "DESC" }
    ],
    "limit": 100
  },
  "unresolved": [],
  "confidence": 0.89,
  "needsClarification": false,
  "clarificationQuestion": null,
  "ambiguities": []
}
```

## 4) `db.semantic.query`

Purpose:
- Build AST + SQL from resolved plan only.
- Apply guardrails/policy before execution.

### Request

```json
{
  "resolvedPlan": {
    "baseTable": "zp_disco_request",
    "filters": [],
    "joins": []
  },
  "strictMode": true,
  "dryRun": false,
  "conversationId": "uuid-string"
}
```

### Response

```json
{
  "tool": "db.semantic.query",
  "version": "v2",
  "ast": {
    "astVersion": "v1",
    "entity": "REQUEST",
    "projections": [],
    "where": { "op": "AND", "conditions": [], "groups": [] },
    "sort": [],
    "groupBy": [],
    "limit": 100
  },
  "compiledSql": {
    "sql": "SELECT ... FROM ... WHERE ... ORDER BY ... LIMIT :p_limit",
    "params": { "p_limit": 100 }
  },
  "guardrail": {
    "allowed": true,
    "reason": null
  },
  "confidence": 0.93,
  "needsClarification": false,
  "clarificationQuestion": null,
  "ambiguities": []
}
```

## 5) `postgres.query`

Purpose:
- Execute SQL only (no semantic inference).

### Request

```json
{
  "sql": "SELECT * FROM zp_disco_request WHERE request_status = :p_status LIMIT :p_limit",
  "params": {
    "p_status": "REJECTED",
    "p_limit": 100
  },
  "readOnly": true,
  "timeoutMs": 10000
}
```

### Response

```json
{
  "tool": "postgres.query",
  "success": true,
  "rowCount": 42,
  "rows": [],
  "meta": {
    "elapsedMs": 73
  }
}
```

## 6) Planner Contract

Planner chain:
1. `db.semantic.interpret`
2. If `needsClarification=true`: return clarification to user and stop.
3. `db.semantic.resolve`
4. If `needsClarification=true`: return clarification to user and stop.
5. `db.semantic.query`
6. `postgres.query`

## 7) Backward Compatibility

`db.semantic.query` should support:
- Legacy mode: raw question input (v1 behavior)
- Strict mode (`strictMode=true`): requires `resolvedPlan` input

Recommended flag:
- `convengine.mcp.db.semantic.pipeline=v2`
