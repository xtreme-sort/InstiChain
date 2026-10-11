package edu.iitm.instichain.common;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CanonicalJsonTest {
    @Test
    void recursivelySortsObjectKeysWithoutReorderingArrays() {
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("z", 2);
        nested.put("a", List.of("second", "first"));
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("payload", nested);
        value.put("actor_id", "id");

        assertEquals("{\"actor_id\":\"id\",\"payload\":{\"a\":[\"second\",\"first\"],\"z\":2}}",
                new CanonicalJson(new ObjectMapper()).string(value));
    }
}
