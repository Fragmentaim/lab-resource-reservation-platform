package com.fragment.labbooking.knowledge.common.constants;

import java.util.Set;

/**
 * Access scopes are evaluated by the Java service before document ids are sent
 * to the vector store. The model never receives an unfiltered document list.
 */
public final class DocumentVisibilityConstants {

    public static final String PUBLIC = "PUBLIC";
    public static final String UPLOADER_ONLY = "UPLOADER_ONLY";
    public static final String ADMIN_ONLY = "ADMIN_ONLY";
    public static final String SPECIFIED_USERS = "SPECIFIED_USERS";

    private static final Set<String> ALL = Set.of(
            PUBLIC, UPLOADER_ONLY, ADMIN_ONLY, SPECIFIED_USERS
    );

    private DocumentVisibilityConstants() {
    }

    public static boolean isSupported(String visibility) {
        return ALL.contains(visibility);
    }
}
