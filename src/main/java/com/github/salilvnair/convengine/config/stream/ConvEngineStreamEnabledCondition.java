package com.github.salilvnair.convengine.config.stream;

import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

public class ConvEngineStreamEnabledCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        if (beanFactory == null) {
            return true;
        }
        String[] names = beanFactory.getBeanNamesForType(ConvEngineStreamSettings.class, false, false);
        if (names.length == 0) {
            return true;
        }
        ConvEngineStreamSettings settings = beanFactory.getBean(names[0], ConvEngineStreamSettings.class);
        return settings.streamEnabled();
    }
}
