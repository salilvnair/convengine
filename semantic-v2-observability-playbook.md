# Semantic V2 Observability + Retraining Playbook

## 1) Stage audit fields (dashboard contract)

Semantic v2 stages now emit consistent `ce_audit.payload_json` keys:

- `tool`
- `version`
- `semantic_v2_stage` (`interpret|resolve|query`)
- `semantic_v2_event` (`input|output|error`)
- `question`
- `confidence` (when stage produces one)
- `needsClarification`
- `ambiguityCount`
- `unresolvedCount` (resolve stage)
- `guardrailAllowed`, `guardrailReason` (query stage)

Recommended dashboard group-by:

- `conversation_id`
- `semantic_v2_stage`
- `semantic_v2_event`
- `confidence` bucket
- `needsClarification`
- `guardrailAllowed`

## 2) Failure correction sink

`ce_semantic_query_failures` is the canonical feedback table for SQL quality issues.

Captured automatically from:

- `db.semantic.query` v2 validation/guardrail errors
- `postgres.query` execution failures
- `THUMBS_DOWN` feedback submissions (with optional `correct_sql`)

Stored columns to use in QA:

- `question`
- `generated_sql`
- `correct_sql`
- `root_cause`
- `reason`
- `stage`
- `metadata_json`
- `created_at`

## 3) Weekly retraining cadence (recommended)

Every week (for example Monday 09:00 local):

1. Pull last 7 days from `ce_semantic_query_failures`.
2. Cluster by `root_cause` + `stage`.
3. Convert high-frequency corrected pairs into:
   - `ce_semantic_synonym` updates
   - `ce_semantic_mapping` updates
   - `ce_semantic_join_path` updates
   - `ce_semantic_query_class` skeleton tuning
4. Add 10-20 regression queries to automated tests.
5. Re-run shadow comparison (v1 vs v2) and track:
   - accuracy delta
   - clarification rate
   - guardrail block rate

## 4) Operational SQL snippets

Top failure causes (last 7 days):

```sql
SELECT root_cause, stage, count(*) AS failures
FROM ce_semantic_query_failures
WHERE created_at >= now() - interval '7 day'
GROUP BY root_cause, stage
ORDER BY failures DESC;
```

Queries with human corrections:

```sql
SELECT question, generated_sql, correct_sql, root_cause, created_at
FROM ce_semantic_query_failures
WHERE correct_sql IS NOT NULL AND correct_sql <> ''
ORDER BY created_at DESC
LIMIT 200;
```
