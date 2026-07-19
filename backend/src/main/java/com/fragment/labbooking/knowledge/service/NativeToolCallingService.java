package com.fragment.labbooking.knowledge.service;

import com.fragment.labbooking.common.auth.LoginUser;

import java.util.Optional;

public interface NativeToolCallingService {

    Optional<ToolRouteResult> tryAnswer(String question, LoginUser actor);
}
