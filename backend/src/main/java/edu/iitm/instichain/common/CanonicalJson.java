package edu.iitm.instichain.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CanonicalJson {
    private final ObjectMapper mapper;

    public CanonicalJson(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode tree(Object value) {
        return mapper.valueToTree(value);
    }

    public byte[] bytes(Object value) {
        try {
            return mapper.writeValueAsBytes(sort(tree(value)));
        } catch (JsonProcessingException error) {
            throw new IllegalArgumentException("Unable to serialize the signed action", error);
        }
    }

    public String string(Object value) {
        return new String(bytes(value), java.nio.charset.StandardCharsets.UTF_8);
    }

    public JsonNode parse(byte[] value) {
        try {
            return mapper.readTree(value);
        } catch (java.io.IOException error) {
            throw new IllegalArgumentException("Fabric returned invalid JSON", error);
        }
    }

    private JsonNode sort(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            List<String> fields = new ArrayList<>();
            value.fieldNames().forEachRemaining(fields::add);
            fields.sort(Comparator.naturalOrder());
            for (String field : fields) sorted.set(field, sort(value.get(field)));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode sorted = mapper.createArrayNode();
            value.forEach(item -> sorted.add(sort(item)));
            return sorted;
        }
        return value;
    }
}
