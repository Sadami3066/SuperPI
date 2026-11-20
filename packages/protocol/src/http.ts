import type { DeviceInfo, DevicePlatform, ErrorCode } from './frames.ts'

export interface HealthResponse {
  ok: boolean
  version: string
  protocolVersion: number
  fingerprint: string | null
}

export interface EnrollRequest {
  pairCode?: string
  loginSecret?: string
  deviceName: string
  platform: DevicePlatform
  appVersion?: string
}

export interface EnrollResponse {
  deviceId: string
  token: string
  accountId: string
  entries: PairEntry[]
  fingerprint: string | null
}

export interface PairEntry {
  kind: 'lan' | 'public'
  origin: string
}

export interface PairCodeResponse {
  code: string
  expiresAt: number
  entries: PairEntry[]
  fingerprint: string | null
}

export interface DeviceListResponse {
  devices: DeviceInfo[]
}

export interface RevokeResponse {
  revoked: string
}

export interface ApiErrorBody {
  code: ErrorCode
  message: string
  details?: unknown
}
