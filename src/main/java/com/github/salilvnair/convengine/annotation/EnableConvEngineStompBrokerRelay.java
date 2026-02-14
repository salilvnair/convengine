package com.github.salilvnair.convengine.annotation;

import com.github.salilvnair.convengine.config.feature.ConvEngineStompBrokerRelayMarker;
import org.springframework.context.annotation.Import;
import java.lang.annotation.*;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Import(ConvEngineStompBrokerRelayMarker.class)
public @interface EnableConvEngineStompBrokerRelay {
}
