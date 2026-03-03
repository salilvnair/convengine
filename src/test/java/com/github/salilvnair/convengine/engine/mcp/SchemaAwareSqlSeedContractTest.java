package com.github.salilvnair.convengine.engine.mcp;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaAwareSqlSeedContractTest {

    @Test
    void schemaAwareSeedEnforcesStrictPlannerAndReadableTimestamps() throws Exception {
        String seed = Files.readString(Path.of("src/main/resources/sql/dbkg/schema_aware_sql_seed.sql"));

        assertTrue(seed.contains("Action MUST be exactly CALL_TOOL or ANSWER"));
        assertTrue(seed.contains("Final answer must report human-readable date/time values"));
        assertTrue(seed.contains("to_char(h1.created_date AT TIME ZONE ''UTC''"));
        assertTrue(seed.contains("to_char(h2.created_date AT TIME ZONE ''UTC''"));
        assertTrue(seed.contains("`db.semantic.catalog`"));
        assertTrue(seed.contains("`postgres.query`"));
        assertTrue(seed.contains("{{dbkg_capsule}}"));
        assertTrue(seed.contains("Use DBKG capsule as primary grounding context"));
    }
}
