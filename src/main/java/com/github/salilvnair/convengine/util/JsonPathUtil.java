package com.github.salilvnair.convengine.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.*;
import com.jayway.jsonpath.spi.json.JacksonJsonNodeJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.TypeRef;
import org.springframework.stereotype.Component;

/**
 * <b>JsonPathUtil</b> uses the jayway jsonpath library
 * and provides wrapper to set and search functionality on top of jsonpath api and avoids boilerplate.
 * @author Salil V Nair
 *
 */

@Component
public class JsonPathUtil {

    protected final Logger log = LoggerFactory.getLogger(this.getClass());

    private final Configuration configuration = getConfiguration();

    private ObjectMapper objectMapper;

    public Configuration getConfiguration() {
        return Configuration.builder().jsonProvider(new JacksonJsonNodeJsonProvider())
                .mappingProvider(new JacksonMappingProvider()).options(Option.SUPPRESS_EXCEPTIONS)
                .build();
    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) or a json string, path(json path expression, check
     *  jsonpath api readme to find more details) and the newData and adds it to the existing list or array</i>
     */
    public DocumentContext add(Object jsonObject, String path, Object newData) {
        if(jsonObject instanceof String jsonString) {
            return add(jsonString, path, newData);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return add(jsonString, path, newData);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>add:DocumentContext>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) , path(json path expression, check
     *  jsonpath api readme to find more details), newData and adds it to the existing list or array, a dummy boolean variable to return JSON string</i>
     */
    public String add(Object jsonObject, String path, Object newData, boolean returnJsonString) {
        if(jsonObject instanceof String jsonString) {
            return add(jsonString, path, newData, returnJsonString);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return add(jsonString, path, newData, returnJsonString);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>add:String>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) , path(json path expression, check
     *  jsonpath api readme to find more details), newData and adds it to the existing list or array, pass the typereference for the typed cast</i>
     */
    public <T> T add(Object jsonObject, String path, Object newData, TypeReference<T> typeRef) {
        if(jsonObject instanceof String jsonString) {
            return add(jsonString, path, newData, typeRef);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return add(jsonString, path, newData, typeRef);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>add:T(typeRef)>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    public DocumentContext add(String jsonString, String path, Object newData) {
        DocumentContext json = JsonPath.using(configuration).parse(jsonString);
        json.add(path, newData);
        return json;
    }

    public <T> T add(String jsonString, String path, Object newData, TypeReference<T> typeRef) {
        DocumentContext parsedJson = JsonPath.using(configuration).parse(jsonString);
        parsedJson.add(path, newData);
        T finalJson = null;
        try {
            finalJson = getObjectMapper().readValue(parsedJson.jsonString(), typeRef);
        }
        catch (IOException ioex) {
            log.error("JsonPathUtil>>add:T(TypeReference)>>conversion error:{}", String.valueOf(ioex));
        }
        return finalJson;
    }

    public String add(String jsonString, String path, Object newData, boolean returnJsonString) {
        if(returnJsonString) {
            return add(jsonString, path, newData).jsonString();
        }
        else {
            return null;
        }

    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) or a json string, path(json path expression, check
     *  jsonpath api readme to find more details) String key and the newData and Add or update the key with a the given value at the given path</i>
     */
    public DocumentContext put(Object jsonObject, String path, String key, Object newData) {
        if(jsonObject instanceof String jsonString) {
            return put(jsonString, path, key, newData);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return put(jsonString, path, key, newData);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>put:DocumentContext>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) , path(json path expression, check
     *  jsonpath api readme to find more details), newData, String key and Add or update the key with a the given value at the given path, a dummy boolean variable to return JSON string</i>
     */
    public String put(Object jsonObject, String path, String key, Object newData, boolean returnJsonString) {
        if(jsonObject instanceof String jsonString) {
            return put(jsonString, path, key, newData, returnJsonString);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return put(jsonString, path, key, newData, returnJsonString);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>put:String>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) , path(json path expression, check
     *  jsonpath api readme to find more details), newData , String key and Add or update the key with a the given value at the given path, pass the typereference for the typed cast</i>
     */
    public <T> T put(Object jsonObject, String path, String key, Object newData, TypeReference<T> typeRef) {
        if(jsonObject instanceof String jsonString) {
            return put(jsonString, path, key , newData, typeRef);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return put(jsonString, path, key, newData, typeRef);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>add:T(typeRef)>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    public DocumentContext put(String jsonString, String path, String key, Object newData) {
        DocumentContext json = JsonPath.using(configuration).parse(jsonString);
        json.put(path, key, newData);
        return json;
    }

    public <T> T put(String jsonString, String path, String key, Object newData, TypeReference<T> typeRef) {
        DocumentContext parsedJson = JsonPath.using(configuration).parse(jsonString);
        parsedJson.put(path, key, newData);
        T finalJson = null;
        try {
            finalJson = getObjectMapper().readValue(parsedJson.jsonString(), typeRef);
        }
        catch (IOException ioex) {
            log.error("JsonPathUtil>>add:T(TypeReference)>>conversion error:{}", String.valueOf(ioex));
        }
        return finalJson;
    }

    public String put(String jsonString, String path, String key, Object newData, boolean returnJsonString) {
        if(returnJsonString) {
            return put(jsonString, path , key, newData).jsonString();
        }
        else {
            return null;
        }

    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) or a json string, path(json path expression, check
     *  jsonpath api readme to find more details) and the newData</i>
     */
    public DocumentContext set(Object jsonObject, String path, Object newData) {
        if(jsonObject instanceof String jsonString) {
            return set(jsonString, path, newData);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return set(jsonString, path, newData);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>set:DocumentContext>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) , path(json path expression, check
     *  jsonpath api readme to find more details), newData and a dummy boolean variable to return JSON string</i>
     */
    public String set(Object jsonObject, String path, Object newData, boolean returnJsonString) {
        if(jsonObject instanceof String jsonString) {
            return set(jsonString, path, newData, returnJsonString);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return set(jsonString, path, newData, returnJsonString);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>set:String>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    public <T> T set(Object jsonObject, String path, Object newData, TypeReference<T> typeRef) {
        if(jsonObject instanceof String jsonString) {
            return set(jsonString, path, newData, typeRef);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return set(jsonString, path, newData, typeRef);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>set:T(typeRef)>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    public <T> T set(String jsonString, String path, Object newData, TypeReference<T> typeRef) {
        DocumentContext parsedJson = JsonPath.using(configuration).parse(jsonString);
        parsedJson.set(path, newData);
        T finalJson = null;
        try {
            finalJson = getObjectMapper().readValue(parsedJson.jsonString(), typeRef);
        }
        catch (IOException ioex) {
            log.error("JsonPathUtil>>set:T(TypeReference)>>conversion error:{}", String.valueOf(ioex));
        }
        return finalJson;
    }

    public DocumentContext set(String jsonString, String path, Object newData) {
        DocumentContext json = JsonPath.using(configuration).parse(jsonString);
        json.set(path, newData);
        return json;
    }

    public String set(String jsonString, String path, Object newData, boolean returnJsonString) {
        if(returnJsonString) {
            return set(jsonString, path, newData).jsonString();
        }
        else {
            return null;
        }

    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) , path(json path expression, check
     *  jsonpath api readme to find more details) and returns the matched Node</i>
     *  <br><br><b>NOTE:</b><i>The result will be always a List type</i>
     */
    public <T> T search(Object jsonObject, String path) {
        if(jsonObject instanceof String jsonString) {
            return search(jsonString, path);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return search(jsonString, path);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>search:T>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    public String search(Object jsonObject, String path, boolean returnJsonString) {
        Object searched = search(jsonObject, path);
        try {
            return getObjectMapper().writeValueAsString(searched);
        }
        catch(Exception ex) {
            log.error("JsonPathUtil>>search:T>>caught exception:{}", String.valueOf(ex));
        }
        return null;
    }

    /**
     * <i>This method takes a Java Object , path(json path expression, check
     *  jsonpath api readme to find more details) along with typeRef  and returns the matched Node
     *  in the form of same input typeRef</i>
     *  <br><br><b>NOTE:</b><i>The result will be always a List type</i>
     */
    public <T> T search(Object jsonObject, String path, TypeRef<T> typeRef) {
        if(jsonObject instanceof String jsonString) {
            return search(jsonString, path, typeRef);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return search(jsonString, path, typeRef);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>search:T(typeRef)>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    public <T> String search(String jsonString, String path, TypeRef<T> typeRef, boolean returnJsonString) {
        Object searched = search(jsonString, path, typeRef);
        try {
            return getObjectMapper().writeValueAsString(searched);
        }
        catch(Exception ex) {
            log.error("JsonPathUtil>>search:T>>caught exception:"+ex);
        }
        return null;
    }

    /**
     * <i>This method takes a json string , path(json path expression, check
     *  jsonpath api readme to find more details) and returns the matched Node</i>
     *  <br><br><b>NOTE:</b><i>The result will be always a List type</i>
     */
    public <T> T search(String jsonString, String path) {
        return JsonPath.using(getAlwaysListConfigWithSuppressedException()).parse(jsonString).read(path);
    }

    /**
     * <i>This method takes a json string , path(json path expression, check
     *  jsonpath api readme to find more details) along with typeRef  and returns the matched Node
     *  in the form of same input typeRef</i>
     *  <br><br><b>NOTE:</b><i>The result will be always a List type</i>
     */
    public <T> T search(String jsonString, String path, TypeRef<T> typeRef) {
        return JsonPath.using(getAlwaysListConfigWithSuppressedException()).parse(jsonString).read(path, typeRef);
    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) , path(json path expression, check
     *  jsonpath api readme to find more details) and deletes the matched Node</i>
     *  <br><br><b>NOTE:</b><i>The result will be always a List type</i>
     */
    public DocumentContext delete(Object jsonObject, String path) {
        if(jsonObject instanceof String jsonString) {
            return delete(jsonString, path);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return delete(jsonString, path);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>delete:DocumentContext>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    public <T> T delete(Object jsonObject, String path, TypeReference<T> typeRef) {
        if(jsonObject instanceof String jsonString) {
            return delete(jsonString, path, typeRef);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return delete(jsonString, path, typeRef);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>delete:T(typeRef)>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    /**
     * <i>This method takes an Object (which will be converted into json string using jackson mapper) , path(json path expression, check
     *  jsonpath api readme to find more details), a dummy boolean variable to return JSON string and deletes the matched Node</i>
     *  <br><br><b>NOTE:</b><i>The result will be always a List type</i>
     */
    public String delete(Object jsonObject, String path, boolean returnJsonString) {
        if(jsonObject instanceof String jsonString) {
            return delete(jsonString, path, returnJsonString);
        }
        else {
            try {
                String jsonString = getObjectMapper().writeValueAsString(jsonObject);
                return delete(jsonString, path, returnJsonString);
            }
            catch(Exception ex) {
                log.error("JsonPathUtil>>delete:String>>caught exception:{}", String.valueOf(ex));
            }
            return null;
        }
    }

    /**
     * public methods which will be utilized by the exposed public methods
     * */
    public DocumentContext delete(String jsonString, String path) {
        DocumentContext json = JsonPath.using(configuration).parse(jsonString);
        json.delete(path);
        return json;
    }

    public <T> T delete(String jsonString, String path, TypeReference<T> typeRef) {
        DocumentContext parsedJson = JsonPath.using(configuration).parse(jsonString);
        parsedJson.delete(path);
        T finalJson = null;
        try {
            finalJson = getObjectMapper().readValue(parsedJson.jsonString(), typeRef);
        }
        catch (IOException ioex) {
            log.error("JsonPathUtil>>delete:T(TypeReference)>>conversion error:{}", String.valueOf(ioex));
        }
        return finalJson;
    }

    public String delete(String jsonString, String path, boolean returnJsonString) {
        if(returnJsonString) {
            return delete(jsonString, path).jsonString();
        }
        else {
            return null;
        }
    }

    private Configuration getAlwaysListConfigWithSuppressedException() {
        return Configuration.builder().jsonProvider(new JacksonJsonNodeJsonProvider())
                .mappingProvider(new JacksonMappingProvider()).options(Option.ALWAYS_RETURN_LIST, Option.SUPPRESS_EXCEPTIONS)
                .build();
    }

    public ObjectMapper getObjectMapper() {
        if(objectMapper==null) {
            objectMapper = new ObjectMapper();
        }
        return objectMapper;
    }

    public void setObjectMapper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

}