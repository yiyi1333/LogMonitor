package com.logmonitor.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.logmonitor.model.LlmModels.StructuredAnalysis;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class AiAnalysisSupport {
    private final ObjectMapper json;

    AiAnalysisSupport(ObjectMapper json) {
        this.json = json;
    }

    String systemPrompt(String locale) {
        String language = switch (locale) {
            case "zh-TW" -> "Traditional Chinese";
            case "en-US" -> "English";
            case "ja-JP" -> "Japanese";
            case "ko-KR" -> "Korean";
            case "es-ES" -> "Spanish";
            case "fr-FR" -> "French";
            case "de-DE" -> "German";
            case "pt-BR" -> "Brazilian Portuguese";
            default -> "Simplified Chinese";
        };
        return """
                You are a senior Java operations engineer. Analyze only the supplied redacted logs and do not invent facts.
                Write all human-readable values in %s. Output only one JSON object without Markdown or commentary:
                {"overview":"summary","rootCauses":["possible cause"],"investigationSteps":["step"],
                "fixSuggestions":["suggestion"],"riskLevel":"LOW|MEDIUM|HIGH|CRITICAL"}
                """.formatted(language);
    }

    StructuredAnalysis parse(String content) {
        String candidate = content.trim();
        if (candidate.startsWith("```")) {
            candidate = candidate.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        try {
            JsonNode node = json.readTree(candidate);
            if (!node.isObject() || node.path("overview").asText().isBlank()) return null;
            String risk = node.path("riskLevel").asText("UNKNOWN").toUpperCase();
            if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(risk)) risk = "UNKNOWN";
            return new StructuredAnalysis(node.path("overview").asText(), strings(node.path("rootCauses")),
                    strings(node.path("investigationSteps")), strings(node.path("fixSuggestions")), risk);
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    String write(StructuredAnalysis analysis) throws JsonProcessingException {
        return analysis == null ? null : json.writeValueAsString(analysis);
    }

    StructuredAnalysis read(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return json.readValue(value, StructuredAnalysis.class);
        } catch (JsonProcessingException ignored) {
            return null;
        }
    }

    private List<String> strings(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node.isArray()) node.forEach(item -> {
            if (item.isTextual() && !item.asText().isBlank()) values.add(item.asText());
        });
        return values;
    }
}
