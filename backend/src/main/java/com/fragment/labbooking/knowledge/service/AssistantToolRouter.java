package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.common.auth.LoginUser;

import java.util.Optional;

/**
 * Routes a small, explicit set of business intents to permission-scoped tools.
 * It deliberately does not let model output choose an arbitrary endpoint.
 */
public interface AssistantToolRouter {

    Optional<ToolRouteResult> route(String question, LoginUser actor);
}
