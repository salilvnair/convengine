package com.github.salilvnair.convengine.annotation;

import com.github.salilvnair.convengine.config.ConvEngineAutoConfiguration;
import com.github.salilvnair.convengine.config.stream.EnableConvEngineImportRegistrar;
import org.springframework.context.annotation.Import;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import({ConvEngineAutoConfiguration.class, EnableConvEngineImportRegistrar.class})
public @interface EnableConvEngine {
    boolean stream() default true;
}
