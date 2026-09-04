package com.logmonitor.service;

import com.logmonitor.config.LogMonitorProperties;
import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.model.LogModels.AccessEvent;
import com.logmonitor.model.LogModels.ErrorEvent;
import com.logmonitor.model.LogModels.ParsedBatch;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LogParser {
    private static final Pattern HEADER = Pattern.compile("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+(TRACE|DEBUG|INFO|WARN|ERROR)\\s+\\d+\\s+---\\s+\\[([^]]+)]\\s+(\\S+)\\s*:\\s?(.*)$", Pattern.MULTILINE);
    private static final Pattern URI = Pattern.compile("当前请求URI[:：]([^,，\\s]+)");
    private static final Pattern URL_URI = Pattern.compile("当前请求URL[:：]https?://[^/]+([^,，\\s]+)");
    private static final Pattern EXCEPTION = Pattern.compile("(?m)^\\s*(?:Caused by:\\s*)?([a-zA-Z_$][\\w$]*(?:\\.[\\w$]+)*(?:Exception|Error))(?::\\s*(.*))?\\s*$");
    private static final Pattern BUSINESS_FRAME = Pattern.compile("(?m)^\\s*at\\s+(src\\.main\\.[^(]+)");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    private final LogMonitorProperties properties;
    private final RedactionService redaction;
    private final Map<String, RequestContext> requestsByThread = new ConcurrentHashMap<>();

    public LogParser(LogMonitorProperties properties, RedactionService redaction) {
        this.properties = properties;
        this.redaction = redaction;
    }

    public ParsedBatch parse(String service, String content, String sourcePath, long baseOffset) {
        Source source = properties.getSources().stream()
                .filter(item -> service.equals(item.getName()))
                .findFirst()
                .orElseGet(() -> {
                    Source fallback = new Source();
                    fallback.setName(service);
                    return fallback;
                });
        return parse(source, content, sourcePath, baseOffset);
    }

    public ParsedBatch parse(Source source, String content, String sourcePath, long baseOffset) {
        String service = source.getApplicationNamespace();
        String instanceKey = source.getInstanceKey();
        String sourceIdentity = source.getId() == null ? instanceKey + "|" + source.getName() : String.valueOf(source.getId());
        List<RawEvent> rawEvents = splitEvents(content, baseOffset, source.getCharset());
        List<AccessEvent> accesses = new ArrayList<>();
        List<ErrorEvent> errors = new ArrayList<>();
        long parseErrors = 0;
        Instant lastEventAt = null;
        List<RawError> pendingErrors = new ArrayList<>();

        for (RawEvent raw : rawEvents) {
            Matcher header = HEADER.matcher(raw.text());
            if (!header.find()) {
                parseErrors++;
                continue;
            }
            Instant at = LocalDateTime.parse(header.group(1), TIME).atZone(properties.getSourceTimezone()).toInstant();
            if (lastEventAt == null || at.isAfter(lastEventAt)) lastEventAt = at;
            String level = header.group(2);
            String thread = header.group(3).trim();
            String logger = header.group(4).trim();
            String message = header.group(5);
            String contextKey = service + "|" + instanceKey + "|" + sourcePath + "|" + thread;

            if (logger.endsWith("OncePerRequest") && message.contains("当前请求URL")) {
                String uri = extractUri(message);
                if (uri != null) {
                    uri = normalizeUri(source, uri);
                    accesses.add(new AccessEvent(service, uri, at,
                            sha256(sourceIdentity + "|" + sourcePath + "|" + raw.offset()
                                    + "|" + at + "|" + thread + "|" + uri)));
                    requestsByThread.put(contextKey, new RequestContext(uri, at));
                }
            }
            if (message.contains("afterCompletion")) {
                requestsByThread.remove(contextKey);
            }
            if (!"ERROR".equals(level) || isNoise(logger, message)) continue;

            RequestContext context = requestsByThread.get(contextKey);
            String inferred = context != null && Duration.between(context.at(), at).abs().toMinutes() < 10 ? context.uri() : null;
            pendingErrors.add(new RawError(at, thread, logger, message, raw.text(), raw.offset(), inferred));
        }

        for (int i = 0; i < pendingErrors.size();) {
            RawError first = pendingErrors.get(i);
            StringBuilder combined = new StringBuilder(first.fullText());
            int j = i + 1;
            while (j < pendingErrors.size()) {
                RawError next = pendingErrors.get(j);
                if (!next.thread().equals(first.thread()) || Duration.between(first.at(), next.at()).abs().toMillis() > 2000) break;
                combined.append('\n').append(next.fullText());
                j++;
            }
            errors.add(toErrorEvent(service, instanceKey, sourceIdentity, first, combined.toString(), sourcePath));
            i = j;
        }
        return new ParsedBatch(accesses, errors, parseErrors, lastEventAt);
    }

    private List<RawEvent> splitEvents(String content, long baseOffset, Charset charset) {
        List<RawEvent> result = new ArrayList<>();
        StringBuilder current = null;
        long currentOffset = baseOffset;
        long cursor = baseOffset;
        for (String line : content.split("(?<=\\n)", -1)) {
            String clean = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
            if (HEADER.matcher(clean).find()) {
                if (current != null) result.add(new RawEvent(current.toString().stripTrailing(), currentOffset));
                current = new StringBuilder(clean);
                currentOffset = cursor;
            } else if (current != null) {
                current.append('\n').append(clean);
            }
            cursor += line.getBytes(charset).length;
        }
        if (current != null) result.add(new RawEvent(current.toString().stripTrailing(), currentOffset));
        return result;
    }

    private ErrorEvent toErrorEvent(String service, String instanceKey, String sourceIdentity, RawError raw, String combined, String sourcePath) {
        String exceptionClass = findRootException(combined);
        String category = classify(raw.logger(), raw.message(), exceptionClass);
        String clean = redaction.truncateContext(redaction.redact(combined));
        String summary = redaction.redact(raw.message()).strip();
        if (summary.length() > 2000) summary = summary.substring(0, 2000);
        String signature = signatureFor(category, exceptionClass, summary, combined);
        String fingerprint = fingerprintFor(service, signature);
        String eventKey = sha256(sourceIdentity + "|" + sourcePath + "|" + raw.offset()
                + "|" + raw.at() + "|" + raw.thread()
                + "|" + normalize(clean));
        return new ErrorEvent(service, category, exceptionClass, summary, raw.at(), raw.thread(), summary,
                clean, raw.inferredUri(), raw.inferredUri() == null ? "NONE" : "INFERRED", sourcePath,
                raw.offset(), signature, fingerprint, eventKey);
    }

    private String extractUri(String message) {
        Matcher matcher = URI.matcher(message);
        if (matcher.find()) return matcher.group(1);
        matcher = URL_URI.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String normalizeUri(Source source, String uri) {
        int query = uri.indexOf('?');
        String value = query >= 0 ? uri.substring(0, query) : uri;
        for (String regex : source.getUriNormalizers()) {
            value = value.replaceAll(regex, "/{id}");
        }
        return value;
    }

    private boolean isNoise(String logger, String message) {
        return logger.endsWith("LoginInterceptor") && message.matches(".*\\*+uri[:：]\\*+.*");
    }

    private String findRootException(String text) {
        Matcher matcher = EXCEPTION.matcher(text);
        String found = null;
        while (matcher.find()) found = matcher.group(1);
        if (found != null) return found;
        if (text.contains("SQL") || text.contains("SqlException")) return "SQL_ERROR";
        return "LOG_ERROR";
    }

    private String classify(String logger, String message, String exceptionClass) {
        String value = (logger + " " + message + " " + exceptionClass).toLowerCase();
        if (value.contains("businessexception") || value.contains("validation failed") ||
                value.contains("用户未登录") || value.contains("凭证") || value.contains("不能为空") ||
                value.contains("不允许")) return "BUSINESS";
        return "SYSTEM";
    }

    private String normalize(String text) {
        return text.toLowerCase()
                .replaceAll("\\b\\d{4}-\\d{2}-\\d{2}(?:[ t]\\d{2}:\\d{2}:\\d{2})?\\b", "{date}")
                .replaceAll("(?<![a-z])\\d{3,}(?![a-z])", "{n}")
                .replaceAll("0x[0-9a-f]+", "{hex}")
                .replaceAll("\\s+", " ").strip();
    }

    public String signatureFor(String category, String exceptionClass, String summary, String stackTrace) {
        String businessFrame = "";
        Matcher frame = BUSINESS_FRAME.matcher(stackTrace == null ? "" : stackTrace);
        if (frame.find()) businessFrame = frame.group(1);
        return sha256(category + "|" + exceptionClass + "|" + normalize(summary == null ? "" : summary) + "|" + businessFrame);
    }

    public String fingerprintFor(String applicationNamespace, String signature) {
        return sha256(applicationNamespace.toLowerCase(Locale.ROOT) + "|" + signature);
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private record RawEvent(String text, long offset) {}
    private record RawError(Instant at, String thread, String logger, String message, String fullText, long offset, String inferredUri) {}
    private record RequestContext(String uri, Instant at) {}
}
