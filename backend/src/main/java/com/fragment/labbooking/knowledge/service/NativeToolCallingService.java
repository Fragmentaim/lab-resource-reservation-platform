package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.common.auth.LoginUser;

import java.util.Optional;

public interface NativeToolCallingService {

    default Optional<ToolRouteResult> tryAnswer(String question, LoginUser actor) {
        return tryAnswer(question, actor, null, null);
    }

    Optional<ToolRouteResult> tryAnswer(String question, LoginUser actor, String sessionId, String traceId);
}
