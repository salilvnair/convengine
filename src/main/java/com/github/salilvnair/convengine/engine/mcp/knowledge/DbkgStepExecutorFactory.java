package com.github.salilvnair.convengine.engine.mcp.knowledge;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class DbkgStepExecutorFactory {

    private final List<DbkgStepExecutor> executors;

    public DbkgStepExecutor require(String executorCode) {
        return executors.stream()
                .filter(executor -> executor.supports(executorCode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(DbkgConstants.MESSAGE_NO_STEP_EXECUTOR_PREFIX + executorCode));
    }
}
