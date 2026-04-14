package com.github.salilvnair.convengine.engine.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.salilvnair.convengine.entity.CeMcpDbTool;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
@Component
public class McpDbExecutor {

    private final NamedParameterJdbcTemplate jdbc; // uses your main datasource
    private final ObjectMapper mapper = new ObjectMapper();

    public String execute(CeMcpDbTool tool, Map<String, Object> args) {
        Map<String, Object> safeArgs = (args == null) ? Map.of() : args;

        // expand identifiers (${table}, ${column})
        String sql = McpSqlTemplate.expandIdentifiers(tool, safeArgs);

        // bind params (:value, :limit)
        Map<String, Object> params = new HashMap<>(safeArgs);

        // enforce limit
        if (!params.containsKey("limit")) {
            params.put("limit", Math.min(tool.getMaxRows(), 200));
        }

        List<Map<String, Object>> rows = jdbc.queryForList(sql, params);

        try {
            return mapper.writeValueAsString(rows);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize rows", e);
        }
    }
}
