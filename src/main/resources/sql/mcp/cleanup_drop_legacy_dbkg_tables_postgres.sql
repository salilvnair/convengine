-- One-time cleanup: drop legacy DBKG / catalog-era MCP tables.
-- Safe to run multiple times.

DROP TABLE IF EXISTS ce_mcp_case_signal CASCADE;
DROP TABLE IF EXISTS ce_mcp_case_type CASCADE;
DROP TABLE IF EXISTS ce_mcp_db_column CASCADE;
DROP TABLE IF EXISTS ce_mcp_db_join_path CASCADE;
DROP TABLE IF EXISTS ce_mcp_db_object CASCADE;
DROP TABLE IF EXISTS ce_mcp_domain_entity CASCADE;
DROP TABLE IF EXISTS ce_mcp_domain_relation CASCADE;
DROP TABLE IF EXISTS ce_mcp_executor_template CASCADE;
DROP TABLE IF EXISTS ce_mcp_id_lineage CASCADE;
DROP TABLE IF EXISTS ce_mcp_outcome_rule CASCADE;
DROP TABLE IF EXISTS ce_mcp_playbook CASCADE;
DROP TABLE IF EXISTS ce_mcp_playbook_signal CASCADE;
DROP TABLE IF EXISTS ce_mcp_playbook_step CASCADE;
DROP TABLE IF EXISTS ce_mcp_playbook_transition CASCADE;
DROP TABLE IF EXISTS ce_mcp_query_knowledge CASCADE;
DROP TABLE IF EXISTS ce_mcp_query_param_rule CASCADE;
DROP TABLE IF EXISTS ce_mcp_query_template CASCADE;
DROP TABLE IF EXISTS ce_mcp_schema_knowledge CASCADE;
DROP TABLE IF EXISTS ce_mcp_semantic_embedding CASCADE;
DROP TABLE IF EXISTS ce_mcp_sql_guardrail CASCADE;
DROP TABLE IF EXISTS ce_mcp_status_dictionary CASCADE;
DROP TABLE IF EXISTS ce_mcp_system_node CASCADE;
DROP TABLE IF EXISTS ce_mcp_system_relation CASCADE;
DROP TABLE IF EXISTS ce_mcp_api_flow CASCADE;
