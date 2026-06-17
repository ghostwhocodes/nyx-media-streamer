export interface SanitizedConfig {
  host: string
  port: number
  corsOrigins: string[]
  mediaRoots: MediaRoot[]
  auth: AuthInfo
  transcode: TranscodeInfo
  thumbnails: ThumbnailInfo
}

export interface MediaRoot {
  path: string
  filesystem: string
}

export interface AuthInfo {
  enabled: boolean
  hasToken: boolean
  users: string[]
}

export interface TranscodeInfo {
  defaultFormat: string
  maxConcurrentJobs: number
  segmentCacheGracePeriodMinutes: number
}

export interface ThumbnailInfo {
  sizes: number[]
  videoOffsetPercent: number
  maxCacheSizeMB: number
}

export interface ConfigUpdateResponse {
  config: SanitizedConfig
  restartRequired: boolean
}

export interface HealthReport {
  status: string
  ffmpegAvailable: boolean
  activeJobs: number
  dbWritable: boolean
  diskSpaceWarning: boolean
  dbConnectivity: boolean
  stuckJobsWarning: boolean
  circuit_breaker_open: boolean
  lastBackupTimestamp: string | null
  lastBackupBytes: number | null
  serverVersion: string | null
  build: Record<string, string>
}

export interface TranscodeJob {
  id: string
  status: string
  inputPath: string
  profile: string
  format: string
  createdAt: string
  updatedAt: string
}

export interface TranscodeJobListing {
  items: TranscodeJob[]
  page: number
  limit: number
  total: number
}
