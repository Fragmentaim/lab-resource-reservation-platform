package com.fragment.labbooking.knowledge.agent;

import com.fragment.labbooking.common.auth.LoginUser;

import java.util.Map;

/**
 * Server-owned identity and authorization boundary for one agent run.
 * It is built from the authenticated principal, never from model output.
 */
public record PolicyContext(Long userId, String role, boolean admin) {

    public static PolicyContext from(LoginUser actor) {
        if (actor == null || actor.getId() == null) {
            throw new IllegalArgumentException("Agent execution requires an authenticated user");
        }
        return new PolicyContext(actor.getId(), actor.getRole() == null ? "" : actor.getRole(), actor.isAdmin());
    }

    public Map<String, Object> safeAttributes() {
        return Map.of("actor_type", admin ? "ADMIN" : "USER", "role", role);
    }
}
