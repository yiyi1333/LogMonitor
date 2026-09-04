package com.logmonitor.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

@Component
public class LocaleSupport {
    public static final String DEFAULT_TAG = "zh-CN";
    public static final List<Locale> SUPPORTED = List.of(
            Locale.forLanguageTag("zh-CN"), Locale.forLanguageTag("zh-TW"), Locale.forLanguageTag("en-US"),
            Locale.forLanguageTag("ja-JP"), Locale.forLanguageTag("ko-KR"), Locale.forLanguageTag("es-ES"),
            Locale.forLanguageTag("fr-FR"), Locale.forLanguageTag("de-DE"), Locale.forLanguageTag("pt-BR"));

    private final MessageSource messages;

    public LocaleSupport(MessageSource messages) {
        this.messages = messages;
    }

    public Locale current() {
        return normalize(LocaleContextHolder.getLocale());
    }

    public Locale from(HttpServletRequest request) {
        return normalize(request.getLocale());
    }

    public String tag() {
        return current().toLanguageTag();
    }

    public String message(String key, Object... arguments) {
        return messages.getMessage(key, arguments, key, current());
    }

    public String message(HttpServletRequest request, String key, Object... arguments) {
        return messages.getMessage(key, arguments, key, from(request));
    }

    public String error(String code, String fallback) {
        if (current().toLanguageTag().equalsIgnoreCase(DEFAULT_TAG) && fallback != null) return fallback;
        String key = "error." + code;
        String translated = messages.getMessage(key, null, key, current());
        if (!key.equals(translated)) return translated;
        return current().toLanguageTag().equalsIgnoreCase(DEFAULT_TAG) && fallback != null
                ? fallback : message("error.REQUEST_FAILED");
    }

    public String error(HttpServletRequest request, String code, String fallback) {
        String key = "error." + code;
        Locale selected = from(request);
        if (selected.toLanguageTag().equalsIgnoreCase(DEFAULT_TAG) && fallback != null) return fallback;
        String translated = messages.getMessage(key, null, key, selected);
        if (!key.equals(translated)) return translated;
        return selected.toLanguageTag().equalsIgnoreCase(DEFAULT_TAG) && fallback != null
                ? fallback : messages.getMessage("error.REQUEST_FAILED", null, "Request failed", selected);
    }

    public static Locale normalize(Locale candidate) {
        if (candidate != null) {
            String tag = candidate.toLanguageTag();
            for (Locale supported : SUPPORTED) {
                if (supported.toLanguageTag().equalsIgnoreCase(tag)) return supported;
            }
            if ("zh".equalsIgnoreCase(candidate.getLanguage())) {
                String country = candidate.getCountry();
                if ("TW".equalsIgnoreCase(country) || "HK".equalsIgnoreCase(country)
                        || "MO".equalsIgnoreCase(country)) return Locale.forLanguageTag("zh-TW");
                return Locale.forLanguageTag(DEFAULT_TAG);
            }
            for (Locale supported : SUPPORTED) {
                if (supported.getLanguage().equalsIgnoreCase(candidate.getLanguage())) return supported;
            }
        }
        return Locale.forLanguageTag(DEFAULT_TAG);
    }
}
