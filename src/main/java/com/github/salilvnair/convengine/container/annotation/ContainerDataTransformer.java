package com.github.salilvnair.convengine.container.annotation;
import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ContainerDataTransformer {
    String intent();
    String state();
}