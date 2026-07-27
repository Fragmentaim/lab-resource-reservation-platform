package com.fragment.labbooking.common.util;

import org.springframework.util.StringUtils;

public final class TextUtil {

    private TextUtil() {
    }

    public static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
