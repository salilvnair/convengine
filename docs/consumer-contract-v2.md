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
  "error": "No HttpApiExecutorAdapter configured"
}
```
