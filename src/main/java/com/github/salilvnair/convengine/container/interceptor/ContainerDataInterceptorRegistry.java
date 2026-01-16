package com.github.salilvnair.convengine.container.interceptor;

import com.github.salilvnair.convengine.container.annotation.ContainerDataInterceptor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

@Component
public class ContainerDataInterceptorRegistry {

    private final List<ContainerDataRequestInterceptor> requestInterceptors;
    private final List<ContainerDataResponseInterceptor> responseInterceptors;

    public ContainerDataInterceptorRegistry(
            List<ContainerDataRequestInterceptor> requestInterceptors,
            List<ContainerDataResponseInterceptor> responseInterceptors
    ) {
        this.requestInterceptors = sort(requestInterceptors);
        this.responseInterceptors = sort(responseInterceptors);
    }

    public List<ContainerDataRequestInterceptor> requestInterceptors() {
        return requestInterceptors;
    }

    public List<ContainerDataResponseInterceptor> responseInterceptors() {
        return responseInterceptors;
    }

    private <T> List<T> sort(List<T> list) {
        return list.stream()
                .filter(i -> i.getClass().isAnnotationPresent(ContainerDataInterceptor.class))
                .sorted(Comparator.comparingInt(i ->
                        i.getClass()
                                .getAnnotation(ContainerDataInterceptor.class)
                                .order()
                ))
                .toList();
    }
}
