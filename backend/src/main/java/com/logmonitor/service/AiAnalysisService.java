package com.logmonitor.service;

import com.logmonitor.mapper.LlmMapper;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.model.ApiModels.ErrorGroupRow;
import com.logmonitor.model.ApiModels.ErrorOccurrenceRow;
import com.logmonitor.model.LlmModels.AnalysisRow;
import com.logmonitor.model.LlmModels.AnalysisView;
import com.logmonitor.model.LlmModels.Completion;
import com.logmonitor.model.LlmModels.Connection;
import com.logmonitor.model.LlmModels.StructuredAnalysis;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.task.TaskExecutor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class AiAnalysisService {
    public static final String PROMPT_VERSION = "v3-localized";
    private final LogMonitorMapper logMapper;
    private final LlmMapper llmMapper;
    private final LlmConfigurationService configurations;
    private final LlmClientService client;
    private final AiAnalysisSupport support;
    private final TaskExecutor taskExecutor;

    public AiAnalysisService(LogMonitorMapper logMapper, LlmMapper llmMapper,
                             LlmConfigurationService configurations, LlmClientService client,
                             AiAnalysisSupport support, TaskExecutor taskExecutor) {
        this.logMapper = logMapper;
        this.llmMapper = llmMapper;
        this.configurations = configurations;
        this.client = client;
        this.support = support;
        this.taskExecutor = taskExecutor;
    }

    public AnalysisView request(long groupId, long userId, String username, boolean refresh, String locale) {
        ErrorGroupRow group = logMapper.errorGroup(groupId);
        if (group == null) throw new IllegalArgumentException("错误分组不存在");
        Connection connection = configurations.resolveConnection(userId);
        AnalysisRow existing = llmMapper.analysis(groupId, connection.modelConfigId(), PROMPT_VERSION, locale);
        if (existing != null && "RUNNING".equals(existing.status())) return view(existing);
        if (existing != null && "SUCCESS".equals(existing.status()) && !refresh) return view(existing);

        long analysisId;
        if (existing == null) {
            Map<String, Object> values = new HashMap<>();
            values.put("groupId", groupId);
            values.put("providerId", connection.providerId());
            values.put("modelId", connection.modelConfigId());
            values.put("providerName", connection.providerName());
            values.put("providerType", connection.providerType());
            values.put("modelName", connection.modelId());
            values.put("promptVersion", PROMPT_VERSION);
            values.put("locale", locale);
            values.put("requestedBy", username);
            try {
                llmMapper.insertAnalysis(values);
                analysisId = ((Number) values.get("id")).longValue();
            } catch (DuplicateKeyException duplicate) {
                existing = llmMapper.analysis(groupId, connection.modelConfigId(), PROMPT_VERSION, locale);
                if (existing == null) throw duplicate;
                if ("RUNNING".equals(existing.status()) || ("SUCCESS".equals(existing.status()) && !refresh)) {
                    return view(existing);
                }
                analysisId = existing.id();
                if (llmMapper.restartAnalysis(analysisId, username) == 0) {
                    return view(llmMapper.analysisById(analysisId));
                }
            }
        } else {
            analysisId = existing.id();
            if (llmMapper.restartAnalysis(analysisId, username) == 0) {
                return view(llmMapper.analysisById(analysisId));
            }
        }

        long queuedId = analysisId;
        taskExecutor.execute(() -> analyze(queuedId, group, connection, locale));
        return view(llmMapper.analysisById(analysisId));
    }

    public AnalysisView current(long groupId, long userId, String locale) {
        Connection connection = configurations.resolveConnection(userId);
        AnalysisRow row = llmMapper.analysis(groupId, connection.modelConfigId(), PROMPT_VERSION, locale);
        if (row == null) row = llmMapper.fallbackAnalysis(groupId, locale);
        return view(row);
    }

    public List<AnalysisView> history(long groupId) {
        if (logMapper.errorGroup(groupId) == null) throw new IllegalArgumentException("错误分组不存在");
        return llmMapper.analyses(groupId).stream().map(this::view).toList();
    }

    private void analyze(long analysisId, ErrorGroupRow group, Connection connection, String locale) {
        try {
            Completion completion = client.complete(connection, support.systemPrompt(locale), context(group), false);
            StructuredAnalysis structured = support.parse(completion.content());
            llmMapper.completeAnalysis(analysisId, support.write(structured), completion.content(),
                    completion.promptTokens(), completion.completionTokens());
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            message = message.replace(connection.apiKey(), "***");
            llmMapper.failAnalysis(analysisId, message.substring(0, Math.min(2000, message.length())));
        }
    }

    private String context(ErrorGroupRow group) {
        List<ErrorOccurrenceRow> samples = logMapper.occurrences(group.id(), null, null, 3, 0);
        StringBuilder context = new StringBuilder()
                .append("服务: ").append(group.service()).append('\n')
                .append("分类: ").append(group.category()).append('\n')
                .append("异常: ").append(group.exceptionClass()).append('\n')
                .append("摘要: ").append(group.summary()).append('\n')
                .append("发生次数: ").append(group.occurrenceCount()).append('\n')
                .append("推断接口: ").append(group.inferredUri() == null ? "未关联" : group.inferredUri()).append('\n');
        for (int i = 0; i < samples.size(); i++) {
            context.append("\n样本 ").append(i + 1).append(":\n").append(samples.get(i).stackTrace());
        }
        return context.toString();
    }

    private AnalysisView view(AnalysisRow row) {
        if (row == null) return null;
        return new AnalysisView(row.id(), row.groupId(), row.modelConfigId(), row.providerName(),
                row.providerType(), row.modelName(), row.promptVersion(), row.locale(), row.requestedBy(), row.status(),
                support.read(row.resultJson()), row.resultText(), row.promptTokens(), row.completionTokens(), row.failureReason(),
                row.createdAt(), row.updatedAt());
    }
}
