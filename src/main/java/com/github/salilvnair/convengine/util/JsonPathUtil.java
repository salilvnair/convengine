package com.github.salilvnair.convengine.util;

import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.TypeRef;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import lombok.experimental.UtilityClass;

import java.util.List;

@UtilityClass
public class JsonPathUtil {

    private static final Configuration SEARCH_CONFIG =
            Configuration.builder()
                    .jsonProvider(new JacksonJsonNodeJsonProvider())
                    .mappingProvider(new JacksonMappingProvider())
                    .options(Option.ALWAYS_RETURN_LIST, Option.SUPPRESS_EXCEPTIONS)
                    .build();

    public static List<Object> search(Object jsonObject, String path) {
        if (jsonObject == null || path == null || path.isBlank()) {
            return List.of();
        }
        List<Object> result = JsonPath.using(SEARCH_CONFIG).parse(jsonObject).read(path, new TypeRef<List<Object>>() {});
        return result == null ? List.of() : result;
    }
}
