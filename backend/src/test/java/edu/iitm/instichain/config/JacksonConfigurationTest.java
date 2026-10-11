package edu.iitm.instichain.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

class JacksonConfigurationTest {
    @Test
    void supportsOffsetDateTimeRequestValues() throws Exception {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        ObjectMapper mapper = new JacksonConfiguration().objectMapper(builder);

        OffsetDateTime timestamp = mapper.readValue(
                "\"2026-10-12T09:30:00+05:30\"", OffsetDateTime.class);

        assertEquals(OffsetDateTime.parse("2026-10-12T09:30:00+05:30").toInstant(),
                timestamp.toInstant());
    }
}
