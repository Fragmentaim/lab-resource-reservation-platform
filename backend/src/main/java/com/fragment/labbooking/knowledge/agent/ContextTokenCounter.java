package com.fragment.labbooking.knowledge.agent;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * A privacy-safe token estimator used for context decisions before a request is
 * sent to the AI sidecar. The sidecar remains the final prompt token authority.
 */
@Component
public class ContextTokenCounter {

    public int estimate(String text) {
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        int chineseChars = 0;
        for (int index = 0; index < text.length(); index++) {
            char value = text.charAt(index);
            if (value >= '\u4e00' && value <= '\u9fff') {
                chineseChars++;
            }
        }
        int otherChars = Math.max(0, text.length() - chineseChars);
        return Math.max(1, (int) Math.ceil(chineseChars * 1.2 + otherChars / 4.0));
    }
}
