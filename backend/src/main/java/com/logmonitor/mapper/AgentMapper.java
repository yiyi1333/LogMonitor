package com.logmonitor.mapper;

import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.model.CollectorAgent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentMapper {
    String AGENT_COLUMNS = "id,agent_uuid AS agentUuid,name,host_name AS hostName,display_address AS displayAddress," +
            "agent_version AS agentVersion,token_hash AS tokenHash,config_revision AS configRevision," +
            "spool_bytes AS spoolBytes,spool_limit_bytes AS spoolLimitBytes,last_seen_at AS lastSeenAt," +
            "last_error AS lastError,created_by AS createdBy,enabled,created_at AS createdAt,deleted_at AS deletedAt";
    @Insert("INSERT INTO collector_agent(agent_uuid,name,normalized_name,host_name,display_address,agent_version,token_hash," +
            "config_revision,spool_limit_bytes,created_by,enabled,created_at,updated_at) VALUES(" +
            "#{agentUuid},#{name},LOWER(#{name}),#{hostName},#{displayAddress},#{version},#{tokenHash},1,#{spoolLimit}," +
            "#{createdBy},TRUE,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertAgent(AgentInsert row);

    @Insert("INSERT INTO agent_allowed_root(agent_id,configured_path,real_path,path_hash) " +
            "VALUES(#{agentId},#{path},#{realPath},#{pathHash})")
    void insertRoot(@Param("agentId") long agentId, @Param("path") String path,
                    @Param("realPath") String realPath, @Param("pathHash") String pathHash);

    @Select("SELECT " + AGENT_COLUMNS + " FROM collector_agent WHERE normalized_name=#{name} AND deleted_at IS NULL")
    CollectorAgent activeAgentByName(String name);
    @Select("SELECT " + AGENT_COLUMNS + " FROM collector_agent WHERE id=#{id} AND deleted_at IS NULL")
    CollectorAgent activeAgent(long id);
    @Select("SELECT " + AGENT_COLUMNS + " FROM collector_agent WHERE token_hash=#{tokenHash} AND deleted_at IS NULL")
    CollectorAgent agentByTokenHash(String tokenHash);
    @Select("SELECT " + AGENT_COLUMNS + " FROM collector_agent WHERE deleted_at IS NULL ORDER BY name")
    List<CollectorAgent> activeAgents();
    @Select("SELECT " + AGENT_COLUMNS + " FROM collector_agent ORDER BY name")
    List<CollectorAgent> allAgents();
    @Select("SELECT real_path FROM agent_allowed_root WHERE agent_id=#{agentId} ORDER BY id")
    List<String> allowedRoots(long agentId);

    @Update("UPDATE collector_agent SET agent_version=#{version},display_address=COALESCE(#{displayAddress},display_address),spool_bytes=#{spoolBytes}," +
            "spool_limit_bytes=#{spoolLimit},last_seen_at=#{seenAt},last_error=#{lastError}," +
            "updated_at=CURRENT_TIMESTAMP WHERE id=#{agentId} AND enabled=TRUE AND deleted_at IS NULL")
    int updateHeartbeat(@Param("agentId") long agentId, @Param("version") String version,
                        @Param("displayAddress") String displayAddress,
                        @Param("spoolBytes") long spoolBytes, @Param("spoolLimit") long spoolLimit,
                        @Param("seenAt") Instant seenAt, @Param("lastError") String lastError);

    @Update("UPDATE log_source SET validation_status=#{status},validation_error=#{error}," +
            "real_path=COALESCE(#{realPath},real_path),real_path_hash=COALESCE(#{pathHash},real_path_hash)," +
            "updated_at=CURRENT_TIMESTAMP WHERE id=#{sourceId} AND agent_id=#{agentId} AND deleted_at IS NULL")
    int updateSourceReport(@Param("agentId") long agentId, @Param("sourceId") long sourceId,
                           @Param("status") String status, @Param("error") String error,
                           @Param("realPath") String realPath, @Param("pathHash") String pathHash);

    @Select("SELECT id,name,directory_path AS path,real_path AS realPath,real_path_hash AS realPathHash," +
            "include_pattern AS includePattern,exclude_pattern AS excludePattern,charset_name AS charsetName," +
            "uri_normalizers AS uriNormalizersText,collector_type AS collectorType,agent_id AS agentId," +
            "instance_key AS instanceKey,application_namespace AS applicationNamespace," +
            "validation_status AS validationStatus,validation_error AS validationError," +
            "start_mode AS startMode,namespace_migration_status AS namespaceMigrationStatus,created_at AS createdAt,deleted_at AS deletedAt FROM log_source " +
            "WHERE agent_id=#{agentId} AND deleted_at IS NULL ORDER BY id")
    @ResultMap("com.logmonitor.mapper.LogMonitorMapper.logSourceResult")
    List<Source> agentSources(long agentId);

    @Update("UPDATE collector_agent SET config_revision=config_revision+1,updated_at=CURRENT_TIMESTAMP " +
            "WHERE id=#{agentId} AND deleted_at IS NULL")
    int bumpConfigRevision(long agentId);

    @Update("UPDATE collector_agent SET enabled=FALSE,token_hash=CONCAT('revoked-',agent_uuid)," +
            "deleted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted_at IS NULL")
    int revokeAgent(long id);
    @Update("UPDATE log_source SET deleted_at=CURRENT_TIMESTAMP,validation_status='DISABLED'," +
            "updated_at=CURRENT_TIMESTAMP WHERE agent_id=#{agentId} AND deleted_at IS NULL")
    int disableAgentSources(long agentId);
    @Delete("DELETE FROM collector_checkpoint WHERE instance_key=#{instanceKey}")
    int deleteAgentCheckpoints(String instanceKey);

    @Select("SELECT COUNT(*) FROM agent_ingest_batch WHERE batch_id=#{batchId}")
    long batchExists(String batchId);
    @Insert("INSERT INTO agent_ingest_batch(batch_id,agent_id,source_id,file_key,stream_generation," +
            "start_offset,end_offset,checksum,received_at) VALUES(#{batchId},#{agentId},#{sourceId}," +
            "#{fileKey},#{generation},#{startOffset},#{endOffset},#{checksum},CURRENT_TIMESTAMP)")
    void insertBatch(BatchInsert row);
    @Delete("DELETE FROM agent_ingest_batch WHERE received_at < #{cutoff}")
    void deleteOldBatches(Instant cutoff);

    class AgentInsert {
        public Long id;
        public String agentUuid;
        public String name;
        public String hostName;
        public String displayAddress;
        public String version;
        public String tokenHash;
        public long spoolLimit;
        public String createdBy;
    }

    record BatchInsert(String batchId, long agentId, long sourceId, String fileKey, String generation,
                       long startOffset, long endOffset, String checksum) {}
}
