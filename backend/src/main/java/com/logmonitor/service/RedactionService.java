package com.logmonitor.service;

import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class RedactionService {
    private static final Pattern JWT = Pattern.compile("(?i)(bearer\\s+)?eyJ[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+\\.[a-zA-Z0-9_-]+");
    private static final Pattern SECRET = Pattern.compile("(?i)(token|cookie|password|passwd|secret|accesskey|api[-_ ]?key)(\\s*[:=]\\s*)([^\\s,，;；]+)");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile("(?<!\\d)\\d{17}[0-9Xx](?!\\d)");
    private static final Pattern IPV4 = Pattern.compile("(?<!\\d)(?:\\d{1,3}\\.){3}\\d{1,3}(?!\\d)");

    public String redact(String text) {
        if (text == null || text.isBlank()) return text;
        String value = JWT.matcher(text).replaceAll("[REDACTED_TOKEN]");
        value = SECRET.matcher(value).replaceAll("$1$2[REDACTED]");
        value = PHONE.matcher(value).replaceAll("[REDACTED_PHONE]");
        value = ID_CARD.matcher(value).replaceAll("[REDACTED_ID]");
        return IPV4.matcher(value).replaceAll("[REDACTED_IP]");
    }

    public String truncateContext(String text) {
        if (text == null) return null;
        String[] lines = text.split("\\R", -1);
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < Math.min(lines.length, 100); i++) {
            if (result.length() > 0) result.append('\n');
            result.append(lines[i]);
            if (result.length() >= 16_384) break;
        }
        return result.substring(0, Math.min(result.length(), 16_384));
    }
}
