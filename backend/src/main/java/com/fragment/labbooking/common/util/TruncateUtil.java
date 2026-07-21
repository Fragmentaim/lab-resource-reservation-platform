package com.fragment.labbooking.common.util;

/**
 * Truncation utility shared across the codebase.
 * Used when writing strings to DB columns with known length limits
 * (e.g. VARCHAR(255), VARCHAR(512)) to prevent DataTruncation errors.
 */
public final class TruncateUtil {

    private TruncateUtil() {}

    /**
     * Truncate text to maxLength characters, returning null if text is null.
     *
     * @param text      the text to truncate; null returns null
     * @param maxLength maximum number of characters to keep; must be positive
     * @return the original text if it fits, or a prefix of maxLength characters
     */
    public static String truncate(String text, int maxLength) {
        if (text == null) {
            return null;
        }
        if (maxLength <= 0) {
            return "";
        }
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }
}
