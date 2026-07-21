package com.fragment.labbooking.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * Liveness endpoint for containers and reverse-proxy probes.
 *
 * It deliberately does not expose configuration or dependency credentials.
 * Dependency-level readiness remains observable through service-specific APIs.
 */
@RestController
public class SystemHealthController {

    @GetMapping("/system/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "service", "lab-booking-backend",
                "timestamp", Instant.now().toString()
        );
    }
}
