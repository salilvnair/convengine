package com.github.salilvnair.convengine.config.stream;

import com.github.salilvnair.convengine.annotation.EnableConvEngine;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;
import java.util.Map;

public class EnableConvEngineImportRegistrar implements ImportBeanDefinitionRegistrar {

    private static final String BEAN_NAME = "convEngineStreamSettings";

    @Override
    public void registerBeanDefinitions(AnnotationMetadata importingClassMetadata, BeanDefinitionRegistry registry) {
        Map<String, Object> attrs = importingClassMetadata.getAnnotationAttributes(EnableConvEngine.class.getName());
        boolean stream = true;
        if (attrs != null && attrs.get("stream") instanceof Boolean b) {
            stream = b;
        }
        if (registry.containsBeanDefinition(BEAN_NAME)) {
            registry.removeBeanDefinition(BEAN_NAME);
        }
        RootBeanDefinition beanDefinition = new RootBeanDefinition(ConvEngineStreamSettings.class);
        beanDefinition.getConstructorArgumentValues().addIndexedArgumentValue(0, stream);
        registry.registerBeanDefinition(BEAN_NAME, beanDefinition);
    }
}
