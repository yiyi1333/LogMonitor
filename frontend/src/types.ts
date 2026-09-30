export interface TimePoint { time: string; value: number }
export interface EndpointRow { id: number; service: string; applicationNamespace: string; sourceId?: number; uri: string; totalCount: number; averagePerMinute: number; peakPerMinute: number; errorCount: number }
export interface ErrorGroup { id: number; fingerprint: string; service: string; applicationNamespace: string; sourceId?: number; category: 'SYSTEM'|'BUSINESS'; exceptionClass?: string; summary: string; firstSeen: string; lastSeen: string; occurrenceCount: number; inferredUri?: string }
export interface ErrorOccurrence { id: number; groupId: number; occurredAt: string; threadName: string; messageText: string; stackTrace?: string; inferredUri?: string; associationType: string; sourcePath?: string; instanceKey?: string; agentName?: string }
export interface ErrorLogItem { id: number; groupId: number; service: string; applicationNamespace: string; category: 'SYSTEM'|'BUSINESS'; exceptionClass?: string; summary: string; occurredAt: string; threadName?: string; inferredUri?: string; associationType: string; sourceId?: number; sourceName?: string; instanceKey: string; agentName?: string; displayAddress?: string }
export interface ErrorOccurrenceDetail extends ErrorLogItem { fingerprint: string; messageText: string; stackTrace?: string; sourcePath?: string; sourceOffset?: number; agentId?: number }
export interface ErrorOccurrencePage { items: ErrorLogItem[]; total: number; page: number; pageSize: number; snapshotId: number }
export interface ErrorOccurrenceUpdates { count: number; latestId: number }
export interface PageResult<T> { items: T[]; total: number; page: number; pageSize: number }
export interface UserSummary { id: number; username: string; role: 'USER'; enabled: boolean; mustChangePassword: boolean; createdAt: string }
export interface Dashboard { totalAccess: number; averagePerMinute: number; peakPerMinute: number; systemErrors: number; businessErrors: number; accessTrend: TimePoint[]; errorTrend: TimePoint[]; topEndpoints: EndpointRow[]; topErrors: ErrorGroup[]; services: string[] }
export interface SourceStatus { id: number; sourceName: string; applicationNamespace: string; path: string; include: string; exclude: string; status: string; files: number; bytesRead: number; totalBytes: number; lastCollectedAt?: string; parseErrors: number; lastError?: string; collectorType: 'LOCAL'|'AGENT'; agentId?: number; agentName?: string; displayAddress: string; instanceKey: string; startMode: 'NOW'|'HISTORY_180D'; namespaceMigrationStatus: 'IDLE'|'MIGRATING'|'FAILED' }
export interface SourceOptions { allowedRoots: string[]; defaultInclude: string; defaultExclude: string }
export interface AgentSummary { id: number; uuid: string; name: string; hostName: string; displayAddress: string; version: string; status: 'ONLINE'|'OFFLINE'|'BLOCKED'|'ERROR'|'WAITING'; spoolBytes: number; spoolLimitBytes: number; lastSeenAt?: string; lastError?: string; allowedRoots: string[]; createdBy: string; createdAt: string }
export interface ApplicationInstance { sourceId: number; agentId?: number; displayAddress: string; applicationName: string; path: string; status: string; active: boolean; label: string }
export interface ApplicationOption { applicationNamespace: string; instances: ApplicationInstance[] }
export interface StructuredAnalysis { overview: string; rootCauses: string[]; investigationSteps: string[]; fixSuggestions: string[]; riskLevel: 'LOW'|'MEDIUM'|'HIGH'|'CRITICAL'|'UNKNOWN' }
export interface AiAnalysis { id?: number; groupId?: number; occurrenceId?: number; modelConfigId?: number; providerName: string; providerType: string; modelName: string; promptVersion: string; locale: string; requestedBy?: string; status: 'RUNNING'|'SUCCESS'|'FAILED'; result?: StructuredAnalysis; resultText?: string; promptTokens?: number; completionTokens?: number; failureReason?: string; createdAt?: string; updatedAt?: string }
export interface LlmModelOption { id: number; providerId: number; providerName: string; providerType: string; modelId: string; displayName: string; enabled: boolean }
export interface LlmSettings { selectedModelId?: number; defaultModelId?: number; effectiveModel?: LlmModelOption; fallbackApplied: boolean; models: LlmModelOption[] }
export interface LlmProvider { id: number; name: string; providerType: string; protocolType: string; baseUrl: string; apiKeyConfigured: boolean; enabled: boolean; lastTestStatus?: 'SUCCESS'|'FAILED'; lastTestError?: string; lastTestedAt?: string; createdBy: string; createdAt: string; models: LlmModelOption[] }
export interface LlmConnectionTest { success: boolean; latencyMs: number; message: string }
export interface LlmDiscoveredModel { modelId: string; displayName: string; owner?: string; capabilityStatus: 'SUPPORTED'|'UNKNOWN'; alreadyConfigured: boolean }
export interface LlmModelDiscovery { source: 'REMOTE'|'CATALOG'; catalogVersion?: string; warning?: string; models: LlmDiscoveredModel[] }
export interface LlmModelImport { created: LlmModelOption[]; skippedModelIds: string[] }

export interface DirectoryListing {
  path: string | null
  parentPath: string | null
  directories: { name: string; path: string }[]
  truncated: boolean
}
