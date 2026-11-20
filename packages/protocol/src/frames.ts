export const PROTOCOL_VERSION = 1

export const ERROR_CODES = [
  'unauthorized',
  'protocol_mismatch',
  'device_offline',
  'bot_not_found',
  'session_not_found',
  'memory_conflict',
  'rate_limited',
  'not_found',
  'internal',
] as const

export type ErrorCode = (typeof ERROR_CODES)[number]

export const ROUTE_METHODS = [
  'bot.prompt',
  'bot.steer',
  'bot.follow_up',
  'bot.abort',
  'bot.state',
  'bot.resume',
  'bot.approval',
] as const

export type RouteMethod = (typeof ROUTE_METHODS)[number]

export type DevicePlatform = 'android' | 'desktop' | 'cli' | 'unknown'

export interface BotInfo {
  id: string
  name: string
}

export interface DeviceInfo {
  deviceId: string
  name: string
  platform: DevicePlatform
  online: boolean
  lastSeen: number
  bots: BotInfo[]
}

export interface TextDeltaEvent {
  type: 'text_delta' | 'reasoning_delta'
  delta: string
}

export type BotEvent =
  | { type: 'agent_start'; runId?: string }
  | { type: 'message_update'; assistantMessageEvent: TextDeltaEvent }
  | { type: 'tool_call'; callId: string; name: string; args?: unknown }
  | { type: 'tool_result'; callId: string; result?: string; isError?: boolean; details?: unknown }
  | { type: 'approval_request'; approvalId: string; kind: string; summary: string }
  | { type: 'queue_update'; steering: string[]; followUp: string[] }
  | { type: 'agent_end'; status?: string }
  | { type: 'agent_settled' }
  | { type: 'error'; message: string }

export interface HelloFrame {
  type: 'hello'
  protocolVersion: number
  deviceId: string
  platform: DevicePlatform
  appVersion: string
  bots: BotInfo[]
  capabilities: string[]
}

export interface RouteFrame {
  type: 'route'
  id: string
  to: string
  method: RouteMethod
  params: Record<string, unknown>
}

export interface RouteOkFrame {
  type: 'route_ok'
  id: string
  result: unknown
}

export interface RouteErrorFrame {
  type: 'route_error'
  id: string
  code: ErrorCode
  message: string
}

export interface RouteEventFrame {
  type: 'route_event'
  id: string
  seq: number
  event: BotEvent
}

export interface PingFrame {
  type: 'ping'
}

export type ClientFrame = HelloFrame | RouteFrame | RouteOkFrame | RouteErrorFrame | RouteEventFrame | PingFrame

export interface HelloOkFrame {
  type: 'hello_ok'
  accountId: string
  devices: DeviceInfo[]
  memoryVersion: number
}

export interface PresenceFrame {
  type: 'presence'
  deviceId: string
  online: boolean
  lastSeen: number
}

export interface InboundRouteFrame extends RouteFrame {
  from: string
}

export interface MemoryChangedFrame {
  type: 'memory_changed'
  version: number
}

export interface PongFrame {
  type: 'pong'
}

export type ServerFrame =
  | HelloOkFrame
  | PresenceFrame
  | InboundRouteFrame
  | RouteOkFrame
  | RouteErrorFrame
  | RouteEventFrame
  | MemoryChangedFrame
  | PongFrame

export class ProtocolError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'ProtocolError'
  }
}

export type FrameDirection = 'client' | 'server'

export function isBotEvent(value: unknown): value is BotEvent {
  if (!isRecord(value) || typeof value.type !== 'string') return false
  switch (value.type) {
    case 'agent_start':
      return value.runId === undefined || typeof value.runId === 'string'
    case 'message_update': {
      const update = value.assistantMessageEvent
      if (!isRecord(update)) return false
      return (update.type === 'text_delta' || update.type === 'reasoning_delta') && typeof update.delta === 'string'
    }
    case 'tool_call':
      return typeof value.callId === 'string' && typeof value.name === 'string'
    case 'tool_result':
      return typeof value.callId === 'string'
    case 'approval_request':
      return typeof value.approvalId === 'string' && typeof value.kind === 'string' && typeof value.summary === 'string'
    case 'queue_update':
      return isStringArray(value.steering) && isStringArray(value.followUp)
    case 'agent_end':
      return value.status === undefined || typeof value.status === 'string'
    case 'agent_settled':
      return true
    case 'error':
      return typeof value.message === 'string'
    default:
      return false
  }
}

export function parseClientFrame(value: unknown): ClientFrame {
  if (!isRecord(value)) throw new ProtocolError('frame must be an object')
  switch (value.type) {
    case 'hello':
      return parseHello(value)
    case 'route':
      return parseRoute(value, false) as RouteFrame
    case 'route_ok':
      return { type: 'route_ok', id: requireString(value, 'id'), result: value.result }
    case 'route_error':
      return {
        type: 'route_error',
        id: requireString(value, 'id'),
        code: requireErrorCode(value, 'code'),
        message: requireString(value, 'message'),
      }
    case 'route_event':
      return parseRouteEvent(value)
    case 'ping':
      return { type: 'ping' }
    default:
      throw new ProtocolError(`unknown client frame type: ${String(value.type)}`)
  }
}

export function parseServerFrame(value: unknown): ServerFrame {
  if (!isRecord(value)) throw new ProtocolError('frame must be an object')
  switch (value.type) {
    case 'hello_ok':
      return {
        type: 'hello_ok',
        accountId: requireString(value, 'accountId'),
        devices: requireDevices(value.devices),
        memoryVersion: requireNumber(value, 'memoryVersion'),
      }
    case 'presence':
      return {
        type: 'presence',
        deviceId: requireString(value, 'deviceId'),
        online: requireBoolean(value, 'online'),
        lastSeen: requireNumber(value, 'lastSeen'),
      }
    case 'route':
      return parseRoute(value, true) as InboundRouteFrame
    case 'route_ok':
      return { type: 'route_ok', id: requireString(value, 'id'), result: value.result }
    case 'route_error':
      return {
        type: 'route_error',
        id: requireString(value, 'id'),
        code: requireErrorCode(value, 'code'),
        message: requireString(value, 'message'),
      }
    case 'route_event':
      return parseRouteEvent(value)
    case 'memory_changed':
      return { type: 'memory_changed', version: requireNumber(value, 'version') }
    case 'pong':
      return { type: 'pong' }
    default:
      throw new ProtocolError(`unknown server frame type: ${String(value.type)}`)
  }
}

export function encodeFrame(frame: ClientFrame | ServerFrame): string {
  return `${JSON.stringify(frame)}\n`
}

export function decodeFrameLine(direction: FrameDirection, line: string): unknown {
  let parsed: unknown
  try {
    parsed = JSON.parse(line)
  } catch {
    throw new ProtocolError('frame is not valid JSON')
  }
  return direction === 'client' ? parseClientFrame(parsed) : parseServerFrame(parsed)
}

function parseHello(value: Record<string, unknown>): HelloFrame {
  const protocolVersion = requireNumber(value, 'protocolVersion')
  if (protocolVersion !== PROTOCOL_VERSION) {
    throw new ProtocolError(`protocol version mismatch: expected ${PROTOCOL_VERSION}, got ${protocolVersion}`)
  }
  return {
    type: 'hello',
    protocolVersion,
    deviceId: requireString(value, 'deviceId'),
    platform: requirePlatform(value, 'platform'),
    appVersion: requireString(value, 'appVersion'),
    bots: requireBots(value.bots),
    capabilities: requireStringArray(value, 'capabilities'),
  }
}

function parseRoute(value: Record<string, unknown>, inbound: boolean): RouteFrame | InboundRouteFrame {
  const base: RouteFrame = {
    type: 'route',
    id: requireString(value, 'id'),
    to: requireString(value, 'to'),
    method: requireRouteMethod(value, 'method'),
    params: requireRecord(value, 'params'),
  }
  if (!inbound) return base
  return { ...base, from: requireString(value, 'from') }
}

function parseRouteEvent(value: Record<string, unknown>): RouteEventFrame {
  const event = value.event
  if (!isBotEvent(event)) throw new ProtocolError('route_event.event is not a valid bot event')
  return {
    type: 'route_event',
    id: requireString(value, 'id'),
    seq: requireNumber(value, 'seq'),
    event,
  }
}

function requireDevices(value: unknown): DeviceInfo[] {
  if (!Array.isArray(value)) throw new ProtocolError('devices must be an array')
  return value.map((entry) => {
    if (!isRecord(entry)) throw new ProtocolError('device must be an object')
    return {
      deviceId: requireString(entry, 'deviceId'),
      name: requireString(entry, 'name'),
      platform: requirePlatform(entry, 'platform'),
      online: requireBoolean(entry, 'online'),
      lastSeen: requireNumber(entry, 'lastSeen'),
      bots: requireBots(entry.bots),
    }
  })
}

function requireBots(value: unknown): BotInfo[] {
  if (!Array.isArray(value)) throw new ProtocolError('bots must be an array')
  return value.map((entry) => {
    if (!isRecord(entry)) throw new ProtocolError('bot must be an object')
    return { id: requireString(entry, 'id'), name: requireString(entry, 'name') }
  })
}

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function requireString(record: Record<string, unknown>, key: string): string {
  const value = record[key]
  if (typeof value !== 'string') throw new ProtocolError(`${key} must be a string`)
  return value
}

export function requireNumber(record: Record<string, unknown>, key: string): number {
  const value = record[key]
  if (typeof value !== 'number' || !Number.isFinite(value)) throw new ProtocolError(`${key} must be a number`)
  return value
}

export function requireBoolean(record: Record<string, unknown>, key: string): boolean {
  const value = record[key]
  if (typeof value !== 'boolean') throw new ProtocolError(`${key} must be a boolean`)
  return value
}

export function requireRecord(record: Record<string, unknown>, key: string): Record<string, unknown> {
  const value = record[key]
  if (!isRecord(value)) throw new ProtocolError(`${key} must be an object`)
  return value
}

export function requireStringArray(record: Record<string, unknown>, key: string): string[] {
  const value = record[key]
  if (!isStringArray(value)) throw new ProtocolError(`${key} must be an array of strings`)
  return value
}

export function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((entry) => typeof entry === 'string')
}

function requireErrorCode(record: Record<string, unknown>, key: string): ErrorCode {
  const value = record[key]
  if (typeof value !== 'string' || !(ERROR_CODES as readonly string[]).includes(value)) {
    throw new ProtocolError(`${key} must be a known error code`)
  }
  return value as ErrorCode
}

function requireRouteMethod(record: Record<string, unknown>, key: string): RouteMethod {
  const value = record[key]
  if (typeof value !== 'string' || !(ROUTE_METHODS as readonly string[]).includes(value)) {
    throw new ProtocolError(`${key} must be a known route method`)
  }
  return value as RouteMethod
}

function requirePlatform(record: Record<string, unknown>, key: string): DevicePlatform {
  const value = record[key]
  switch (value) {
    case 'android':
    case 'desktop':
    case 'cli':
    case 'unknown':
      return value
    default:
      throw new ProtocolError(`${key} must be a known platform`)
  }
}
