package edu.iitm.instichain.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HealthControllerTest {
    @Test
    void reportsHealthy() {
        assertEquals("ok", new HealthController().health().get("status"));
    }
}
