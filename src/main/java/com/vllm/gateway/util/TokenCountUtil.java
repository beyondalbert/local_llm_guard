package com.vllm.gateway.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import org.springframework.stereotype.Component;

@Component
public class TokenCountUtil {

    private final Encoding encoding;

    public TokenCountUtil() {
        EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
        this.encoding = registry.getEncoding(EncodingType.CL100K_BASE);
    }

    public int count(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        try {
            return encoding.countTokens(text);
        } catch (Exception e) {
            // reference-only fallback: roughly 4 chars per token
            return Math.max(1, text.length() / 4);
        }
    }

    public int countMessages(JsonNode messages) {
        if (messages == null || !messages.isArray()) {
            return 0;
        }
        int total = 0;
        for (JsonNode message : messages) {
            JsonNode content = message.get("content");
            if (content == null) {
                continue;
            }
            if (content.isTextual()) {
                total += count(content.asText());
            } else if (content.isArray()) {
                for (JsonNode part : content) {
                    JsonNode text = part.get("text");
                    if (text != null && text.isTextual()) {
                        total += count(text.asText());
                    }
                }
            }
        }
        return total;
    }
}
