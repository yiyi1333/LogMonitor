package com.logmonitor.service;

import com.logmonitor.mapper.LlmMapper;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.model.ApiModels.ErrorOccurrenceDetail;
import com.logmonitor.model.LlmModels.Completion;
import com.logmonitor.model.LlmModels.Connection;
import com.logmonitor.model.LlmModels.OccurrenceAnalysisRow;
import com.logmonitor.model.LlmModels.OccurrenceAnalysisView;
import com.logmonitor.model.LlmModels.StructuredAnalysis;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class OccurrenceAiAnalysisService {
    public static final String PROMPT_VERSION = "v4-occurrence-localized";
    private final LogMonitorMapper logMapper;
    private final LlmMapper llmMapper;
    private final LlmConfigurationService configurations;
    private final LlmClientService client;
    private final AiAnalysisSupport support;
    private final TaskExecutor taskExecutor;

    public OccurrenceAiAnalysisService(LogMonitorMapper logMapper, LlmMapper llmMapper,
                                       LlmConfigurationService configurations, LlmClientService client,
                                       AiAnalysisSupport support, TaskExecutor taskExecutor) {
        this.logMapper = logMapper;
        this.llmMapper = llmMapper;
        this.configurations = configurations;
        this.client = client;
        this.support = support;
        this.taskExecutor = taskExecutor;
    }

    public OccurrenceAnalysisView request(long occurrenceId, long userId, String username,
                                          boolean refresh, String locale) {
        ErrorOccurrenceDetail occurrence = requireOccurrence(occurrenceId);
        Connection connection = configurations.resolveConnection(userId);
        OccurrenceAnalysisRow existing = llmMapper.occurrenceAnalysis(
                occurrenceId, connection.modelConfigId(), PROMPT_VERSION, locale);
        if (existing != null && "RUNNING".equals(existing.status())) return view(existing);
        if (existing != null && "SUCCESS".equals(existing.status()) && !refresh) return view(existing);

        long analysisId;
        if (existing == null) {
            Map<String, Object> values = new HashMap<>();
            values.put("occurrenceId", occurrenceId);
            values.put("providerId", connection.providerId());
            values.put("modelId", connection.modelConfigId());
            values.put("providerName", connection.providerName());
            values.put("providerType", connection.providerType());
            values.put("modelName", connection.modelId());
            values.put("promptVersion", PROMPT_VERSION);
            values.put("locale", locale);
            values.put("requestedBy", username);
            try {
                llmMapper.insertOccurrenceAnalysis(values);
                analysisId = ((Number) values.get("id")).longValue();
            } catch (DuplicateKeyException duplicate) {
                existing = llmMapper.occurrenceAnalysis(
                        occurrenceId, connection.modelConfigId(), PROMPT_VERSION, locale);
                if (existing == null) throw duplicate;
                if ("RUNNING".equals(existing.status()) || ("SUCCESS".equals(existing.status()) && !refresh)) {
                    return view(existing);
                }
                analysisId = existing.id();
                if (llmMapper.restartOccurrenceAnalysis(analysisId, username) == 0) {
                    return view(llmMapper.occurrenceAnalysisById(analysisId));
                }
            }
        } else {
            analysisId = existing.id();
            if (llmMapper.restartOccurrenceAnalysis(analysisId, username) == 0) {
                return view(llmMapper.occurrenceAnalysisById(analysisId));
            }
        }

        long queuedId = analysisId;
        taskExecutor.execute(() -> analyze(queuedId, occurrence, connection, locale));
        return view(llmMapper.occurrenceAnalysisById(analysisId));
    }

    public OccurrenceAnalysisView current(long occurrenceId, long userId, String locale) {
        requireOccurrence(occurrenceId);
        Connection connection = configurations.resolveConnection(userId);
        OccurrenceAnalysisRow row = llmMapper.occurrenceAnalysis(
                occurrenceId, connection.modelConfigId(), PROMPT_VERSION, locale);
        if (row == null) row = llmMapper.fallbackOccurrenceAnalysis(occurrenceId, locale);
        return view(row);
    }

    public List<OccurrenceAnalysisView> history(long occurrenceId) {
        requireOccurrence(occurrenceId);
        return llmMapper.occurrenceAnalyses(occurrenceId).stream().map(this::view).toList();
    }

    private void analyze(long analysisId, ErrorOccurrenceDetail occurrence,
                         Connection connection, String locale) {
        try {
            Completion completion = client.complete(connection, support.systemPrompt(locale), context(occurrence), false);
            StructuredAnalysis structured = support.parse(completion.content());
            llmMapper.completeOccurrenceAnalysis(analysisId, support.write(structured), completion.content(),
                    completion.promptTokens(), completion.completionTokens());
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            message = message.replace(connection.apiKey(), "***");
            llmMapper.failOccurrenceAnalysis(analysisId, message.substring(0, Math.min(2000, message.length())));
        }
    }

    private ErrorOccurrenceDetail requireOccurrence(long occurrenceId) {
        ErrorOccurrenceDetail row = logMapper.errorOccurrence(occurrenceId);
        if (row == null) throw new IllegalArgumentException("错误记录不存在");
        return row;
    }

    private String context(ErrorOccurrenceDetail occurrence) {
        return new StringBuilder()
                .append("服务: ").append(occurrence.service()).append('\n')
                .append("分类: ").append(occurrence.category()).append('\n')
                .append("异常: ").append(value(occurrence.exceptionClass())).append('\n')
                .append("摘要: ").append(value(occurrence.summary())).append('\n')
                .append("发生时间: ").append(occurrence.occurredAt()).append('\n')
                .append("线程: ").append(value(occurrence.threadName())).append('\n')
                .append("来源: ").append(value(occurrence.sourceName())).append(" · ")
                .append(value(occurrence.sourcePath())).append('\n')
                .append("推断接口: ").append(value(occurrence.inferredUri())).append('\n')
                .append("脱敏消息:\n").append(value(occurrence.messageText())).append('\n')
                .append("脱敏堆栈:\n").append(value(occurrence.stackTrace()))
                .toString();
    }

    private String value(String value) {
        return value == null || value.isBlank() ? "未提供" : value;
    }

    private OccurrenceAnalysisView view(OccurrenceAnalysisRow row) {
        if (row == null) return null;
        return new OccurrenceAnalysisView(row.id(), row.occurrenceId(), row.modelConfigId(),
                row.providerName(), row.providerType(), row.modelName(), row.promptVersion(), row.locale(),
                row.requestedBy(), row.status(), support.read(row.resultJson()), row.resultText(),
                row.promptTokens(), row.completionTokens(), row.failureReason(), row.createdAt(), row.updatedAt());
    }
}
