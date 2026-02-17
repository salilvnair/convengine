package com.github.salilvnair.convengine.config.stream;

import org.springframework.beans.factory.config.ConstructorArgumentValues;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ConfigurationCondition;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class ConvEngineStreamEnabledCondition implements Condition, ConfigurationCondition {

    private static final String SETTINGS_BEAN_NAME = "convEngineStreamSettings";

    @Override
    public ConfigurationPhase getConfigurationPhase() {
        // Evaluate after registrars have had a chance to contribute bean definitions.
        return ConfigurationPhase.REGISTER_BEAN;
    }

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();

        // Preferred path: settings bean is visible in bean factory.
        if (beanFactory != null) {
            String[] names = beanFactory.getBeanNamesForType(ConvEngineStreamSettings.class, false, false);
            if (names.length > 0) {
                ConvEngineStreamSettings settings = beanFactory.getBean(names[0], ConvEngineStreamSettings.class);
                return settings.streamEnabled();
            }
        }

        // Fallback path: read constructor arg directly from bean definition registry.
        BeanDefinitionRegistry registry = context.getRegistry();
        if (registry != null && registry.containsBeanDefinition(SETTINGS_BEAN_NAME)) {
            ConstructorArgumentValues.ValueHolder holder =
                    registry.getBeanDefinition(SETTINGS_BEAN_NAME)
                            .getConstructorArgumentValues()
                            .getIndexedArgumentValue(0, Boolean.class);

            if (holder != null && holder.getValue() instanceof Boolean streamEnabled) {
                return streamEnabled;
            }
            if (holder != null && holder.getValue() != null) {
                return Boolean.parseBoolean(String.valueOf(holder.getValue()));
            }
        }

        // Backward-compatible default when setting is not available.
        return true;
    }
}
