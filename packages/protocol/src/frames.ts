export const PROTOCOL_VERSION = 1

export const RELAY_CAPABILITIES = ['remote-bot', 'bot-sessions', 'bot-images', 'bot-approval'] as const
export type RelayCapability = (typeof RELAY_CAPABILITIES)[number]

export const ERROR_CODES = [
  'unauthorized',
  'protocol_mismatch',
  'device_offline',
  'bot_not_found',
  'session_not_found',
  'memory_conflict',
  'rate_limited',
  'bad_request',
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
  'bot.sessions',
  'bot.history',
] as const

export type RouteMethod = (typeof ROUTE_METHODS)[number]

export type DevicePlatform = 'android' | 'desktop' | 'cli' | 'unknown'

export interface BotInfo {
  id: string
  name: string
  avatar?: string
}

export interface RemoteSessionSummary {
  id: string
  title: string
  updatedAt: number
  messageCount?: number
  pinned?: boolean
}

export interface RouteImagePart {
  data: string
  mimeType: 'image/jpeg' | 'image/png' | 'image/webp' | 'image/gif'
  name?: string
}

export interface DeviceInfo {
  deviceId: string
  name: string
  platform: DevicePlatform
  online: boolean
  lastSeen: number
  bots: BotInfo[]
  capabilities?: string[]
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
  | {
      type: 'approval_request'
      approvalId: string
      kind: string
      summary: string
      title?: string
      message?: string
      options?: string[]
      placeholder?: string
      prefill?: string
    }
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
  capabilities?: string[]
}

export interface PresenceFrame {
  type: 'presence'
  deviceId: string
  online: boolean
  lastSeen: number
  bots?: BotInfo[]
  capabilities?: string[]
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

export class UnknownRouteMethodError extends ProtocolError {
  readonly routeId: string
  readonly method: string

  constructor(routeId: string, method: string) {
    super(`method must be a known route method: ${method.slice(0, 48)}`)
    this.name = 'UnknownRouteMethodError'
    this.routeId = routeId
    this.method = method
  }
}

export class RouteValidationError extends ProtocolError {
  readonly routeId: string
  readonly code: ErrorCode

  constructor(routeId: string, message: string, code: ErrorCode = 'bad_request') {
    super(message)
    this.name = 'RouteValidationError'
    this.routeId = routeId
    this.code = code
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
      return (
        typeof value.approvalId === 'string' &&
        typeof value.kind === 'string' &&
        typeof value.summary === 'string' &&
        (value.title === undefined || typeof value.title === 'string') &&
        (value.message === undefined || typeof value.message === 'string') &&
        (value.options === undefined || isStringArray(value.options)) &&
        (value.placeholder === undefined || typeof value.placeholder === 'string') &&
        (value.prefill === undefined || typeof value.prefill === 'string')
      )
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
      return { type: 'route_ok', id: requireIdentifier(value, 'id'), result: value.result }
    case 'route_error':
      return {
        type: 'route_error',
        id: requireIdentifier(value, 'id'),
        code: requireErrorCode(value, 'code'),
        message: requireString(value, 'message').slice(0, 2048),
      }
    case 'route_event':
      return parseRouteEvent(value)
    case 'ping':
      return { type: 'ping' }
    default:
      throw new ProtocolError('unknown client frame type')
  }
}

export function parseServerFrame(value: unknown): ServerFrame {
  if (!isRecord(value)) throw new ProtocolError('frame must be an object')
  switch (value.type) {
    case 'hello_ok': {
      const capabilities = optionalCapabilities(value.capabilities)
      const frame: HelloOkFrame = {
        type: 'hello_ok',
        accountId: requireString(value, 'accountId'),
        devices: requireDevices(value.devices, true),
        memoryVersion: requireNumber(value, 'memoryVersion'),
      }
      if (capabilities) frame.capabilities = capabilities
      return frame
    }
    case 'presence': {
      const bots = value.bots === undefined ? undefined : requireBots(value.bots, true)
      const capabilities = optionalCapabilities(value.capabilities)
      const frame: PresenceFrame = {
        type: 'presence',
        deviceId: requireIdentifier(value, 'deviceId'),
        online: requireBoolean(value, 'online'),
        lastSeen: requireNumber(value, 'lastSeen'),
      }
      if (bots) frame.bots = bots
      if (capabilities) frame.capabilities = capabilities
      return frame
    }
    case 'route':
      return parseRoute(value, true) as InboundRouteFrame
    case 'route_ok':
      return { type: 'route_ok', id: requireIdentifier(value, 'id'), result: value.result }
    case 'route_error':
      return {
        type: 'route_error',
        id: requireIdentifier(value, 'id'),
        code: requireErrorCode(value, 'code'),
        message: requireString(value, 'message').slice(0, 2048),
      }
    case 'route_event':
      return parseRouteEvent(value)
    case 'memory_changed':
      return { type: 'memory_changed', version: requireNumber(value, 'version') }
    case 'pong':
      return { type: 'pong' }
    default:
      throw new ProtocolError('unknown server frame type')
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
    throw new ProtocolError(`protocol version mismatch: expected ${PROTOCOL_VERSION}`)
  }
  return {
    type: 'hello',
    protocolVersion,
    deviceId: requireIdentifier(value, 'deviceId'),
    platform: requirePlatform(value, 'platform'),
    appVersion: requireBoundedString(value, 'appVersion', 64),
    bots: requireBots(value.bots, false),
    capabilities: requireCapabilities(value, 'capabilities'),
  }
}

function parseRoute(value: Record<string, unknown>, inbound: boolean): RouteFrame | InboundRouteFrame {
  const id = requireIdentifier(value, 'id')
  const to = requireIdentifier(value, 'to')
  const from = inbound ? requireIdentifier(value, 'from') : undefined
  const params = requireRecord(value, 'params')
  const method = requireRouteMethod(value, 'method', id)
  let nextParams = params
  if (params.images !== undefined) {
    try {
      const sanitized = validateRouteImages(params.images)
      nextParams = { ...params, images: sanitized }
    } catch (error) {
      throw new RouteValidationError(id, error instanceof Error ? error.message : 'invalid route images')
    }
  }
  const base: RouteFrame = { type: 'route', id, to, method, params: nextParams }
  if (!inbound) return base
  return { ...base, from: from as string }
}

function parseRouteEvent(value: Record<string, unknown>): RouteEventFrame {
  const event = value.event
  if (!isBotEvent(event)) throw new ProtocolError('route_event.event is not a valid bot event')
  return {
    type: 'route_event',
    id: requireIdentifier(value, 'id'),
    seq: requireNumber(value, 'seq'),
    event,
  }
}

function requireDevices(value: unknown, tolerantAvatars: boolean): DeviceInfo[] {
  if (!Array.isArray(value)) throw new ProtocolError('devices must be an array')
  return value.map((entry) => {
    if (!isRecord(entry)) throw new ProtocolError('device must be an object')
    const capabilities = optionalCapabilities(entry.capabilities)
    const device: DeviceInfo = {
      deviceId: requireIdentifier(entry, 'deviceId'),
      name: requireBoundedString(entry, 'name', 128),
      platform: requirePlatform(entry, 'platform'),
      online: requireBoolean(entry, 'online'),
      lastSeen: requireNumber(entry, 'lastSeen'),
      bots: requireBots(entry.bots, tolerantAvatars),
    }
    if (capabilities) device.capabilities = capabilities
    return device
  })
}

const MAX_BOTS_PER_DEVICE = 64
const MAX_BOT_ID_LENGTH = 128
const MAX_BOT_NAME_LENGTH = 128
const MAX_BOT_AVATAR_LENGTH = 65_536
const MAX_TOTAL_AVATAR_CHARS_PER_DEVICE = 512 * 1024
const MAX_CAPABILITIES = 32
const MAX_CAPABILITY_LENGTH = 64
const MAX_IDENTIFIER_LENGTH = 128
const MAX_ROUTE_IMAGES = 4
const MAX_ROUTE_IMAGE_BASE64_LENGTH = 2_000_000
const MAX_ROUTE_IMAGES_TOTAL_LENGTH = 3_000_000
const STRICT_BASE64_PATTERN = /^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/

function decodeAndCheckImageMagic(
  subtype: string,
  payload: string,
): 'image/jpeg' | 'image/png' | 'image/webp' | 'image/gif' | null {
  if (!payload || payload.length % 4 !== 0 || !STRICT_BASE64_PATTERN.test(payload)) return null
  let binary: string
  try {
    binary = atob(payload.slice(0, 16))
  } catch {
    return null
  }
  if (subtype === 'jpeg' || subtype === 'jpg') {
    if (binary.length < 3) return null
    if (binary.charCodeAt(0) !== 0xff || binary.charCodeAt(1) !== 0xd8 || binary.charCodeAt(2) !== 0xff) return null
    return 'image/jpeg'
  }
  if (subtype === 'png') {
    const pngSig = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]
    if (binary.length < pngSig.length) return null
    for (let i = 0; i < pngSig.length; i += 1) {
      if (binary.charCodeAt(i) !== pngSig[i]) return null
    }
    return 'image/png'
  }
  if (subtype === 'webp') {
    if (binary.length < 12) return null
    const ok =
      binary.charCodeAt(0) === 0x52 &&
      binary.charCodeAt(1) === 0x49 &&
      binary.charCodeAt(2) === 0x46 &&
      binary.charCodeAt(3) === 0x46 &&
      binary.charCodeAt(8) === 0x57 &&
      binary.charCodeAt(9) === 0x45 &&
      binary.charCodeAt(10) === 0x42 &&
      binary.charCodeAt(11) === 0x50
    return ok ? 'image/webp' : null
  }
  if (subtype === 'gif') {
    if (binary.length < 6) return null
    const sig = binary.slice(0, 6)
    return sig === 'GIF87a' || sig === 'GIF89a' ? 'image/gif' : null
  }
  return null
}

function normalizeBotAvatar(raw: string): string | null {
  if (raw.length > MAX_BOT_AVATAR_LENGTH) return null
  const match = /^data:image\/(jpeg|jpg|png|webp);base64,([A-Za-z0-9+/=]+)$/.exec(raw)
  if (!match) return null
  const subtype = match[1]
  const payload = match[2]
  if (!subtype || !payload) return null
  const mime = decodeAndCheckImageMagic(subtype, payload)
  return mime && mime !== 'image/gif' ? `data:${mime};base64,${payload}` : null
}

export function validateRouteImages(raw: unknown): RouteImagePart[] {
  if (!Array.isArray(raw)) throw new ProtocolError('images must be an array')
  if (raw.length > MAX_ROUTE_IMAGES) throw new ProtocolError('too many images')
  let totalChars = 0
  return raw.map((item) => {
    if (!isRecord(item)) throw new ProtocolError('image must be an object')
    const data = requireString(item, 'data')
    const mimeRaw = requireString(item, 'mimeType')
    if (data.length === 0 || data.length > MAX_ROUTE_IMAGE_BASE64_LENGTH) {
      throw new ProtocolError('image payload is invalid or too large')
    }
    totalChars += data.length
    if (totalChars > MAX_ROUTE_IMAGES_TOTAL_LENGTH) {
      throw new ProtocolError('total image payload exceeds limit')
    }
    const subtypeMatch = /^image\/(jpeg|jpg|png|webp|gif)$/.exec(mimeRaw.toLowerCase())
    const mime = subtypeMatch?.[1] ? decodeAndCheckImageMagic(subtypeMatch[1], data) : null
    if (!mime) throw new ProtocolError('image format or data is invalid')
    const part: RouteImagePart = { data, mimeType: mime }
    if (typeof item.name === 'string' && item.name.trim() !== '') {
      part.name = item.name.trim().slice(0, 128)
    }
    return part
  })
}

function requireBots(value: unknown, tolerantAvatars: boolean): BotInfo[] {
  if (!Array.isArray(value)) throw new ProtocolError('bots must be an array')
  let totalAvatarChars = 0
  return value.slice(0, MAX_BOTS_PER_DEVICE).map((entry) => {
    if (!isRecord(entry)) throw new ProtocolError('bot must be an object')
    const bot: BotInfo = {
      id: requireBoundedString(entry, 'id', MAX_BOT_ID_LENGTH, true),
      name: requireBoundedString(entry, 'name', MAX_BOT_NAME_LENGTH, true),
    }
    if (typeof entry.avatar === 'string' && entry.avatar !== '') {
      const normalized = normalizeBotAvatar(entry.avatar)
      if (!normalized) {
        if (!tolerantAvatars) throw new ProtocolError('bot.avatar is invalid')
      } else if (totalAvatarChars + normalized.length <= MAX_TOTAL_AVATAR_CHARS_PER_DEVICE) {
        totalAvatarChars += normalized.length
        bot.avatar = normalized
      }
    } else if (entry.avatar !== undefined && entry.avatar !== null && entry.avatar !== '' && !tolerantAvatars) {
      throw new ProtocolError('bot.avatar is invalid')
    }
    return bot
  })
}

function requireIdentifier(record: Record<string, unknown>, key: string): string {
  const value = record[key]
  if (typeof value !== 'string' || value.trim() === '' || value.length > MAX_IDENTIFIER_LENGTH) {
    throw new ProtocolError(`${key} must be a non-empty identifier`)
  }
  return value
}

function requireBoundedString(record: Record<string, unknown>, key: string, maxLength: number, nonEmpty = false): string {
  const value = record[key]
  if (typeof value !== 'string') throw new ProtocolError(`${key} must be a string`)
  if (nonEmpty && value.trim() === '') throw new ProtocolError(`${key} must not be empty`)
  return value.slice(0, maxLength)
}

function requireCapabilities(record: Record<string, unknown>, key: string): string[] {
  const arr = requireStringArray(record, key)
  return arr
    .filter((entry) => entry.trim() !== '')
    .slice(0, MAX_CAPABILITIES)
    .map((entry) => entry.slice(0, MAX_CAPABILITY_LENGTH))
}

function optionalCapabilities(value: unknown): string[] | undefined {
  if (value === undefined || value === null) return undefined
  if (!isStringArray(value)) return undefined
  return value
    .filter((entry) => entry.trim() !== '')
    .slice(0, MAX_CAPABILITIES)
    .map((entry) => entry.slice(0, MAX_CAPABILITY_LENGTH))
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

function requireRouteMethod(record: Record<string, unknown>, key: string, routeId?: string): RouteMethod {
  const value = record[key]
  if (typeof value !== 'string' || !(ROUTE_METHODS as readonly string[]).includes(value)) {
    if (routeId) {
      throw new UnknownRouteMethodError(routeId, typeof value === 'string' ? value : 'invalid')
    }
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
