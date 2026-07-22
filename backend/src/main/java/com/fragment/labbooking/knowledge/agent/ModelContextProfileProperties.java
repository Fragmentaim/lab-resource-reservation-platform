package com.fragment.labbooking.knowledge.agent;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Server-owned model capability registry. End users never tune token buckets;
 * operators select one capability profile for the configured provider/model.
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.knowledge.model-context")
public class ModelContextProfileProperties {

    private String activeProfile = "safe-default";
    private Map<String, Profile> profiles = new LinkedHashMap<>();

    public Profile active() {
        Profile configured = profiles.get(activeProfile);
        return configured == null ? new Profile() : configured.normalized();
    }

    @Data
    public static class Profile {
        /** Complete model context window, including prompt and generated output. */
        private int contextWindowTokens = 12000;
        /** Output capacity reserved for the current response. */
        private int maxOutputTokens = 2000;
        /** Covers tokenizer/provider accounting differences and next-round overhead. */
        private int safetyMarginTokens = 512;
        /** When compaction occurs, retain this share of the available raw-history room. */
        private double compactionTargetRatio = 0.70D;
        /** 0 means derive from the context window; protects one abnormal tool payload. */
        private int maxSingleToolResultTokens = 0;

        public Profile normalized() {
            Profile copy = new Profile();
            copy.contextWindowTokens = Math.max(2048, contextWindowTokens);
            copy.maxOutputTokens = Math.min(Math.max(256, maxOutputTokens), copy.contextWindowTokens / 2);
            copy.safetyMarginTokens = Math.min(Math.max(128, safetyMarginTokens), copy.contextWindowTokens / 4);
            copy.compactionTargetRatio = Math.max(0.35D, Math.min(0.90D, compactionTargetRatio));
            copy.maxSingleToolResultTokens = maxSingleToolResultTokens;
            return copy;
        }

        public int effectiveMaxSingleToolResultTokens() {
            if (maxSingleToolResultTokens > 0) return maxSingleToolResultTokens;
            return Math.max(1600, Math.min(8192, contextWindowTokens / 32));
        }
    }
}
