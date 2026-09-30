package com.logmonitor.mapper;

import com.logmonitor.model.ApiModels.EndpointRow;
import com.logmonitor.model.ApiModels.ErrorGroupRow;
import com.logmonitor.model.ApiModels.ErrorLogItem;
import com.logmonitor.model.ApiModels.ErrorOccurrenceDetail;
import com.logmonitor.model.ApiModels.ErrorOccurrenceRow;
import com.logmonitor.model.ApiModels.UserSummary;
import com.logmonitor.model.UserAccount;
import com.logmonitor.config.LogMonitorProperties.Source;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface LogMonitorMapper {
    @Select("SELECT 1")
    int databasePing();

    @Select("SELECT id,username,password_hash,role,enabled,must_change_password,session_version,created_at FROM app_user WHERE username=#{username} ORDER BY id LIMIT 1")
    UserAccount userAccount(String username);
    @Select("SELECT id,username,password_hash,role,enabled,must_change_password,session_version,created_at FROM app_user WHERE id=#{id} ORDER BY id LIMIT 1")
    UserAccount userAccountById(long id);
    @Select("SELECT COUNT(*) FROM app_user WHERE username=#{username}")
    long userCount(String username);
    @Insert("INSERT INTO app_user(username,password_hash,role,must_change_password) VALUES(#{username},#{passwordHash},#{role},#{mustChangePassword})")
    void insertUser(@Param("username") String username, @Param("passwordHash") String passwordHash,
                    @Param("role") String role, @Param("mustChangePassword") boolean mustChangePassword);
    @Update("UPDATE app_user SET password_hash=#{passwordHash},must_change_password=FALSE WHERE username=#{username}")
    int updateUserPassword(@Param("username") String username, @Param("passwordHash") String passwordHash);
    @Update("UPDATE app_user SET role='ROOT',enabled=TRUE,must_change_password=FALSE," +
            "session_version=session_version+1 WHERE username=#{username}")
    void promoteRootUser(String username);
    @Select("SELECT id,username,role,enabled,must_change_password,created_at FROM app_user WHERE role='USER' ORDER BY created_at DESC,id DESC LIMIT #{limit} OFFSET #{offset}")
    List<UserSummary> users(@Param("limit") int limit, @Param("offset") int offset);
    @Select("SELECT COUNT(*) FROM app_user WHERE role='USER'")
    long regularUserCount();
    @Update("UPDATE app_user SET enabled=#{enabled},session_version=session_version+1 " +
            "WHERE id=#{id} AND role='USER' AND enabled<>#{enabled}")
    int updateRegularUserStatus(@Param("id") long id, @Param("enabled") boolean enabled);
    @Delete("DELETE FROM app_user WHERE id=#{id} AND role='USER'")
    int deleteRegularUser(long id);

    @Select("SELECT id,name,directory_path AS path,real_path AS realPath,real_path_hash AS realPathHash," +
            "include_pattern AS includePattern,exclude_pattern AS excludePattern,charset_name AS charsetName," +
            "uri_normalizers AS uriNormalizersText,collector_type AS collectorType,agent_id AS agentId," +
            "instance_key AS instanceKey,application_namespace AS applicationNamespace," +
            "validation_status AS validationStatus,validation_error AS validationError," +
            "start_mode AS startMode,namespace_migration_status AS namespaceMigrationStatus,created_at AS createdAt,deleted_at AS deletedAt " +
            "FROM log_source WHERE deleted_at IS NULL ORDER BY name")
    @Results(id = "logSourceResult", value = {
            @Result(column = "id", property = "id", id = true),
            @Result(column = "name", property = "name"),
            @Result(column = "path", property = "path"),
            @Result(column = "realPath", property = "realPath"),
            @Result(column = "realPathHash", property = "realPathHash"),
            @Result(column = "includePattern", property = "includePattern"),
            @Result(column = "excludePattern", property = "excludePattern"),
            @Result(column = "charsetName", property = "charsetName"),
            @Result(column = "uriNormalizersText", property = "uriNormalizersText"),
            @Result(column = "collectorType", property = "collectorType"),
            @Result(column = "agentId", property = "agentId"),
            @Result(column = "instanceKey", property = "instanceKey"),
            @Result(column = "applicationNamespace", property = "applicationNamespace"),
            @Result(column = "validationStatus", property = "validationStatus"),
            @Result(column = "validationError", property = "validationError"),
            @Result(column = "startMode", property = "startMode"),
            @Result(column = "namespaceMigrationStatus", property = "namespaceMigrationStatus"),
            @Result(column = "createdAt", property = "createdAt"),
            @Result(column = "deletedAt", property = "deletedAt")
    })
    List<Source> activeLogSources();
    @Select("SELECT id,name,directory_path AS path,real_path AS realPath,real_path_hash AS realPathHash," +
            "include_pattern AS includePattern,exclude_pattern AS excludePattern,charset_name AS charsetName," +
            "uri_normalizers AS uriNormalizersText,collector_type AS collectorType,agent_id AS agentId," +
            "instance_key AS instanceKey,application_namespace AS applicationNamespace," +
            "validation_status AS validationStatus,validation_error AS validationError," +
            "start_mode AS startMode,namespace_migration_status AS namespaceMigrationStatus,created_at AS createdAt,deleted_at AS deletedAt " +
            "FROM log_source ORDER BY normalized_namespace,name,id")
    @ResultMap("logSourceResult")
    List<Source> allLogSources();
    @Select("SELECT id,name,directory_path AS path,real_path AS realPath,real_path_hash AS realPathHash," +
            "include_pattern AS includePattern,exclude_pattern AS excludePattern,charset_name AS charsetName," +
            "uri_normalizers AS uriNormalizersText,collector_type AS collectorType,agent_id AS agentId," +
            "instance_key AS instanceKey,application_namespace AS applicationNamespace," +
            "validation_status AS validationStatus,validation_error AS validationError," +
            "start_mode AS startMode,namespace_migration_status AS namespaceMigrationStatus,created_at AS createdAt,deleted_at AS deletedAt " +
            "FROM log_source WHERE id=#{id} AND deleted_at IS NULL")
    @ResultMap("logSourceResult")
    Source activeLogSource(long id);
    @Select("SELECT id,name,directory_path AS path,real_path AS realPath,real_path_hash AS realPathHash," +
            "include_pattern AS includePattern,exclude_pattern AS excludePattern,charset_name AS charsetName," +
            "uri_normalizers AS uriNormalizersText,collector_type AS collectorType,agent_id AS agentId," +
            "instance_key AS instanceKey,application_namespace AS applicationNamespace," +
            "validation_status AS validationStatus,validation_error AS validationError," +
            "start_mode AS startMode,namespace_migration_status AS namespaceMigrationStatus,created_at AS createdAt,deleted_at AS deletedAt " +
            "FROM log_source WHERE id=#{id}")
    @ResultMap("logSourceResult")
    Source logSource(long id);
    @Select("SELECT id,name,directory_path AS path,real_path AS realPath,real_path_hash AS realPathHash," +
            "include_pattern AS includePattern,exclude_pattern AS excludePattern,charset_name AS charsetName," +
            "uri_normalizers AS uriNormalizersText,collector_type AS collectorType,agent_id AS agentId," +
            "instance_key AS instanceKey,application_namespace AS applicationNamespace," +
            "validation_status AS validationStatus,validation_error AS validationError," +
            "start_mode AS startMode,namespace_migration_status AS namespaceMigrationStatus,created_at AS createdAt,deleted_at AS deletedAt " +
            "FROM log_source WHERE instance_key=#{instanceKey} AND normalized_name=#{normalizedName}")
    @ResultMap("logSourceResult")
    Source logSourceByName(@Param("instanceKey") String instanceKey, @Param("normalizedName") String normalizedName);
    @Select("SELECT id,name,directory_path AS path,real_path AS realPath,real_path_hash AS realPathHash," +
            "include_pattern AS includePattern,exclude_pattern AS excludePattern,charset_name AS charsetName," +
            "uri_normalizers AS uriNormalizersText,collector_type AS collectorType,agent_id AS agentId," +
            "instance_key AS instanceKey,application_namespace AS applicationNamespace," +
            "validation_status AS validationStatus,validation_error AS validationError," +
            "start_mode AS startMode,namespace_migration_status AS namespaceMigrationStatus,created_at AS createdAt,deleted_at AS deletedAt " +
            "FROM log_source WHERE instance_key=#{instanceKey} AND real_path_hash=#{pathHash}")
    @ResultMap("logSourceResult")
    Source logSourceByPathHash(@Param("instanceKey") String instanceKey, @Param("pathHash") String pathHash);
    @Insert("INSERT INTO log_source(name,normalized_name,directory_path,real_path,real_path_hash,include_pattern," +
            "exclude_pattern,charset_name,uri_normalizers,collector_type,agent_id,instance_key,application_namespace,normalized_namespace,validation_status," +
            "validation_error,start_mode,namespace_migration_status,created_at,updated_at) VALUES(#{name},LOWER(#{name}),#{path}," +
            "#{realPath},#{realPathHash},#{include},#{exclude},#{charsetName},#{uriNormalizersText},#{collectorType}," +
            "#{agentId},#{instanceKey},#{applicationNamespace},LOWER(#{applicationNamespace}),#{validationStatus},#{validationError},#{startMode},'IDLE',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertLogSource(Source source);
    @Update("UPDATE log_source SET name=#{name},directory_path=#{path},real_path=#{realPath},include_pattern=#{include}," +
            "exclude_pattern=#{exclude},charset_name=#{charsetName},uri_normalizers=#{uriNormalizersText}," +
            "collector_type=#{collectorType},agent_id=#{agentId},instance_key=#{instanceKey}," +
            "application_namespace=#{applicationNamespace},normalized_namespace=LOWER(#{applicationNamespace})," +
            "validation_status=#{validationStatus},validation_error=#{validationError},start_mode=#{startMode}," +
            "deleted_at=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int reactivateLogSource(Source source);
    @Update("UPDATE log_source SET uri_normalizers=#{uriNormalizersText}," +
            "updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted_at IS NULL")
    int updateLogSourceAdvanced(Source source);
    @Update("UPDATE log_source SET deleted_at=CURRENT_TIMESTAMP,updated_at=CURRENT_TIMESTAMP WHERE id=#{id} AND deleted_at IS NULL")
    int softDeleteLogSource(long id);
    @Delete("DELETE FROM collector_checkpoint WHERE source_id=#{sourceId}")
    int deleteSourceCheckpoints(long sourceId);
    @Select("SELECT setting_value FROM app_setting WHERE setting_key=#{key}")
    String appSetting(String key);
    @Insert("INSERT INTO app_setting(setting_key,setting_value,updated_at) VALUES(#{key},#{value},CURRENT_TIMESTAMP)")
    void insertAppSetting(@Param("key") String key, @Param("value") String value);

    @Insert("INSERT INTO source_namespace_migration(source_id,old_namespace,target_namespace,status,created_at) " +
            "VALUES(#{sourceId},#{oldNamespace},#{targetNamespace},'PENDING',CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id", keyColumn = "id")
    void insertNamespaceMigration(NamespaceMigration row);
    @Select("SELECT id,source_id AS sourceId,old_namespace AS oldNamespace,target_namespace AS targetNamespace," +
            "status,failure_reason AS failureReason FROM source_namespace_migration " +
            "WHERE status IN ('PENDING','RUNNING') ORDER BY id")
    List<NamespaceMigration> pendingNamespaceMigrations();
    @Select("SELECT COUNT(*) FROM source_namespace_migration WHERE source_id=#{sourceId} AND status IN ('PENDING','RUNNING')")
    long activeNamespaceMigration(long sourceId);
    @Update("UPDATE source_namespace_migration SET status='RUNNING',started_at=CURRENT_TIMESTAMP,failure_reason=NULL WHERE id=#{id}")
    int startNamespaceMigration(long id);
    @Update("UPDATE source_namespace_migration SET status='SUCCESS',completed_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int completeNamespaceMigration(long id);
    @Update("UPDATE source_namespace_migration SET status='FAILED',failure_reason=#{reason},completed_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int failNamespaceMigration(@Param("id") long id, @Param("reason") String reason);
    @Update("UPDATE log_source SET namespace_migration_status=#{status},updated_at=CURRENT_TIMESTAMP WHERE id=#{sourceId}")
    int updateSourceMigrationStatus(@Param("sourceId") long sourceId, @Param("status") String status);
    @Update("UPDATE log_source SET application_namespace=#{namespace},normalized_namespace=LOWER(#{namespace})," +
            "namespace_migration_status='IDLE',updated_at=CURRENT_TIMESTAMP WHERE id=#{sourceId}")
    int updateSourceNamespace(@Param("sourceId") long sourceId, @Param("namespace") String namespace);
    @Update("UPDATE api_access_minute SET service_name=#{namespace} WHERE source_id=#{sourceId}")
    int updateAccessNamespace(@Param("sourceId") long sourceId, @Param("namespace") String namespace);
    @Update("UPDATE access_dedup_baseline SET service_name=#{namespace} WHERE source_id=#{sourceId}")
    int updateBaselineNamespace(@Param("sourceId") long sourceId, @Param("namespace") String namespace);
    @Select("SELECT g.id,g.fingerprint,g.signature_hash AS signature,g.service_name AS service,g.category," +
            "g.exception_class AS exceptionClass,g.summary,g.first_seen AS firstSeen,g.last_seen AS lastSeen," +
            "g.occurrence_count AS totalCount,g.inferred_uri AS inferredUri,COUNT(o.id) AS sourceCount," +
            "MIN(o.occurred_at) AS sourceFirstSeen,MAX(o.occurred_at) AS sourceLastSeen " +
            "FROM error_group g JOIN error_occurrence o ON o.group_id=g.id WHERE o.source_id=#{sourceId} " +
            "GROUP BY g.id,g.fingerprint,g.signature_hash,g.service_name,g.category,g.exception_class,g.summary," +
            "g.first_seen,g.last_seen,g.occurrence_count,g.inferred_uri ORDER BY g.id")
    List<NamespaceErrorGroup> namespaceErrorGroups(long sourceId);
    @Select("SELECT stack_trace FROM error_occurrence WHERE group_id=#{groupId} ORDER BY id LIMIT 1")
    String representativeStack(long groupId);
    @Update("UPDATE error_group SET signature_hash=#{signature},fingerprint=#{fingerprint},updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int updateGroupIdentity(@Param("id") long id, @Param("signature") String signature, @Param("fingerprint") String fingerprint);
    @Update("UPDATE error_group SET fingerprint=#{fingerprint},signature_hash=#{signature},service_name=#{namespace}," +
            "updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int moveWholeGroup(@Param("id") long id, @Param("fingerprint") String fingerprint,
                       @Param("signature") String signature, @Param("namespace") String namespace);
    @Update("UPDATE error_occurrence SET group_id=#{targetGroupId} WHERE source_id=#{sourceId} AND group_id=#{oldGroupId}")
    int reassignSourceOccurrences(@Param("sourceId") long sourceId, @Param("oldGroupId") long oldGroupId,
                                  @Param("targetGroupId") long targetGroupId);
    @Update("UPDATE error_occurrence SET group_id=#{targetGroupId} WHERE group_id=#{oldGroupId}")
    int reassignSourceOccurrencesForGroup(@Param("oldGroupId") long oldGroupId,
                                          @Param("targetGroupId") long targetGroupId);
    @Update("UPDATE error_group SET first_seen=(SELECT MIN(occurred_at) FROM error_occurrence WHERE group_id=#{id})," +
            "last_seen=(SELECT MAX(occurred_at) FROM error_occurrence WHERE group_id=#{id})," +
            "occurrence_count=(SELECT COUNT(*) FROM error_occurrence WHERE group_id=#{id}),updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    int recomputeGroup(long id);
    @Delete("DELETE FROM error_group WHERE id=#{id} AND NOT EXISTS (SELECT 1 FROM error_occurrence WHERE group_id=#{id})")
    int deleteEmptyGroup(long id);
    @Delete("DELETE FROM ai_analysis WHERE group_id=#{groupId}")
    int deleteGroupAi(long groupId);
    @Select("SELECT g.id,g.service_name AS service,g.category,g.exception_class AS exceptionClass,g.summary," +
            "g.signature_hash AS signature,g.fingerprint FROM error_group g WHERE g.signature_hash IS NULL ORDER BY g.id")
    List<NamespaceErrorGroup> groupsWithoutSignature();

    @Select("SELECT id, access_count FROM api_access_minute WHERE source_id=#{sourceId} " +
            "AND uri_hash=#{uriHash} AND minute_at=#{minute} ORDER BY id LIMIT 1")
    Map<String, Object> findAccess(@Param("sourceId") long sourceId,
                                   @Param("uriHash") String uriHash, @Param("minute") Instant minute);

    @Insert("INSERT INTO api_access_minute(service_name,instance_key,source_id,uri,uri_hash,method,minute_at,access_count) " +
            "VALUES(#{service},#{instanceKey},#{sourceId},#{uri},#{uriHash},'UNKNOWN',#{minute},#{count})")
    void insertAccess(@Param("service") String service, @Param("instanceKey") String instanceKey, @Param("sourceId") long sourceId,
                      @Param("uri") String uri, @Param("uriHash") String uriHash,
                      @Param("minute") Instant minute, @Param("count") long count);

    @Update("UPDATE api_access_minute SET access_count=access_count+#{count} WHERE id=#{id}")
    void incrementAccess(@Param("id") long id, @Param("count") long count);

    @Select("SELECT COUNT(*) FROM access_event_dedup WHERE event_key=#{eventKey}")
    long accessEventExists(String eventKey);
    @Insert("INSERT INTO access_event_dedup(event_key,occurred_at) VALUES(#{eventKey},#{occurredAt})")
    void insertAccessEvent(@Param("eventKey") String eventKey,@Param("occurredAt") Instant occurredAt);
    @Select("SELECT COUNT(*) FROM access_dedup_baseline WHERE source_id=#{sourceId} AND uri_hash=#{uriHash} " +
            "AND minute_at=#{minute} AND #{occurredAt} <= baseline_until")
    long accessBaselineExistsBySource(@Param("sourceId") long sourceId,@Param("uriHash") String uriHash,
                                      @Param("minute") Instant minute,@Param("occurredAt") Instant occurredAt);
    @Select("SELECT COUNT(*) FROM access_dedup_baseline WHERE service_name=#{service} AND instance_key=#{instanceKey} " +
            "AND uri_hash=#{uriHash} AND minute_at=#{minute} AND #{occurredAt} <= baseline_until")
    long accessBaselineExists(@Param("service") String service,@Param("instanceKey") String instanceKey,
                              @Param("uriHash") String uriHash,@Param("minute") Instant minute,
                              @Param("occurredAt") Instant occurredAt);

    @Select("SELECT g.id,g.fingerprint,g.service_name AS service,g.service_name AS applicationNamespace," +
            "(SELECT CASE WHEN COUNT(DISTINCT o.source_id)=1 THEN MIN(o.source_id) END FROM error_occurrence o WHERE o.group_id=g.id) AS sourceId,g.category," +
            "exception_class,summary,first_seen,last_seen,occurrence_count,inferred_uri " +
            "FROM error_group g WHERE fingerprint=#{fingerprint} ORDER BY id LIMIT 1")
    ErrorGroupRow findErrorGroupByFingerprint(String fingerprint);

    @Insert("INSERT INTO error_group(fingerprint,signature_hash,service_name,category,exception_class,summary,first_seen,last_seen,occurrence_count,inferred_uri,updated_at) " +
            "VALUES(#{fingerprint},#{signature},#{service},#{category},#{exceptionClass},#{summary},#{firstSeen},#{lastSeen},#{count},#{inferredUri},CURRENT_TIMESTAMP)")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    void insertErrorGroup(ErrorGroupInsert row);

    @Update("UPDATE error_group SET first_seen=LEAST(first_seen,#{firstSeen}), last_seen=GREATEST(last_seen,#{lastSeen}), " +
            "occurrence_count=occurrence_count+#{count}, " +
            "inferred_uri=COALESCE(#{inferredUri},inferred_uri), updated_at=CURRENT_TIMESTAMP WHERE id=#{id}")
    void incrementErrorGroup(@Param("id") long id, @Param("firstSeen") Instant firstSeen,
                             @Param("lastSeen") Instant lastSeen, @Param("count") long count,
                             @Param("inferredUri") String inferredUri);

    @Insert("INSERT INTO error_occurrence(group_id,occurred_at,thread_name,message_text,stack_trace,inferred_uri," +
            "association_type,source_path,source_offset,event_key,instance_key,source_id) " +
            "VALUES(#{groupId},#{occurredAt},#{thread},#{message},#{stackTrace},#{inferredUri},#{associationType}," +
            "#{sourcePath},#{sourceOffset},#{eventKey},#{instanceKey},#{sourceId})")
    void insertOccurrence(OccurrenceInsert row);

    @Select("SELECT COUNT(*) FROM error_occurrence WHERE event_key=#{eventKey}")
    long occurrenceExists(String eventKey);

    @Select("SELECT * FROM collector_checkpoint WHERE source_id=#{sourceId} " +
            "AND file_key=#{fileKey} ORDER BY id LIMIT 1")
    Map<String, Object> findCheckpoint(@Param("sourceId") long sourceId, @Param("fileKey") String fileKey);

    @Insert("INSERT INTO collector_checkpoint(source_name,instance_key,source_id,file_key,stream_generation,file_path," +
            "byte_offset,pending_offset,pending_text,pending_bytes,status,file_size,last_modified_at,last_collected_at," +
            "last_event_at,last_error,parse_error_count) VALUES(#{source},#{instanceKey},#{sourceId},#{fileKey}," +
            "#{generation},#{path},#{offset},#{pendingOffset},#{pending},#{pendingBytes},#{status},#{fileSize}," +
            "#{modifiedAt},#{collectedAt},#{lastEventAt},#{lastError},#{parseErrors})")
    void insertCheckpoint(Checkpoint row);

    @Update("UPDATE collector_checkpoint SET file_path=#{path},byte_offset=#{offset},pending_offset=#{pendingOffset},pending_text=#{pending},pending_bytes=#{pendingBytes},status=#{status},file_size=#{fileSize}," +
            "last_modified_at=#{modifiedAt},last_collected_at=#{collectedAt},last_event_at=#{lastEventAt},last_error=#{lastError},parse_error_count=parse_error_count+#{parseErrors}, " +
            "stream_generation=#{generation} WHERE source_id=#{sourceId} AND file_key=#{fileKey}")
    void updateCheckpoint(Checkpoint row);

    @Select("<script>SELECT COALESCE(SUM(access_count),0) totalAccess, COALESCE(MAX(access_count),0) peakPerMinute, " +
            "(SELECT COUNT(*) FROM error_occurrence o JOIN error_group g ON g.id=o.group_id WHERE o.occurred_at BETWEEN #{from} AND #{to}" +
            "<if test='service != null and service != \"\"'> AND g.service_name=#{service}</if>" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if> AND g.category='SYSTEM') systemErrors, " +
            "(SELECT COUNT(*) FROM error_occurrence o JOIN error_group g ON g.id=o.group_id WHERE o.occurred_at BETWEEN #{from} AND #{to}" +
            "<if test='service != null and service != \"\"'> AND g.service_name=#{service}</if>" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if> AND g.category='BUSINESS') businessErrors " +
            "FROM api_access_minute WHERE minute_at BETWEEN #{from} AND #{to}" +
            "<if test='service != null and service != \"\"'> AND service_name=#{service}</if>" +
            "<if test='sourceId != null'> AND source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND instance_key=#{instanceKey}</if></script>")
    Map<String, Object> dashboardTotals(@Param("from") Instant from, @Param("to") Instant to,
                                        @Param("service") String service, @Param("instanceKey") String instanceKey,
                                        @Param("sourceId") Long sourceId);

    @Select("<script>SELECT minute_at time, SUM(access_count) metricValue FROM api_access_minute WHERE minute_at BETWEEN #{from} AND #{to}" +
            "<if test='service != null and service != \"\"'> AND service_name=#{service}</if>" +
            "<if test='sourceId != null'> AND source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND instance_key=#{instanceKey}</if> GROUP BY minute_at ORDER BY minute_at</script>")
    List<Map<String, Object>> accessTrend(@Param("from") Instant from, @Param("to") Instant to,
                                          @Param("service") String service, @Param("instanceKey") String instanceKey,
                                          @Param("sourceId") Long sourceId);

    @Select("<script>SELECT o.occurred_at time, COUNT(*) metricValue FROM error_occurrence o JOIN error_group g ON g.id=o.group_id " +
            "WHERE o.occurred_at BETWEEN #{from} AND #{to}<if test='service != null and service != \"\"'> AND g.service_name=#{service}</if>" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if> " +
            "GROUP BY o.occurred_at ORDER BY o.occurred_at</script>")
    List<Map<String, Object>> errorTrendRaw(@Param("from") Instant from, @Param("to") Instant to,
                                            @Param("service") String service, @Param("instanceKey") String instanceKey,
                                            @Param("sourceId") Long sourceId);

    @Select("<script>SELECT aggregated.id,aggregated.service,aggregated.applicationNamespace,aggregated.sourceId," +
            "aggregated.uri,aggregated.totalCount,aggregated.averagePerMinute,aggregated.peakPerMinute," +
            "(SELECT COUNT(*) FROM error_occurrence o JOIN error_group g ON g.id=o.group_id " +
            "LEFT JOIN log_source os ON os.id=o.source_id WHERE g.service_name=aggregated.applicationNamespace " +
            "AND COALESCE(os.name,'UNKNOWN')=aggregated.service AND o.inferred_uri=aggregated.uri AND o.occurred_at BETWEEN #{from} AND #{to}" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if>) errorCount " +
            "FROM (SELECT MIN(a.id) id,COALESCE(s.name,'UNKNOWN') service,a.service_name applicationNamespace," +
            "CASE WHEN COUNT(DISTINCT a.source_id)=1 THEN MIN(a.source_id) END sourceId,a.uri," +
            "SUM(a.access_count) totalCount,AVG(a.access_count) averagePerMinute,MAX(a.access_count) peakPerMinute " +
            "FROM api_access_minute a LEFT JOIN log_source s ON s.id=a.source_id WHERE a.minute_at BETWEEN #{from} AND #{to}" +
            "<if test='service != null and service != \"\"'> AND a.service_name=#{service}</if>" +
            "<if test='sourceId != null'> AND a.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND a.instance_key=#{instanceKey}</if>" +
            "<if test='keyword != null and keyword != \"\"'> AND a.uri LIKE CONCAT('%',#{keyword},'%')</if> " +
            "GROUP BY a.service_name,COALESCE(s.name,'UNKNOWN'),a.uri) aggregated " +
            "ORDER BY aggregated.totalCount DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<EndpointRow> endpoints(@Param("from") Instant from, @Param("to") Instant to, @Param("service") String service,
                                @Param("instanceKey") String instanceKey, @Param("sourceId") Long sourceId, @Param("keyword") String keyword,
                                @Param("limit") int limit, @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM (SELECT 1 FROM api_access_minute a LEFT JOIN log_source s ON s.id=a.source_id " +
            "WHERE a.minute_at BETWEEN #{from} AND #{to}" +
            "<if test='service != null and service != \"\"'> AND a.service_name=#{service}</if>" +
            "<if test='sourceId != null'> AND a.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND a.instance_key=#{instanceKey}</if>" +
            "<if test='keyword != null and keyword != \"\"'> AND a.uri LIKE CONCAT('%',#{keyword},'%')</if> " +
            "GROUP BY a.service_name,COALESCE(s.name,'UNKNOWN'),a.uri) x</script>")
    long endpointCount(@Param("from") Instant from, @Param("to") Instant to, @Param("service") String service,
                       @Param("instanceKey") String instanceKey, @Param("sourceId") Long sourceId, @Param("keyword") String keyword);

    @Select("SELECT service_name FROM api_access_minute WHERE id=#{id}")
    String endpointNamespace(long id);
    @Select("SELECT COALESCE(s.name,'UNKNOWN') FROM api_access_minute a LEFT JOIN log_source s ON s.id=a.source_id WHERE a.id=#{id}")
    String endpointApplicationName(long id);
    @Select("SELECT uri FROM api_access_minute WHERE id=#{id}")
    String endpointUri(long id);
    @Select("<script>SELECT a.minute_at time,SUM(a.access_count) metricValue FROM api_access_minute a " +
            "LEFT JOIN log_source s ON s.id=a.source_id WHERE a.service_name=#{applicationNamespace} " +
            "AND COALESCE(s.name,'UNKNOWN')=#{applicationName} AND a.uri=#{uri} AND a.minute_at BETWEEN #{from} AND #{to}" +
            "<if test='sourceId != null'> AND a.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND a.instance_key=#{instanceKey}</if> " +
            "GROUP BY a.minute_at ORDER BY a.minute_at</script>")
    List<Map<String, Object>> endpointTrend(@Param("applicationNamespace") String applicationNamespace,
                                            @Param("applicationName") String applicationName,
                                            @Param("uri") String uri,
                                            @Param("from") Instant from,@Param("to") Instant to,
                                            @Param("instanceKey") String instanceKey,@Param("sourceId") Long sourceId);

    @Select("<script>SELECT g.id,g.fingerprint,g.service_name AS service,g.service_name AS applicationNamespace," +
            "CASE WHEN COUNT(DISTINCT o.source_id)=1 THEN MIN(o.source_id) END AS sourceId,g.category,g.exception_class,g.summary," +
            "g.first_seen,MAX(o.occurred_at) last_seen,COUNT(*) occurrence_count,g.inferred_uri " +
            "FROM error_group g JOIN error_occurrence o ON o.group_id=g.id WHERE o.occurred_at BETWEEN #{from} AND #{to}" +
            "<if test='service != null and service != \"\"'> AND g.service_name=#{service}</if>" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if>" +
            "<if test='category != null and category != \"\"'> AND g.category=#{category}</if>" +
            "<if test='endpoint != null and endpoint != \"\"'> AND g.inferred_uri=#{endpoint}</if>" +
            "<if test='keyword != null and keyword != \"\"'> AND (g.summary LIKE CONCAT('%',#{keyword},'%') OR g.exception_class LIKE CONCAT('%',#{keyword},'%'))</if> " +
            "GROUP BY g.id,g.fingerprint,g.service_name,g.category,g.exception_class,g.summary,g.first_seen,g.inferred_uri " +
            "ORDER BY last_seen DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<ErrorGroupRow> errorGroups(@Param("from") Instant from,@Param("to") Instant to,@Param("service") String service,
                                    @Param("instanceKey") String instanceKey,@Param("sourceId") Long sourceId,@Param("category") String category,
                                    @Param("keyword") String keyword,@Param("endpoint") String endpoint,
                                    @Param("limit") int limit,@Param("offset") int offset);

    @Select("<script>SELECT g.id,g.fingerprint,g.service_name AS service,g.service_name AS applicationNamespace," +
            "CASE WHEN COUNT(DISTINCT o.source_id)=1 THEN MIN(o.source_id) END AS sourceId,g.category,g.exception_class,g.summary," +
            "MIN(o.occurred_at) first_seen,MAX(o.occurred_at) last_seen,COUNT(*) occurrence_count,g.inferred_uri " +
            "FROM error_group g JOIN error_occurrence o ON o.group_id=g.id WHERE o.occurred_at BETWEEN #{from} AND #{to}" +
            "<if test='service != null and service != \"\"'> AND g.service_name=#{service}</if>" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if> " +
            "GROUP BY g.id,g.fingerprint,g.service_name,g.category,g.exception_class,g.summary,g.inferred_uri " +
            "ORDER BY occurrence_count DESC,last_seen DESC LIMIT #{limit}</script>")
    List<ErrorGroupRow> topErrorGroups(@Param("from") Instant from,@Param("to") Instant to,
                                       @Param("service") String service,@Param("instanceKey") String instanceKey,@Param("sourceId") Long sourceId,
                                       @Param("limit") int limit);

    @Select("<script>SELECT COUNT(DISTINCT g.id) FROM error_group g JOIN error_occurrence o ON o.group_id=g.id WHERE o.occurred_at BETWEEN #{from} AND #{to}" +
            "<if test='service != null and service != \"\"'> AND g.service_name=#{service}</if>" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if>" +
            "<if test='category != null and category != \"\"'> AND g.category=#{category}</if>" +
            "<if test='endpoint != null and endpoint != \"\"'> AND g.inferred_uri=#{endpoint}</if>" +
            "<if test='keyword != null and keyword != \"\"'> AND (g.summary LIKE CONCAT('%',#{keyword},'%') OR g.exception_class LIKE CONCAT('%',#{keyword},'%'))</if></script>")
    long errorGroupCount(@Param("from") Instant from,@Param("to") Instant to,@Param("service") String service,
                         @Param("instanceKey") String instanceKey,@Param("sourceId") Long sourceId,@Param("category") String category,
                         @Param("keyword") String keyword,@Param("endpoint") String endpoint);

    @Select("SELECT g.id,g.fingerprint,g.service_name AS service,g.service_name AS applicationNamespace," +
            "(SELECT CASE WHEN COUNT(DISTINCT o.source_id)=1 THEN MIN(o.source_id) END FROM error_occurrence o WHERE o.group_id=g.id) AS sourceId,g.category," +
            "g.exception_class,g.summary,g.first_seen,g.last_seen,g.occurrence_count,g.inferred_uri FROM error_group g WHERE g.id=#{id}")
    ErrorGroupRow errorGroup(long id);
    @Select("<script>SELECT o.*,a.name AS agent_name FROM error_occurrence o LEFT JOIN collector_agent a " +
            "ON a.agent_uuid=o.instance_key WHERE o.group_id=#{groupId}" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if> " +
            "ORDER BY o.occurred_at DESC LIMIT #{limit} OFFSET #{offset}</script>")
    List<ErrorOccurrenceRow> occurrences(@Param("groupId") long groupId,@Param("instanceKey") String instanceKey,@Param("sourceId") Long sourceId,
                                         @Param("limit") int limit,@Param("offset") int offset);
    @Select("<script>SELECT COUNT(*) FROM error_occurrence WHERE group_id=#{groupId}" +
            "<if test='sourceId != null'> AND source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND instance_key=#{instanceKey}</if></script>")
    long occurrenceCount(@Param("groupId") long groupId, @Param("instanceKey") String instanceKey,@Param("sourceId") Long sourceId);

    @Select("<script>SELECT o.id,o.group_id,g.service_name AS service,g.service_name AS applicationNamespace,g.category,g.exception_class,g.summary," +
            "o.occurred_at,o.thread_name,o.inferred_uri,o.association_type," +
            "o.source_id,s.name AS source_name,o.instance_key,a.name AS agent_name,COALESCE(a.display_address,a.host_name,'local') AS display_address " +
            "FROM error_occurrence o JOIN error_group g ON g.id=o.group_id " +
            "LEFT JOIN log_source s ON s.id=o.source_id LEFT JOIN collector_agent a ON a.agent_uuid=o.instance_key " +
            "WHERE o.id&lt;=#{snapshotId} AND g.service_name=#{service} AND o.occurred_at BETWEEN #{from} AND #{to}" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if>" +
            "<if test='category != null and category != \"\"'> AND g.category=#{category}</if>" +
            "<if test='keyword != null and keyword != \"\"'> AND (g.summary LIKE CONCAT('%',#{keyword},'%') " +
            "OR g.exception_class LIKE CONCAT('%',#{keyword},'%') OR o.message_text LIKE CONCAT('%',#{keyword},'%'))</if> " +
            "ORDER BY o.occurred_at <choose><when test='sort == \"ASC\"'>ASC</when><otherwise>DESC</otherwise></choose>," +
            "o.id <choose><when test='sort == \"ASC\"'>ASC</when><otherwise>DESC</otherwise></choose> " +
            "LIMIT #{limit} OFFSET #{offset}</script>")
    List<ErrorLogItem> errorOccurrences(@Param("service") String service, @Param("from") Instant from,
                                        @Param("to") Instant to, @Param("instanceKey") String instanceKey,@Param("sourceId") Long sourceId,
                                        @Param("category") String category, @Param("keyword") String keyword,
                                        @Param("sort") String sort, @Param("snapshotId") long snapshotId,
                                        @Param("limit") int limit,
                                        @Param("offset") int offset);

    @Select("<script>SELECT COUNT(*) FROM error_occurrence o JOIN error_group g ON g.id=o.group_id " +
            "WHERE o.id&lt;=#{snapshotId} AND g.service_name=#{service} AND o.occurred_at BETWEEN #{from} AND #{to}" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if>" +
            "<if test='category != null and category != \"\"'> AND g.category=#{category}</if>" +
            "<if test='keyword != null and keyword != \"\"'> AND (g.summary LIKE CONCAT('%',#{keyword},'%') " +
            "OR g.exception_class LIKE CONCAT('%',#{keyword},'%') OR o.message_text LIKE CONCAT('%',#{keyword},'%'))</if></script>")
    long errorOccurrenceCount(@Param("service") String service, @Param("from") Instant from,
                              @Param("to") Instant to, @Param("instanceKey") String instanceKey,@Param("sourceId") Long sourceId,
                              @Param("category") String category, @Param("keyword") String keyword,
                              @Param("snapshotId") long snapshotId);

    @Select("<script>SELECT COUNT(*) FROM error_occurrence o JOIN error_group g ON g.id=o.group_id " +
            "WHERE o.id&gt;#{afterId} AND o.id&lt;=#{snapshotId} AND g.service_name=#{service} " +
            "AND o.occurred_at BETWEEN #{from} AND #{to}" +
            "<if test='sourceId != null'> AND o.source_id=#{sourceId}</if>" +
            "<if test='instanceKey != null and instanceKey != \"\"'> AND o.instance_key=#{instanceKey}</if>" +
            "<if test='category != null and category != \"\"'> AND g.category=#{category}</if>" +
            "<if test='keyword != null and keyword != \"\"'> AND (g.summary LIKE CONCAT('%',#{keyword},'%') " +
            "OR g.exception_class LIKE CONCAT('%',#{keyword},'%') OR o.message_text LIKE CONCAT('%',#{keyword},'%'))</if></script>")
    long errorOccurrenceUpdateCount(@Param("afterId") long afterId, @Param("service") String service,
                                    @Param("from") Instant from, @Param("to") Instant to,
                                    @Param("instanceKey") String instanceKey,@Param("sourceId") Long sourceId,
                                    @Param("category") String category, @Param("keyword") String keyword,
                                    @Param("snapshotId") long snapshotId);

    @Select("SELECT COALESCE(MAX(id),0) FROM error_occurrence")
    long latestOccurrenceId();

    @Select("SELECT o.id,o.group_id,g.fingerprint,g.service_name AS service,g.service_name AS applicationNamespace,g.category,g.exception_class,g.summary," +
            "o.occurred_at,o.thread_name,o.message_text,o.stack_trace,o.inferred_uri,o.association_type," +
            "o.source_id,s.name AS source_name,o.source_path,o.source_offset,o.instance_key,a.id AS agent_id,a.name AS agent_name," +
            "COALESCE(a.display_address,a.host_name,'local') AS display_address " +
            "FROM error_occurrence o JOIN error_group g ON g.id=o.group_id " +
            "LEFT JOIN log_source s ON s.id=o.source_id LEFT JOIN collector_agent a ON a.agent_uuid=o.instance_key " +
            "WHERE o.id=#{id}")
    ErrorOccurrenceDetail errorOccurrence(long id);

    @Select("SELECT * FROM collector_checkpoint ORDER BY source_name,file_path")
    List<Map<String, Object>> checkpoints();
    @Select("SELECT COUNT(*) FROM collector_checkpoint WHERE source_name=#{sourceName}")
    long sourceCheckpointCount(String sourceName);
    @Select("SELECT COUNT(*) FROM collector_checkpoint WHERE source_name=#{sourceName} AND pending_text IS NOT NULL AND pending_text<>''")
    long sourcePendingCheckpointCount(String sourceName);
    @Select("SELECT application_namespace FROM log_source WHERE deleted_at IS NULL " +
            "AND application_namespace IS NOT NULL GROUP BY application_namespace ORDER BY application_namespace")
    List<String> services();

    @Select("SELECT COUNT(*) FROM api_access_minute")
    long accessBucketCount();
    @Select("SELECT COALESCE(SUM(access_count),0) FROM api_access_minute")
    long totalAccessCount();
    @Select("SELECT COUNT(*) FROM error_occurrence")
    long totalOccurrenceCount();

    @Delete("DELETE FROM ai_analysis WHERE group_id IN (SELECT id FROM error_group WHERE last_seen < #{cutoff})")
    void deleteOldAi(Instant cutoff);
    @Delete("DELETE FROM error_occurrence WHERE occurred_at < #{cutoff}")
    void deleteOldOccurrences(Instant cutoff);
    @Delete("DELETE FROM error_group WHERE last_seen < #{cutoff}")
    void deleteOldGroups(Instant cutoff);
    @Delete("DELETE FROM api_access_minute WHERE minute_at < #{cutoff}")
    void deleteOldAccess(Instant cutoff);
    @Delete("DELETE FROM access_event_dedup WHERE occurred_at < #{cutoff}")
    void deleteOldAccessDedup(Instant cutoff);
    @Delete("DELETE FROM access_dedup_baseline WHERE minute_at < #{cutoff}")
    void deleteOldAccessBaseline(Instant cutoff);

    class ErrorGroupInsert {
        public Long id; public String fingerprint; public String signature; public String service; public String category; public String exceptionClass;
        public String summary; public Instant firstSeen; public Instant lastSeen; public long count; public String inferredUri;
    }
    class NamespaceMigration {
        public Long id; public long sourceId; public String oldNamespace; public String targetNamespace;
        public String status; public String failureReason;
    }
    class NamespaceErrorGroup {
        public long id; public String fingerprint; public String signature; public String service; public String category;
        public String exceptionClass; public String summary; public Instant firstSeen; public Instant lastSeen;
        public long totalCount; public long sourceCount; public Instant sourceFirstSeen; public Instant sourceLastSeen;
        public String inferredUri;
    }
    record OccurrenceInsert(long groupId, Instant occurredAt, String thread, String message, String stackTrace,
                            String inferredUri, String associationType, String sourcePath, long sourceOffset,
                            String eventKey, String instanceKey, Long sourceId) {}
    record Checkpoint(String source, String instanceKey, Long sourceId, String fileKey, String generation,
                      String path, long offset, long pendingOffset, String pending,
                      String pendingBytes, String status, long fileSize, Instant modifiedAt, Instant collectedAt,
                      Instant lastEventAt, String lastError, long parseErrors) {}
}
