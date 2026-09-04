package com.logmonitor.mapper;

import com.logmonitor.model.LlmModels.AnalysisRow;
import com.logmonitor.model.LlmModels.ModelInsert;
import com.logmonitor.model.LlmModels.ModelOption;
import com.logmonitor.model.LlmModels.ModelRow;
import com.logmonitor.model.LlmModels.OccurrenceAnalysisRow;
import com.logmonitor.model.LlmModels.ProviderInsert;
import com.logmonitor.model.LlmModels.ProviderRow;
import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface LlmMapper {
    @Select("SELECT * FROM llm_provider_config ORDER BY created_at,id")
    List<ProviderRow> providers();

    @Select("SELECT * FROM llm_provider_config WHERE id=#{id}")
    ProviderRow provider(long id);

    @Insert("INSERT INTO llm_provider_config(name,normalized_name,provider_type,protocol_type,base_url," +
            "api_key_ciphertext,api_key_nonce,enabled,created_by,created_at,updated_at) VALUES(" +
            "#{name},#{normalizedName},#{providerType},#{protocolType},#{baseUrl},#{apiKeyCiphertext}," +
            "#{apiKeyNonce},#{enabled},#{createdBy},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertProvider(ProviderInsert provider);

    @Update("<script>UPDATE llm_provider_config SET name=#{name},normalized_name=#{normalizedName}," +
            "provider_type=#{providerType},protocol_type=#{protocolType},base_url=#{baseUrl}," +
            "updated_at=CURRENT_TIMESTAMP<if test='ciphertext != null'>,api_key_ciphertext=#{ciphertext}," +
            "api_key_nonce=#{nonce},key_version=key_version+1</if> WHERE id=#{id}</script>")
    int updateProvider(@Param("id") long id, @Param("name") String name,
                       @Param("normalizedName") String normalizedName,
                       @Param("providerType") String providerType,
                       @Param("protocolType") String protocolType,
                       @Param("baseUrl") String baseUrl,
                       @Param("ciphertext") String ciphertext, @Param("nonce") String nonce);

    @Update("UPDATE llm_provider_config SET enabled=#{enabled},updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int updateProviderStatus(@Param("id") long id, @Param("enabled") boolean enabled);

    @Update("UPDATE llm_provider_config SET last_test_status=#{status},last_test_error=#{error}," +
            "last_tested_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void updateProviderTest(@Param("id") long id, @Param("status") String status, @Param("error") String error);

    @Delete("DELETE FROM llm_provider_config WHERE id=#{id}")
    int deleteProvider(long id);

    @Select("SELECT * FROM llm_model_config WHERE provider_id=#{providerId} ORDER BY created_at,id")
    List<ModelRow> models(long providerId);

    @Select("SELECT * FROM llm_model_config WHERE id=#{id}")
    ModelRow model(long id);

    @Insert("INSERT INTO llm_model_config(provider_id,model_id,normalized_model_id,display_name,enabled," +
            "created_at,updated_at) VALUES(#{providerId},#{modelId},#{normalizedModelId},#{displayName}," +
            "#{enabled},CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertModel(ModelInsert model);

    @Update("UPDATE llm_model_config SET model_id=#{modelId},normalized_model_id=#{normalizedModelId}," +
            "display_name=#{displayName},updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND provider_id=#{providerId}")
    int updateModel(@Param("providerId") long providerId, @Param("id") long id,
                    @Param("modelId") String modelId, @Param("normalizedModelId") String normalizedModelId,
                    @Param("displayName") String displayName);

    @Update("UPDATE llm_model_config SET enabled=#{enabled},updated_at=CURRENT_TIMESTAMP " +
            "WHERE id=#{id} AND provider_id=#{providerId}")
    int updateModelStatus(@Param("providerId") long providerId, @Param("id") long id,
                          @Param("enabled") boolean enabled);

    @Delete("DELETE FROM llm_model_config WHERE id=#{id} AND provider_id=#{providerId}")
    int deleteModel(@Param("providerId") long providerId, @Param("id") long id);

    @Select("SELECT m.id,m.provider_id,p.name provider_name,p.provider_type,m.model_id,m.display_name," +
            "(m.enabled AND p.enabled) enabled FROM llm_model_config m JOIN llm_provider_config p " +
            "ON p.id=m.provider_id ORDER BY p.created_at,p.id,m.created_at,m.id")
    List<ModelOption> modelOptions();

    @Select("SELECT m.id,m.provider_id,p.name provider_name,p.provider_type,m.model_id,m.display_name," +
            "TRUE enabled FROM llm_model_config m JOIN llm_provider_config p ON p.id=m.provider_id " +
            "WHERE m.id=#{id} AND m.enabled=TRUE AND p.enabled=TRUE")
    ModelOption availableModel(long id);

    @Select("SELECT m.id,m.provider_id,p.name provider_name,p.provider_type,m.model_id,m.display_name," +
            "TRUE enabled FROM llm_model_config m JOIN llm_provider_config p ON p.id=m.provider_id " +
            "WHERE m.enabled=TRUE AND p.enabled=TRUE ORDER BY p.created_at,p.id,m.created_at,m.id LIMIT 1")
    ModelOption firstAvailableModel();

    @Select("SELECT model_config_id FROM user_llm_preference WHERE user_id=#{userId}")
    Long preference(long userId);

    @Select("SELECT COUNT(*) FROM user_llm_preference WHERE user_id=#{userId}")
    int preferenceCount(long userId);

    @Insert("INSERT INTO user_llm_preference(user_id,model_config_id,updated_at) " +
            "VALUES(#{userId},#{modelId},CURRENT_TIMESTAMP)")
    void insertPreference(@Param("userId") long userId, @Param("modelId") long modelId);

    @Update("UPDATE user_llm_preference SET model_config_id=#{modelId},updated_at=CURRENT_TIMESTAMP WHERE user_id=#{userId}")
    void updatePreference(@Param("userId") long userId, @Param("modelId") long modelId);

    @Select("SELECT default_model_id FROM llm_system_setting WHERE setting_id=1")
    Long defaultModelId();

    @Update("UPDATE llm_system_setting SET default_model_id=#{modelId},updated_at=CURRENT_TIMESTAMP WHERE setting_id=1")
    void setDefaultModel(@Param("modelId") Long modelId);

    @Select("SELECT p.id provider_id,m.id model_config_id,p.name provider_name,p.provider_type,p.protocol_type," +
            "p.base_url,p.api_key_ciphertext,p.api_key_nonce,m.model_id FROM llm_model_config m " +
            "JOIN llm_provider_config p ON p.id=m.provider_id WHERE m.id=#{modelId} " +
            "AND m.enabled=TRUE AND p.enabled=TRUE")
    java.util.Map<String, Object> connection(long modelId);

    @Select("SELECT * FROM ai_analysis WHERE group_id=#{groupId} AND model_config_id=#{modelId} " +
            "AND prompt_version=#{promptVersion} AND locale=#{locale} ORDER BY id LIMIT 1")
    AnalysisRow analysis(@Param("groupId") long groupId, @Param("modelId") long modelId,
                         @Param("promptVersion") String promptVersion, @Param("locale") String locale);

    @Select("SELECT * FROM ai_analysis WHERE group_id=#{groupId} AND locale<>#{locale} AND status='SUCCESS' " +
            "ORDER BY updated_at DESC,id DESC LIMIT 1")
    AnalysisRow fallbackAnalysis(@Param("groupId") long groupId, @Param("locale") String locale);

    @Select("SELECT * FROM ai_analysis WHERE group_id=#{groupId} ORDER BY updated_at DESC,id DESC")
    List<AnalysisRow> analyses(long groupId);

    @Insert("INSERT INTO ai_analysis(group_id,provider_config_id,model_config_id,provider_name,provider_type," +
            "model_name,prompt_version,locale,requested_by,status,created_at,updated_at) VALUES(#{groupId},#{providerId}," +
            "#{modelId},#{providerName},#{providerType},#{modelName},#{promptVersion},#{locale},#{requestedBy},'RUNNING'," +
            "CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertAnalysis(java.util.Map<String, Object> values);

    @Update("UPDATE ai_analysis SET requested_by=#{requestedBy},status='RUNNING',result_json=NULL,result_text=NULL," +
            "prompt_tokens=NULL,completion_tokens=NULL,failure_reason=NULL,updated_at=CURRENT_TIMESTAMP " +
            "WHERE id=#{id} AND status<>'RUNNING'")
    int restartAnalysis(@Param("id") long id, @Param("requestedBy") String requestedBy);

    @Update("UPDATE ai_analysis SET status='SUCCESS',result_json=#{resultJson},result_text=#{resultText}," +
            "prompt_tokens=#{promptTokens},completion_tokens=#{completionTokens},failure_reason=NULL," +
            "updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void completeAnalysis(@Param("id") long id, @Param("resultJson") String resultJson,
                          @Param("resultText") String resultText, @Param("promptTokens") Long promptTokens,
                          @Param("completionTokens") Long completionTokens);

    @Update("UPDATE ai_analysis SET status='FAILED',failure_reason=#{failure},updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void failAnalysis(@Param("id") long id, @Param("failure") String failure);

    @Select("SELECT * FROM ai_analysis WHERE id=#{id}")
    AnalysisRow analysisById(long id);

    @Select("SELECT * FROM error_occurrence_ai_analysis WHERE occurrence_id=#{occurrenceId} " +
            "AND model_config_id=#{modelId} AND prompt_version=#{promptVersion} AND locale=#{locale} ORDER BY id LIMIT 1")
    OccurrenceAnalysisRow occurrenceAnalysis(@Param("occurrenceId") long occurrenceId,
                                             @Param("modelId") long modelId,
                                             @Param("promptVersion") String promptVersion,
                                             @Param("locale") String locale);

    @Select("SELECT * FROM error_occurrence_ai_analysis WHERE occurrence_id=#{occurrenceId} " +
            "AND locale&lt;&gt;#{locale} AND status='SUCCESS' ORDER BY updated_at DESC,id DESC LIMIT 1")
    OccurrenceAnalysisRow fallbackOccurrenceAnalysis(@Param("occurrenceId") long occurrenceId,
                                                      @Param("locale") String locale);

    @Select("SELECT * FROM error_occurrence_ai_analysis WHERE occurrence_id=#{occurrenceId} " +
            "ORDER BY updated_at DESC,id DESC")
    List<OccurrenceAnalysisRow> occurrenceAnalyses(long occurrenceId);

    @Insert("INSERT INTO error_occurrence_ai_analysis(occurrence_id,provider_config_id,model_config_id," +
            "provider_name,provider_type,model_name,prompt_version,locale,requested_by,status,created_at,updated_at) " +
            "VALUES(#{occurrenceId},#{providerId},#{modelId},#{providerName},#{providerType},#{modelName}," +
            "#{promptVersion},#{locale},#{requestedBy},'RUNNING',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertOccurrenceAnalysis(java.util.Map<String, Object> values);

    @Update("UPDATE error_occurrence_ai_analysis SET requested_by=#{requestedBy},status='RUNNING'," +
            "result_json=NULL,result_text=NULL,prompt_tokens=NULL,completion_tokens=NULL,failure_reason=NULL," +
            "updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND status&lt;&gt;'RUNNING'")
    int restartOccurrenceAnalysis(@Param("id") long id, @Param("requestedBy") String requestedBy);

    @Update("UPDATE error_occurrence_ai_analysis SET status='SUCCESS',result_json=#{resultJson}," +
            "result_text=#{resultText},prompt_tokens=#{promptTokens},completion_tokens=#{completionTokens}," +
            "failure_reason=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void completeOccurrenceAnalysis(@Param("id") long id, @Param("resultJson") String resultJson,
                                    @Param("resultText") String resultText,
                                    @Param("promptTokens") Long promptTokens,
                                    @Param("completionTokens") Long completionTokens);

    @Update("UPDATE error_occurrence_ai_analysis SET status='FAILED',failure_reason=#{failure}," +
            "updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void failOccurrenceAnalysis(@Param("id") long id, @Param("failure") String failure);

    @Select("SELECT * FROM error_occurrence_ai_analysis WHERE id=#{id}")
    OccurrenceAnalysisRow occurrenceAnalysisById(long id);
}
