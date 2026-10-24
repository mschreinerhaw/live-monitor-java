package com.live.monitor.ops;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** JSON boundary helpers shared by persistence, the agent contract and API validation. */
public final class OpsData {
    private static final ObjectMapper JSON = new ObjectMapper().configure(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS,true);
    private OpsData() { }
    public static Map<String,Object> map(Object... pairs) {
        Map<String,Object> result = new LinkedHashMap<>();
        for (int i=0;i<pairs.length;i+=2) result.put((String)pairs[i],pairs[i+1]);
        return result;
    }
    public static String text(Map<String,Object> value,String key) { return Objects.toString(value.get(key),""); }
    public static int number(Map<String,Object> value,String key,int fallback) {
        Object n=value.get(key); return n instanceof Number?((Number)n).intValue():fallback;
    }
    public static boolean flag(Map<String,Object> value,String key) { return Boolean.TRUE.equals(value.get(key)); }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Map<String,Object> value,String key) {
        Object child=value.get(key); return child instanceof Map?(Map<String,Object>)child:new LinkedHashMap<>();
    }
    @SuppressWarnings("unchecked")
    public static List<Map<String,Object>> rows(Map<String,Object> value,String key) {
        Object child=value.get(key); return child instanceof List?(List<Map<String,Object>>)child:new ArrayList<>();
    }
    public static String json(Object value) {
        try { return JSON.writeValueAsString(value); } catch(Exception e) { throw new IllegalArgumentException("Invalid JSON"); }
    }
    public static Map<String,Object> parse(String value) {
        try { return JSON.readValue(value,new TypeReference<Map<String,Object>>(){}); }
        catch(Exception e) { throw new IllegalArgumentException("Invalid JSON object"); }
    }
    public static void require(boolean condition,String message) {
        if(!condition) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
    public static String identifier(String value) {
        require(value.matches("[a-zA-Z0-9][a-zA-Z0-9_.-]{0,63}"),"Invalid resource identifier"); return value;
    }
}
