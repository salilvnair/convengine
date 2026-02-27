package com.github.salilvnair.convengine.cache;

import com.github.salilvnair.convengine.service.ConversationCacheService;
import com.github.salilvnair.convengine.service.ConversationHistoryCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.concurrent.ConcurrentMapCache;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConvEngineCacheAnalyzer {

    private static final List<String> STATIC_CACHES = List.of(
            "ce_config",
            "ce_rule",
            "ce_pending_action",
            "ce_intent",
            "ce_intent_classifier",
            "ce_output_schema",
            "ce_prompt_template",
            "ce_response",
            "ce_container_config",
            "ce_mcp_tool",
            "ce_mcp_db_tool",
            "ce_mcp_planner",
            "ce_policy",
            "ce_verbose"
    );

    private static final List<String> RUNTIME_CACHES = List.of(
            "ce_conversation_cache",
            "ce_conversation_history_cache"
    );

    private final CacheManager cacheManager;
    private final StaticConfigurationCacheService staticCacheService;
    private final ConversationCacheService conversationCacheService;
    private final ConversationHistoryCacheService conversationHistoryCacheService;
    private final ApplicationContext applicationContext;
    private final Environment environment;

    public Map<String, Object> analyze(boolean includeWarmupAndTiming) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cacheProvider", cacheProviderMetadata());
        result.put("springCacheProperties", springCacheProperties());
        result.put("cacheInfrastructure", cacheInfraMetadata());
        result.put("aopAndProxyDiagnostics", aopAndProxyDiagnostics());
        result.put("warmupAndTimingEnabled", includeWarmupAndTiming);

        if (includeWarmupAndTiming) {
            result.put("warmupTimingMs", warmupStaticCachesAndMeasureMs());
        }

        Map<String, Object> staticCacheReport = new LinkedHashMap<>();
        for (String cacheName : STATIC_CACHES) {
            staticCacheReport.put(cacheName, describeStaticCache(cacheName));
        }
        result.put("staticCaches", staticCacheReport);

        Map<String, Object> runtimeCacheReport = new LinkedHashMap<>();
        for (String cacheName : RUNTIME_CACHES) {
            runtimeCacheReport.put(cacheName, describeRuntimeCache(cacheName));
        }
        result.put("runtimeCaches", runtimeCacheReport);

        log.info("ConvEngine CacheAnalyzer: {}", result);
        return result;
    }

    private Map<String, Object> cacheProviderMetadata() {
        Map<String, Object> provider = new LinkedHashMap<>();
        provider.put("cacheManagerBeanClass", cacheManager.getClass().getName());
        provider.put("cacheManagerToString", String.valueOf(cacheManager));
        provider.put("registeredCacheNames", cacheManager.getCacheNames());

        Map<String, String> cacheManagerBeans = new LinkedHashMap<>();
        String[] beanNames = BeanFactoryUtils.beanNamesForTypeIncludingAncestors(applicationContext, CacheManager.class);
        for (String beanName : beanNames) {
            Object bean = applicationContext.getBean(beanName);
            cacheManagerBeans.put(beanName, bean.getClass().getName());
        }
        provider.put("allCacheManagerBeans", cacheManagerBeans);
        provider.put("singleCacheManagerBeanPresent", beanNames.length == 1);
        return provider;
    }

    private Map<String, Object> springCacheProperties() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("spring.cache.type", environment.getProperty("spring.cache.type"));
        props.put("spring.cache.cache-names", environment.getProperty("spring.cache.cache-names"));
        props.put("spring.aop.proxy-target-class", environment.getProperty("spring.aop.proxy-target-class"));
        props.put("spring.aop.auto", environment.getProperty("spring.aop.auto"));
        return props;
    }

    private Map<String, Object> cacheInfraMetadata() {
        Map<String, Object> infra = new LinkedHashMap<>();
        String[] interceptorBeans = BeanFactoryUtils
                .beanNamesForTypeIncludingAncestors(applicationContext, org.springframework.cache.interceptor.CacheInterceptor.class);
        String[] operationSourceBeans = BeanFactoryUtils
                .beanNamesForTypeIncludingAncestors(applicationContext, org.springframework.cache.interceptor.CacheOperationSource.class);
        String[] resolverBeans = BeanFactoryUtils
                .beanNamesForTypeIncludingAncestors(applicationContext, org.springframework.cache.interceptor.CacheResolver.class);
        String[] errorHandlerBeans = BeanFactoryUtils
                .beanNamesForTypeIncludingAncestors(applicationContext, org.springframework.cache.interceptor.CacheErrorHandler.class);

        infra.put("cacheInterceptorBeans", Arrays.asList(interceptorBeans));
        infra.put("cacheOperationSourceBeans", Arrays.asList(operationSourceBeans));
        infra.put("cacheResolverBeans", Arrays.asList(resolverBeans));
        infra.put("cacheErrorHandlerBeans", Arrays.asList(errorHandlerBeans));
        return infra;
    }

    private Map<String, Object> aopAndProxyDiagnostics() {
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("StaticConfigurationCacheService", describeBeanProxy(staticCacheService, StaticConfigurationCacheService.class));
        diag.put("ConversationCacheService", describeBeanProxy(conversationCacheService, ConversationCacheService.class));
        diag.put("ConversationHistoryCacheService",
                describeBeanProxy(conversationHistoryCacheService, ConversationHistoryCacheService.class));
        return diag;
    }

    private Map<String, Object> describeBeanProxy(Object bean, Class<?> declaredType) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("beanRuntimeClass", bean.getClass().getName());
        data.put("targetClass", AopUtils.getTargetClass(bean).getName());
        data.put("isAopProxy", AopUtils.isAopProxy(bean));
        data.put("isCglibProxy", AopUtils.isCglibProxy(bean));
        data.put("isJdkDynamicProxy", AopUtils.isJdkDynamicProxy(bean));
        data.put("cacheAnnotatedMethodsOnDeclaredType", listCacheAnnotatedMethods(declaredType));
        data.put("cacheAnnotatedMethodsOnTargetType", listCacheAnnotatedMethods(AopUtils.getTargetClass(bean)));

        if (bean instanceof Advised advised) {
            data.put(
                    "advisors",
                    Arrays.stream(advised.getAdvisors())
                            .map(advisor -> advisor.getAdvice().getClass().getName())
                            .collect(Collectors.toList())
            );
        } else {
            data.put("advisors", List.of());
        }
        return data;
    }

    private List<String> listCacheAnnotatedMethods(Class<?> clazz) {
        return List.of(clazz.getMethods()).stream()
                .filter(this::hasCacheAnnotation)
                .map(m -> m.getName() + "(" + m.getParameterCount() + " args)")
                .distinct()
                .sorted()
                .toList();
    }

    private boolean hasCacheAnnotation(Method method) {
        return method.isAnnotationPresent(org.springframework.cache.annotation.Cacheable.class)
                || method.isAnnotationPresent(org.springframework.cache.annotation.CachePut.class)
                || method.isAnnotationPresent(org.springframework.cache.annotation.CacheEvict.class);
    }

    private Map<String, Object> warmupStaticCachesAndMeasureMs() {
        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("ce_config", measure(() -> staticCacheService.getAllConfigs()));
        metrics.put("ce_rule", measure(() -> staticCacheService.getAllRules()));
        metrics.put("ce_pending_action", measure(() -> staticCacheService.getAllPendingActions()));
        metrics.put("ce_intent", measure(() -> staticCacheService.getAllIntents()));
        metrics.put("ce_intent_classifier", measure(() -> staticCacheService.getAllIntentClassifiers()));
        metrics.put("ce_output_schema", measure(() -> staticCacheService.getAllOutputSchemas()));
        metrics.put("ce_prompt_template", measure(() -> staticCacheService.getAllPromptTemplates()));
        metrics.put("ce_response", measure(() -> staticCacheService.getAllResponses()));
        metrics.put("ce_container_config", measure(() -> staticCacheService.getAllContainerConfigs()));
        metrics.put("ce_mcp_tool", measure(() -> staticCacheService.getAllMcpTools()));
        metrics.put("ce_mcp_db_tool", measure(() -> staticCacheService.getAllMcpDbTools()));
        metrics.put("ce_mcp_planner", measure(() -> staticCacheService.getAllMcpPlanners()));
        metrics.put("ce_policy", measure(() -> staticCacheService.getAllPolicies()));
        metrics.put("ce_verbose", measure(() -> staticCacheService.getAllVerboses()));
        return metrics;
    }

    private Map<String, Object> describeStaticCache(String cacheName) {
        Map<String, Object> details = new LinkedHashMap<>();
        Cache cache = cacheManager.getCache(cacheName);
        details.put("exists", cache != null);
        if (cache == null) {
            return details;
        }

        Cache.ValueWrapper entry = cache.get(SimpleKey.EMPTY);
        details.put("hasSimpleKeyEntry", entry != null);
        if (entry != null) {
            Object value = entry.get();
            details.put("valueType", value == null ? "null" : value.getClass().getName());
            if (value instanceof List<?> list) {
                details.put("listSize", list.size());
            }
        }

        if (cache instanceof ConcurrentMapCache cmc) {
            details.put("nativeEntryCount", cmc.getNativeCache().size());
        }
        return details;
    }

    private Map<String, Object> describeRuntimeCache(String cacheName) {
        Map<String, Object> details = new LinkedHashMap<>();
        Cache cache = cacheManager.getCache(cacheName);
        details.put("exists", cache != null);
        if (cache instanceof ConcurrentMapCache cmc) {
            details.put("nativeEntryCount", cmc.getNativeCache().size());
        }
        return details;
    }

    private Map<String, Object> measure(Runnable action) {
        long start = System.nanoTime();
        action.run();
        long firstMs = (System.nanoTime() - start) / 1_000_000;

        long secondStart = System.nanoTime();
        action.run();
        long secondMs = (System.nanoTime() - secondStart) / 1_000_000;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("firstCallMs", firstMs);
        data.put("secondCallMs", secondMs);
        return data;
    }
}
