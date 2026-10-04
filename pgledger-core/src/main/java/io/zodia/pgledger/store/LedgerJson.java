package io.zodia.pgledger.store;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class LedgerJson {
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN)
            .build();

    private LedgerJson() {
    }

    public static byte[] writeBytes(Object value) {
        try {
            return MAPPER.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new LedgerException("failed to write json", e);
        }
    }

    public static String writeString(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (IOException e) {
            throw new LedgerException("failed to write json", e);
        }
    }

    public static <T> T read(byte[] json, Class<T> type) {
        try {
            return MAPPER.readValue(json, type);
        } catch (IOException e) {
            throw new JsonReadException(e);
        }
    }

    public static JsonNode tree(byte[] json) {
        try {
            return MAPPER.readTree(json);
        } catch (IOException e) {
            throw new JsonReadException(e);
        }
    }

    public static <T> T convert(JsonNode node, Class<T> type) {
        try {
            return MAPPER.treeToValue(node, type);
        } catch (IOException e) {
            throw new JsonReadException(e);
        }
    }

    public static <T> List<T> list(byte[] json, Class<T> element) {
        try {
            return MAPPER.readValue(json, MAPPER.getTypeFactory().constructCollectionType(List.class, element));
        } catch (IOException e) {
            throw new JsonReadException(e);
        }
    }

    public static Map<String, Object> map(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readValue(json, MAP_TYPE);
        } catch (IOException e) {
            throw new LedgerException("failed to read json", e);
        }
    }

    public static final class JsonReadException extends RuntimeException {
        public JsonReadException(IOException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
