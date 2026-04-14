package com.github.salilvnair.convengine.engine.mcp.util;

import com.github.salilvnair.convengine.audit.AuditService;
import com.github.salilvnair.convengine.audit.ConvEngineAuditStage;
import com.github.salilvnair.convengine.engine.session.EngineSession;
import com.github.salilvnair.convengine.transport.verbose.VerboseMessagePublisher;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class McpSqlAuditHelper {

    private McpSqlAuditHelper() {
        // static util
    }

    public static void auditSqlExecution(
            AuditService audit,
            VerboseMessagePublisher verbosePublisher,
            EngineSession session,
            UUID fallbackConversationId,
            String sourceClass,
            ConvEngineAuditStage stageIfSuccess,
            ConvEngineAuditStage stageIfError,
            Map<String, Object> basePayload,
            String sql,
            Map<String, Object> params,
            List<Map<String, Object>> rows,
            Exception error) {

        UUID conversationId = session != null ? session.getConversationId() : fallbackConversationId;
        if (conversationId == null) {
            return;
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (basePayload != null) {
            payload.putAll(basePayload);
        }
        payload.put("sql", sql);
        payload.put("params", params);
        payload.put("status", error == null ? "SUCCESS" : "ERROR");
        payload.put("error", error == null ? null : error.getClass().getName() + ": " + error.getMessage());

        ConvEngineAuditStage stage = error == null ? stageIfSuccess : stageIfError;

        if (error != null) {
            payload.putAll(buildErrorPayload(error));
        }
        payload.put("row_count", rows == null ? 0 : rows.size());
        payload.put("rows", rows == null ? List.of() : rows);

        if (audit != null) {
            audit.audit(stage, conversationId, payload);
        }

        if (session != null && verbosePublisher != null) {
            String toolCode = basePayload != null && basePayload.containsKey("tool_code")
                    ? String.valueOf(basePayload.get("tool_code"))
                    : null;
            verbosePublisher.publish(session, sourceClass, stage.name(), null, toolCode, error != null, payload);
        }
    }

    public static Map<String, Object> buildErrorPayload(Exception error) {
        Map<String, Object> details = new LinkedHashMap<>();
        Throwable root = rootCause(error);
        details.put("error_class", error.getClass().getName());
        details.put("error_message", error.getMessage());
        details.put("root_cause_class", root == null ? null : root.getClass().getName());
        details.put("root_cause_message", root == null ? null : root.getMessage());
        SQLException sqlError = sqlException(error);
        details.put("sql_state", sqlError == null ? null : sqlError.getSQLState());
        details.put("sql_error_code", sqlError == null ? null : sqlError.getErrorCode());
        return details;
    }

    public static SQLException sqlException(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
            current = current.getCause();
        }
        return null;
    }

    public static Throwable rootCause(Throwable error) {
        Throwable current = error;
        Throwable next = current == null ? null : current.getCause();
        while (next != null && next != current) {
            current = next;
            next = current.getCause();
        }
        return current;
    }
}
