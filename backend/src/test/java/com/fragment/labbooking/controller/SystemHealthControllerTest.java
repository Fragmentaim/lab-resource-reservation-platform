package com.fragment.labbooking.controller;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SystemHealthControllerTest {

    private final SystemHealthController controller = new SystemHealthController();

    @Test
    void healthShouldExposeOnlyLivenessMetadata() {
        Map<String, Object> response = controller.health();

        assertEquals("UP", response.get("status"));
        assertEquals("lab-booking-backend", response.get("service"));
        assertNotNull(response.get("timestamp"));
        assertEquals(3, response.size());
    }
}
