package com.logmonitor.service;

import com.logmonitor.config.LogMonitorProperties.Source;
import com.logmonitor.mapper.LogMonitorMapper;
import com.logmonitor.mapper.LogMonitorMapper.ErrorGroupInsert;
import com.logmonitor.mapper.LogMonitorMapper.NamespaceErrorGroup;
import com.logmonitor.mapper.LogMonitorMapper.NamespaceMigration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class NamespaceMigrationService implements ApplicationRunner {
    private static final Pattern NAMESPACE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,79}");
    private final LogMonitorMapper mapper;
    private final LogParser parser;
    private final SourceLockRegistry locks;
    private final AgentService agents;
    private final Executor executor;
    private final TransactionTemplate transactions;

    public NamespaceMigrationService(LogMonitorMapper mapper, LogParser parser, SourceLockRegistry locks,
                                     AgentService agents, @Qualifier("sourceScanExecutor") Executor executor,
                                     PlatformTransactionManager transactionManager) {
        this.mapper = mapper;
        this.parser = parser;
        this.locks = locks;
        this.agents = agents;
        this.executor = executor;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        transactions.executeWithoutResult(status -> initializeSignatures());
        List<NamespaceMigration> pending = mapper.pendingNamespaceMigrations();
        for (NamespaceMigration migration : pending) executor.execute(() -> migrate(migration.id));
    }

    public long request(long sourceId, String rawNamespace) {
        String namespace = rawNamespace == null ? "" : rawNamespace.trim();
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new SourceManagementException("INVALID_SOURCE", HttpStatus.BAD_REQUEST,
                    "应用命名空间须为 1-80 位字母、数字、点、下划线或连字符");
        }
        NamespaceMigration migration = transactions.execute(status -> {
            Source source = mapper.activeLogSource(sourceId);
            if (source == null) throw new SourceManagementException("SOURCE_NOT_FOUND", HttpStatus.NOT_FOUND, "日志源不存在");
            if (source.getApplicationNamespace().equalsIgnoreCase(namespace)) return null;
            if (mapper.activeNamespaceMigration(sourceId) > 0) {
                throw new SourceManagementException("SOURCE_NAMESPACE_MIGRATING", HttpStatus.CONFLICT, "日志源命名空间正在迁移");
            }
            NamespaceMigration row = new NamespaceMigration();
            row.sourceId = sourceId;
            row.oldNamespace = source.getApplicationNamespace();
            row.targetNamespace = namespace;
            mapper.insertNamespaceMigration(row);
            mapper.updateSourceMigrationStatus(sourceId, "MIGRATING");
            if (source.getAgentId() != null) agents.bumpRevision(source.getAgentId());
            return row;
        });
        if (migration == null) return 0;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { executor.execute(() -> migrate(migration.id)); }
            });
        } else {
            executor.execute(() -> migrate(migration.id));
        }
        return migration.id;
    }

    private void migrate(long migrationId) {
        NamespaceMigration migration = mapper.pendingNamespaceMigrations().stream()
                .filter(item -> item.id == migrationId).findFirst().orElse(null);
        if (migration == null) return;
        ReentrantLock lock = locks.lock(migration.sourceId);
        lock.lock();
        try {
            transactions.executeWithoutResult(status -> migrateTransactional(migration));
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            if (message.length() > 2000) message = message.substring(0, 2000);
            String failure = message;
            transactions.executeWithoutResult(status -> {
                mapper.failNamespaceMigration(migrationId, failure);
                mapper.updateSourceMigrationStatus(migration.sourceId, "FAILED");
            });
        } finally {
            lock.unlock();
        }
    }

    private void migrateTransactional(NamespaceMigration migration) {
        mapper.startNamespaceMigration(migration.id);
        Source source = mapper.logSource(migration.sourceId);
        if (source == null) throw new IllegalStateException("日志源不存在");
        for (NamespaceErrorGroup group : mapper.namespaceErrorGroups(source.getId())) {
            String signature = ensureSignature(group);
            String targetFingerprint = parser.fingerprintFor(migration.targetNamespace, signature);
            var target = mapper.findErrorGroupByFingerprint(targetFingerprint);
            if (group.totalCount == group.sourceCount && target == null) {
                mapper.moveWholeGroup(group.id, targetFingerprint, signature, migration.targetNamespace);
                continue;
            }
            long targetId;
            if (target == null) {
                ErrorGroupInsert row = new ErrorGroupInsert();
                row.fingerprint = targetFingerprint;
                row.signature = signature;
                row.service = migration.targetNamespace;
                row.category = group.category;
                row.exceptionClass = group.exceptionClass;
                row.summary = group.summary;
                row.firstSeen = group.sourceFirstSeen;
                row.lastSeen = group.sourceLastSeen;
                row.count = group.sourceCount;
                row.inferredUri = group.inferredUri;
                mapper.insertErrorGroup(row);
                targetId = row.id;
            } else {
                targetId = target.id();
            }
            mapper.reassignSourceOccurrences(source.getId(), group.id, targetId);
            mapper.recomputeGroup(targetId);
            mapper.recomputeGroup(group.id);
            if (group.totalCount == group.sourceCount) mapper.deleteGroupAi(group.id);
            mapper.deleteEmptyGroup(group.id);
        }
        mapper.updateAccessNamespace(source.getId(), migration.targetNamespace);
        mapper.updateBaselineNamespace(source.getId(), migration.targetNamespace);
        mapper.updateSourceNamespace(source.getId(), migration.targetNamespace);
        mapper.completeNamespaceMigration(migration.id);
        if (source.getAgentId() != null) agents.bumpRevision(source.getAgentId());
    }

    private void initializeSignatures() {
        for (NamespaceErrorGroup group : mapper.groupsWithoutSignature()) {
            String signature = ensureSignature(group);
            String fingerprint = parser.fingerprintFor(group.service, signature);
            var target = mapper.findErrorGroupByFingerprint(fingerprint);
            if (target == null || target.id() == group.id) {
                mapper.updateGroupIdentity(group.id, signature, fingerprint);
            } else {
                mapper.reassignSourceOccurrencesForGroup(group.id, target.id());
                mapper.recomputeGroup(target.id());
                mapper.deleteGroupAi(group.id);
                mapper.deleteEmptyGroup(group.id);
            }
        }
    }

    private String ensureSignature(NamespaceErrorGroup group) {
        if (group.signature != null && !group.signature.isBlank()) return group.signature;
        return parser.signatureFor(group.category, group.exceptionClass, group.summary,
                mapper.representativeStack(group.id));
    }
}
