package com.github.salilvnair.convengine.audit.dispatch;

import com.github.salilvnair.convengine.audit.AuditEventListener;
import com.github.salilvnair.convengine.config.ConvEngineAuditConfig;
import com.github.salilvnair.convengine.config.feature.ConvEngineAsyncAuditDispatchMarker;
import com.github.salilvnair.convengine.entity.CeAudit;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Component
@RequiredArgsConstructor
@Slf4j
public class AuditEventDispatcher {

    private final List<AuditEventListener> listeners;
    private final ConvEngineAuditConfig auditConfig;
    private final ObjectProvider<ConvEngineAsyncAuditDispatchMarker> asyncAuditDispatchMarker;

    private final AtomicLong droppedByBackpressure = new AtomicLong();
    private volatile ThreadPoolExecutor executor;

    @PostConstruct
    void init() {
        if (!isAsyncDispatchEnabled()) {
            return;
        }
        ConvEngineAuditConfig.Dispatch dispatch = auditConfig.getDispatch();
        int workers = Math.max(1, dispatch.getWorkerThreads());
        int queueCapacity = Math.max(1, dispatch.getQueueCapacity());
        long keepAliveSeconds = Math.max(0, dispatch.getKeepAliveSeconds());
        executor = new ThreadPoolExecutor(
                            workers,
                            workers,
                            keepAliveSeconds,
                            TimeUnit.SECONDS,
                            new LinkedBlockingQueue<>(queueCapacity),
                            new ConvEngineRejectedExecutionHandler(dispatch.getRejectionPolicy())
        );
        executor.prestartAllCoreThreads();
    }

    public void dispatch(CeAudit audit) {
        if (listeners == null || listeners.isEmpty() || audit == null) {
            return;
        }
        if (!isAsyncDispatchEnabled() || executor == null) {
            notifySync(audit);
            return;
        }

        try {
            executor.execute(() -> notifySync(audit));
        }
        catch (RejectedExecutionException ex) {
            droppedByBackpressure.incrementAndGet();
            log.warn("Audit event dropped convId={} stage={} reason={}", audit.getConversationId(), audit.getStage(), ex.getMessage());
        }
    }

    public long droppedByBackpressureCount() {
        return droppedByBackpressure.get();
    }

    @PreDestroy
    void shutdown() {
        if (executor == null) {
            return;
        }
        executor.shutdown();
    }

    private void notifySync(CeAudit audit) {
        for (AuditEventListener listener : listeners) {
            try {
                listener.onAudit(audit);
            }
            catch (Exception e) {
                log.warn(
                        "Audit listener failed listener={} convId={} stage={} msg={}",
                        listener.getClass().getSimpleName(),
                        audit.getConversationId(),
                        audit.getStage(),
                        e.getMessage()
                );
            }
        }
    }

    private boolean isAsyncDispatchEnabled() {
        return asyncAuditDispatchMarker.getIfAvailable() != null
                || (auditConfig.getDispatch() != null && auditConfig.getDispatch().isAsyncEnabled());
    }

    private final class ConvEngineRejectedExecutionHandler implements RejectedExecutionHandler {

        private final ConvEngineAuditConfig.RejectionPolicy policy;

        private ConvEngineRejectedExecutionHandler(ConvEngineAuditConfig.RejectionPolicy policy) {
            this.policy = policy == null ? ConvEngineAuditConfig.RejectionPolicy.CALLER_RUNS : policy;
        }

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor threadPoolExecutor) {
            switch (policy) {
                case CALLER_RUNS -> {
                    if (!threadPoolExecutor.isShutdown()) {
                        task.run();
                    }
                }
                case DROP_NEWEST -> {
                    droppedByBackpressure.incrementAndGet();
                }
                case DROP_OLDEST -> {
                    if (threadPoolExecutor.isShutdown()) {
                        droppedByBackpressure.incrementAndGet();
                        return;
                    }
                    Runnable evicted = threadPoolExecutor.getQueue().poll();
                    if (evicted == null) {
                        droppedByBackpressure.incrementAndGet();
                        return;
                    }
                    if (!threadPoolExecutor.getQueue().offer(task)) {
                        droppedByBackpressure.incrementAndGet();
                    }
                }
                case ABORT -> throw new RejectedExecutionException("Audit async queue full");
            }
        }
    }
}
