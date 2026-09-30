package com.logmonitor.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class AgentModels {
    private AgentModels() {}

    static final class DirectoryEntry {
        public String name; public String path;
        DirectoryEntry() {} DirectoryEntry(String name,String path){this.name=name;this.path=path;}
    }
    static final class DirectoryListing {
        public String path; public String parentPath; public List<DirectoryEntry> directories; public boolean truncated;
        DirectoryListing() {} DirectoryListing(String path,String parentPath,List<DirectoryEntry> directories,boolean truncated){this.path=path;this.parentPath=parentPath;this.directories=directories;this.truncated=truncated;}
    }
    static final class DirectoryRequest {
        public String requestId; public String path; public String query; public long deadlineEpochMillis;
    }
    static final class DirectoryResult {
        public String requestId; public DirectoryListing listing; public String errorCode;
    }
    static final class LocalConfig {
        public String serverUrl;
        public boolean allowHttp;
        public long agentId;
        public String agentUuid;
        public String token;
        public String agentName;
        public String hostName;
        public String displayAddress;
        public long configRevision;
        public int pollIntervalSeconds = 10;
        public int maxBatchBytes = 4 * 1024 * 1024;
        public long spoolLimitBytes = 5L * 1024 * 1024 * 1024;
        public List<String> allowedRoots = new ArrayList<String>();
    }

    static final class RootRequest {
        public String path;
        public String realPath;
        RootRequest() {}
        RootRequest(String path, String realPath) { this.path = path; this.realPath = realPath; }
    }

    static final class EnrollRequest {
        public String username;
        public String password;
        public String name;
        public String hostName;
        public String displayAddress;
        public String version;
        public List<RootRequest> roots = new ArrayList<RootRequest>();
    }

    static final class EnrollResponse {
        public long agentId;
        public String agentUuid;
        public String token;
        public long configRevision;
        public int pollIntervalSeconds;
        public int maxBatchBytes;
        public long spoolLimitBytes;
    }

    static final class SourceConfig {
        public long id;
        public String name;
        public String path;
        public String include;
        public String exclude;
        public String charset;
        public List<String> uriNormalizers = new ArrayList<String>();
        public String startMode;
    }

    static final class RemoteConfig {
        public long revision;
        public List<SourceConfig> sources = new ArrayList<SourceConfig>();
    }

    static final class FileState {
        public long sourceId;
        public String fileKey;
        public String generation;
        public String path;
        public long offset;
        public long lastSize;
        public boolean stableSent;
    }

    static final class RuntimeState {
        public long sequence;
        public Map<String, FileState> files = new LinkedHashMap<String, FileState>();
    }

    static final class BatchMetadata {
        public String batchId;
        public long sourceId;
        public String fileKey;
        public String generation;
        public String path;
        public long startOffset;
        public long endOffset;
        public long fileSize;
        public String modifiedAt;
        public String charset;
        public String checksum;
        public boolean stable;
        public long createdAt;
    }

    static final class SourceReport {
        public long sourceId;
        public String status;
        public String realPath;
        public String error;
        public long files;
        public long bytesRead;
        public long totalBytes;
        public long parseErrors;
        public String lastCollectedAt;
    }

    static final class Heartbeat {
        public String version = AgentMain.VERSION;
        public String displayAddress;
        public long spoolBytes;
        public long spoolLimitBytes;
        public List<SourceReport> sources = new ArrayList<SourceReport>();
    }

    static final class BatchAck {
        public String status;
        public long expectedOffset;
    }

    static final class ApiError {
        public String code;
        public String message;
        public Long expectedOffset;
    }
}
