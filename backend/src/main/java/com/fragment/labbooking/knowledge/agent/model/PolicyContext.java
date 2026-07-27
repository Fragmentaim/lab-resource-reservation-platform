package com.fragment.labbooking.knowledge.agent.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fragment.labbooking.common.auth.LoginUser;

/**
 * Server-owned identity and authorization boundary for one agent run.
 * It is built from the authenticated principal, never from model output.
 */
public record PolicyContext(@JsonIgnore Long userId, String role, @JsonIgnore boolean admin) {

    public PolicyContext {
        userId = AgentModelGuard.requiredId(userId, "userId");
        role = AgentModelGuard.text(role);
    }

    public static PolicyContext from(LoginUser actor) {
        if (actor == null || actor.getId() == null) {
            throw new IllegalArgumentException("Agent execution requires an authenticated user");
        }
        return new PolicyContext(actor.getId(), actor.getRole(), actor.isAdmin());
    }

    @JsonProperty("actor_type")
    public String actorType() {
        return admin ? "ADMIN" : "USER";
    }
}
