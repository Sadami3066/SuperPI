export const PROTOCOL_VERSION = 1

export const RELAY_CAPABILITIES = [
  'remote-bot',
  'bot-sessions',
  'bot-images',
  'bot-approval',
  'bot-live-sync',
  'bot-manage',
  'session-manage',
  'workspace-files',
  'sync-events',
  'bot-controls',
  'session-controls',
  'bot-capabilities',
  'present-artifacts',
] as const
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
  'permission_denied',
  'already_exists',
  'payload_too_large',
  'conflict',
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
  'bot.create',
  'bot.update',
  'bot.delete',
  'bot.models',
  'bot.set_model',
  'bot.set_thinking',
  'bot.commands',
  'bot.instructions.get',
  'bot.instructions.set',
  'bot.reload',
  'bot.capabilities.get',
  'bot.capabilities.set',
  'session.create',
  'session.rename',
  'session.delete',
  'session.update_meta',
  'session.compact',
  'session.retry',
  'file.list',
  'file.stat',
  'file.read_chunk',
  'file.write_chunk',
  'file.mkdir',
  'file.rename',
  'file.delete',
  'artifact.read_chunk',
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
  archived?: boolean
  busy?: boolean
}

export interface RemoteFileEntry {
  name: string
  path: string
  type: 'file' | 'directory'
  size: number
  modifiedAt: number
  mimeType?: string
}

export const SYNC_EVENT_KINDS = [
  'sessions_changed',
  'bot_activity',
  'approval_changed',
  'model_changed',
  'capabilities_changed',
] as const
export type SyncEventKind = (typeof SYNC_EVENT_KINDS)[number]

export interface SyncEventFrame {
  type: 'sync_event'
  deviceId?: string
  kind: SyncEventKind
  botId: string
  sessionId?: string
  busy?: boolean
  pendingApprovals?: number
  provider?: string
  modelId?: string
  thinkingLevel?: string
  ts: number
}

export interface BotQuestionOption {
  label: string
  description?: string
}

export interface BotQuestionItem {
  id: string
  header?: string
  question: string
  multiSelect?: boolean
  options?: BotQuestionOption[]
}

export interface BotQuestionAnswer {
  id: string
  selected?: string[]
  custom?: string
  skipped?: boolean
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

export interface BotUserMessagePayload {
  text: string
  images?: RouteImagePart[]
}

export type BotEvent =
  | {
      type: 'agent_start'
      runId?: string
      sessionId?: string
      title?: string
      userMessage?: BotUserMessagePayload
      originRouteId?: string
    }
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
      toolCallId?: string
      questions?: BotQuestionItem[]
      resolved?: boolean
    }
  | { type: 'user_message'; text: string; images?: RouteImagePart[]; sessionId?: string }
  | { type: 'queue_update'; steering: string[]; followUp: string[]; sessionId?: string; userMessage?: BotUserMessagePayload }
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

export type ClientFrame =
  | HelloFrame
  | RouteFrame
  | RouteOkFrame
  | RouteErrorFrame
  | RouteEventFrame
  | SyncEventFrame
  | PingFrame

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
  name?: string
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
  | SyncEventFrame
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
      return (
        (value.runId === undefined || typeof value.runId === 'string') &&
        (value.sessionId === undefined || typeof value.sessionId === 'string') &&
        (value.title === undefined || typeof value.title === 'string') &&
        (value.originRouteId === undefined || typeof value.originRouteId === 'string') &&
        (value.userMessage === undefined || isBotUserMessagePayload(value.userMessage))
      )
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
        isNonEmptyString(value.approvalId) &&
        isNonEmptyString(value.kind) &&
        isNonEmptyString(value.summary) &&
        (value.title === undefined || typeof value.title === 'string') &&
        (value.message === undefined || typeof value.message === 'string') &&
        (value.options === undefined || isStringArray(value.options)) &&
        (value.placeholder === undefined || typeof value.placeholder === 'string') &&
        (value.prefill === undefined || typeof value.prefill === 'string') &&
        (value.toolCallId === undefined || typeof value.toolCallId === 'string') &&
        (value.resolved === undefined || typeof value.resolved === 'boolean') &&
        (value.kind === 'question' && value.resolved !== true
          ? isBotQuestionItems(value.questions)
          : value.questions === undefined || isBotQuestionItems(value.questions))
      )
    case 'user_message':
      return (
        (value.sessionId === undefined || typeof value.sessionId === 'string') &&
        isBotUserMessagePayload(value)
      )
    case 'queue_update':
      return (
        isStringArray(value.steering) &&
        isStringArray(value.followUp) &&
        (value.sessionId === undefined || typeof value.sessionId === 'string') &&
        (value.userMessage === undefined || isBotUserMessagePayload(value.userMessage))
      )
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
    case 'sync_event':
      return parseSyncEvent(value, false)
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
      if (value.name !== undefined) {
        frame.name = requireBoundedString(value, 'name', 128, true)
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
    case 'sync_event':
      return parseSyncEvent(value, true)
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
  nextParams = validateRouteParams(method, nextParams, id)
  const base: RouteFrame = { type: 'route', id, to, method, params: nextParams }
  if (!inbound) return base
  return { ...base, from: from as string }
}

function parseSyncEvent(value: Record<string, unknown>, requireDeviceId: boolean): SyncEventFrame {
  let deviceId: string | undefined
  if (requireDeviceId || value.deviceId !== undefined) {
    deviceId = requireIdentifier(value, 'deviceId')
  }
  const kind = requireSyncEventKind(value, 'kind')
  const botId = requireBoundedString(value, 'botId', MAX_BOT_ID_LENGTH, true)
  const ts = requireNumber(value, 'ts')
  if (ts < 0) throw new ProtocolError('ts must be non-negative')
  const frame: SyncEventFrame = { type: 'sync_event', kind, botId, ts }
  if (deviceId !== undefined) frame.deviceId = deviceId
  if (value.sessionId !== undefined) {
    frame.sessionId = requireBoundedString(value, 'sessionId', MAX_IDENTIFIER_LENGTH, true)
  }
  if (value.busy !== undefined) {
    frame.busy = requireBoolean(value, 'busy')
  }
  if (value.pendingApprovals !== undefined) {
    const count = requireNumber(value, 'pendingApprovals')
    if (!Number.isInteger(count) || count < 0) {
      throw new ProtocolError('pendingApprovals must be a non-negative integer')
    }
    frame.pendingApprovals = count
  }
  if (value.provider !== undefined) {
    frame.provider = requireBoundedString(value, 'provider', 128, true)
  }
  if (value.modelId !== undefined) {
    frame.modelId = requireBoundedString(value, 'modelId', 256, true)
  }
  if (value.thinkingLevel !== undefined) {
    frame.thinkingLevel = requireBoundedString(value, 'thinkingLevel', 32, true)
  }
  return frame
}

function parseRouteEvent(value: Record<string, unknown>): RouteEventFrame {
  const event = value.event
  if (!isBotEvent(event)) throw new ProtocolError('route_event.event is not a valid bot event')
  let normalizedEvent: BotEvent = event
  if (event.type === 'user_message' && event.images !== undefined) {
    normalizedEvent = { ...event, images: validateRouteImages(event.images) }
  } else if (
    (event.type === 'agent_start' || event.type === 'queue_update') &&
    event.userMessage?.images !== undefined
  ) {
    normalizedEvent = {
      ...event,
      userMessage: {
        ...event.userMessage,
        images: validateRouteImages(event.userMessage.images),
      },
    }
  }
  return {
    type: 'route_event',
    id: requireIdentifier(value, 'id'),
    seq: requireNumber(value, 'seq'),
    event: normalizedEvent,
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

export const MAX_FILE_CHUNK_BYTES = 256 * 1024
export const MAX_FILE_CHUNK_BASE64_CHARS = 360_000
export const MAX_FILE_TRANSFER_BYTES = 50 * 1024 * 1024
export const MAX_RELATIVE_PATH_LENGTH = 512

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
const SHA256_HEX_PATTERN = /^[a-fA-F0-9]{64}$/

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

function isNonEmptyString(value: unknown): value is string {
  return typeof value === 'string' && value.trim() !== ''
}

function isBotUserMessagePayload(value: unknown): value is BotUserMessagePayload {
  if (!isRecord(value) || typeof value.text !== 'string') return false
  if (value.images === undefined) return value.text.trim() !== ''
  try {
    const sanitized = validateRouteImages(value.images)
    return value.text.trim() !== '' || sanitized.length > 0
  } catch {
    return false
  }
}

function isBotQuestionOption(value: unknown): value is BotQuestionOption {
  if (!isRecord(value)) return false
  if (!isNonEmptyString(value.label)) return false
  return value.description === undefined || typeof value.description === 'string'
}

function isBotQuestionItem(value: unknown): value is BotQuestionItem {
  if (!isRecord(value)) return false
  if (!isNonEmptyString(value.id) || !isNonEmptyString(value.question)) return false
  if (value.header !== undefined && typeof value.header !== 'string') return false
  if (value.multiSelect !== undefined && typeof value.multiSelect !== 'boolean') return false
  if (
    value.options !== undefined &&
    (!Array.isArray(value.options) || value.options.length > 16 || !value.options.every(isBotQuestionOption))
  ) {
    return false
  }
  return true
}

function isBotQuestionItems(value: unknown): value is BotQuestionItem[] {
  if (!Array.isArray(value) || value.length === 0 || value.length > 16) return false
  const seen = new Set<string>()
  for (const item of value) {
    if (!isBotQuestionItem(item)) return false
    const key = item.id.trim()
    if (seen.has(key)) return false
    seen.add(key)
  }
  return true
}

function isBotQuestionAnswer(value: unknown): value is BotQuestionAnswer {
  if (!isRecord(value)) return false
  if (!isNonEmptyString(value.id)) return false
  if (value.selected !== undefined && !isStringArray(value.selected)) return false
  if (value.custom !== undefined && typeof value.custom !== 'string') return false
  if (value.skipped !== undefined && typeof value.skipped !== 'boolean') return false
  return true
}

function validateBotApprovalParams(params: Record<string, unknown>, routeId: string): void {
  if (!isNonEmptyString(params.approvalId)) {
    throw new RouteValidationError(routeId, 'approvalId is required')
  }
  if (params.answers !== undefined) {
    if (!Array.isArray(params.answers) || params.answers.length > 16 || !params.answers.every(isBotQuestionAnswer)) {
      throw new RouteValidationError(routeId, 'invalid approval answers')
    }
  }
  const hasResolution =
    params.cancelled === true ||
    typeof params.confirmed === 'boolean' ||
    typeof params.value === 'string' ||
    params.answers !== undefined
  if (!hasResolution) {
    throw new RouteValidationError(routeId, 'approval response payload is required')
  }
}

export function validateRelativeWorkspacePath(raw: string, allowEmpty = false): string {
  if (typeof raw !== 'string') {
    throw new ProtocolError('path must be a string')
  }
  if (raw.length > MAX_RELATIVE_PATH_LENGTH) {
    throw new ProtocolError('path exceeds maximum length')
  }
  if (raw.includes('\u0000') || raw.includes('\\') || raw.includes(':')) {
    throw new ProtocolError('path contains invalid characters')
  }
  if (raw.startsWith('/')) {
    throw new ProtocolError('path must be relative')
  }
  const segments = raw.split('/').filter((segment) => segment !== '')
  if (segments.length === 0) {
    if (allowEmpty) return ''
    throw new ProtocolError('path must not be empty')
  }
  for (const segment of segments) {
    if (segment === '.' || segment === '..') {
      throw new ProtocolError('path traversal is not allowed')
    }
  }
  return segments.join('/')
}

function validateRouteParams(
  method: RouteMethod,
  params: Record<string, unknown>,
  routeId: string,
): Record<string, unknown> {
  switch (method) {
    case 'bot.approval':
      validateBotApprovalParams(params, routeId)
      return params
    case 'bot.create':
      return validateBotCreateParams(params, routeId)
    case 'bot.update':
      return validateBotUpdateParams(params, routeId)
    case 'bot.delete':
    case 'bot.commands':
    case 'bot.instructions.get':
    case 'bot.capabilities.get':
      requireRouteBotId(params, routeId)
      return params
    case 'bot.capabilities.set':
      return validateBotCapabilitiesSetParams(params, routeId)
    case 'bot.models':
    case 'bot.reload':
      requireRouteBotId(params, routeId)
      if (params.sessionId !== undefined) {
        requireRouteIdentifier(params, 'sessionId', routeId)
      }
      return params
    case 'bot.set_model':
      requireRouteBotId(params, routeId)
      requireRouteBoundedNonEmpty(params, 'provider', 128, routeId)
      requireRouteBoundedNonEmpty(params, 'modelId', 256, routeId)
      if (params.sessionId !== undefined) {
        requireRouteIdentifier(params, 'sessionId', routeId)
      }
      if (params.thinkingLevel !== undefined) {
        requireRouteBoundedNonEmpty(params, 'thinkingLevel', 32, routeId)
      }
      return params
    case 'bot.set_thinking':
      requireRouteBotId(params, routeId)
      requireRouteBoundedNonEmpty(params, 'thinkingLevel', 32, routeId)
      if (params.sessionId !== undefined) {
        requireRouteIdentifier(params, 'sessionId', routeId)
      }
      return params
    case 'bot.instructions.set':
      requireRouteBotId(params, routeId)
      if (typeof params.content !== 'string') {
        throw new RouteValidationError(routeId, 'content must be a string')
      }
      if (Buffer.byteLength(params.content, 'utf8') > 65536) {
        throw new RouteValidationError(routeId, 'instructions exceed 64 KiB limit', 'payload_too_large')
      }
      return params
    case 'session.create':
      requireRouteBotId(params, routeId)
      if (params.title !== undefined && (typeof params.title !== 'string' || params.title.length > 128)) {
        throw new RouteValidationError(routeId, 'title must be a string within 128 characters')
      }
      return params
    case 'session.rename':
      requireRouteBotId(params, routeId)
      requireRouteIdentifier(params, 'sessionId', routeId)
      requireRouteBoundedNonEmpty(params, 'title', 128, routeId)
      return params
    case 'session.delete':
    case 'session.retry':
      requireRouteBotId(params, routeId)
      requireRouteIdentifier(params, 'sessionId', routeId)
      return params
    case 'session.compact':
      requireRouteBotId(params, routeId)
      if (params.sessionId !== undefined) {
        requireRouteIdentifier(params, 'sessionId', routeId)
      }
      if (
        params.customInstructions !== undefined &&
        (typeof params.customInstructions !== 'string' || params.customInstructions.length > 2000)
      ) {
        throw new RouteValidationError(routeId, 'customInstructions must be a string within 2000 characters')
      }
      return params
    case 'session.update_meta':
      requireRouteBotId(params, routeId)
      requireRouteIdentifier(params, 'sessionId', routeId)
      if (params.pinned !== undefined && typeof params.pinned !== 'boolean') {
        throw new RouteValidationError(routeId, 'pinned must be a boolean')
      }
      if (params.archived !== undefined && typeof params.archived !== 'boolean') {
        throw new RouteValidationError(routeId, 'archived must be a boolean')
      }
      if (typeof params.pinned !== 'boolean' && typeof params.archived !== 'boolean') {
        throw new RouteValidationError(routeId, 'pinned or archived is required')
      }
      return params
    case 'file.list':
      return validateFileListParams(params, routeId)
    case 'file.stat':
      requireRouteBotId(params, routeId)
      return { ...params, path: requireRoutePath(params.path, false, routeId) }
    case 'file.read_chunk':
      return validateFileReadChunkParams(params, routeId)
    case 'file.write_chunk':
      return validateFileWriteChunkParams(params, routeId)
    case 'file.mkdir':
      requireRouteBotId(params, routeId)
      return { ...params, path: requireRoutePath(params.path, false, routeId) }
    case 'file.rename':
      requireRouteBotId(params, routeId)
      return {
        ...params,
        from: requireRoutePath(params.from, false, routeId),
        to: requireRoutePath(params.to, false, routeId),
      }
    case 'file.delete':
      requireRouteBotId(params, routeId)
      if (params.recursive !== undefined && typeof params.recursive !== 'boolean') {
        throw new RouteValidationError(routeId, 'recursive must be a boolean')
      }
      return { ...params, path: requireRoutePath(params.path, false, routeId) }
    case 'artifact.read_chunk':
      return validateArtifactReadChunkParams(params, routeId)
    default:
      return params
  }
}

function validateBotCapabilitiesSetParams(
  params: Record<string, unknown>,
  routeId: string,
): Record<string, unknown> {
  requireRouteBotId(params, routeId)
  const policy = params.policy
  if (!isRecord(policy)) {
    throw new RouteValidationError(routeId, 'policy must be an object')
  }
  if (policy.allCapabilities !== undefined && typeof policy.allCapabilities !== 'boolean') {
    throw new RouteValidationError(routeId, 'policy.allCapabilities must be a boolean')
  }
  for (const field of ['skills', 'plugins', 'mcpServers'] as const) {
    const arr = policy[field]
    if (arr !== undefined) {
      if (!isStringArray(arr) || arr.length > 500 || arr.some((entry) => entry.length > 256)) {
        throw new RouteValidationError(routeId, `policy.${field} must be a valid string array`)
      }
    }
  }
  if (policy.screenTools !== undefined && typeof policy.screenTools !== 'boolean') {
    throw new RouteValidationError(routeId, 'policy.screenTools must be a boolean')
  }
  if (policy.miniappTools !== undefined && typeof policy.miniappTools !== 'boolean') {
    throw new RouteValidationError(routeId, 'policy.miniappTools must be a boolean')
  }
  return params
}

function validateArtifactReadChunkParams(
  params: Record<string, unknown>,
  routeId: string,
): Record<string, unknown> {
  requireRouteBotId(params, routeId)
  const artifactId = params.artifactId
  if (typeof artifactId !== 'string' || !/^[A-Za-z0-9_-]{1,128}$/.test(artifactId)) {
    throw new RouteValidationError(routeId, 'artifactId must be a safe identifier')
  }
  const offset = params.offset
  if (typeof offset !== 'number' || !Number.isInteger(offset) || offset < 0) {
    throw new RouteValidationError(routeId, 'offset must be a non-negative integer')
  }
  const length = params.length
  if (typeof length !== 'number' || !Number.isInteger(length) || length < 1 || length > MAX_FILE_CHUNK_BYTES) {
    const code = typeof length === 'number' && length > MAX_FILE_CHUNK_BYTES ? 'payload_too_large' : 'bad_request'
    throw new RouteValidationError(routeId, `length must be an integer between 1 and ${MAX_FILE_CHUNK_BYTES}`, code)
  }
  return params
}

function validateBotCreateParams(params: Record<string, unknown>, routeId: string): Record<string, unknown> {
  requireRouteBoundedNonEmpty(params, 'name', MAX_BOT_NAME_LENGTH, routeId)
  if (params.cwd !== undefined) {
    throw new RouteValidationError(routeId, 'cwd is not supported')
  }
  if (
    params.intro !== undefined &&
    params.intro !== null &&
    (typeof params.intro !== 'string' || params.intro.length > 256)
  ) {
    throw new RouteValidationError(routeId, 'intro must be a string within 256 characters')
  }
  if (params.avatar !== undefined && params.avatar !== null) {
    if (typeof params.avatar !== 'string') {
      throw new RouteValidationError(routeId, 'avatar must be a valid data URL')
    }
    const normalized = normalizeBotAvatar(params.avatar)
    if (!normalized) {
      throw new RouteValidationError(routeId, 'avatar image format or size is invalid')
    }
    return { ...params, avatar: normalized }
  }
  return params
}

function validateBotUpdateParams(params: Record<string, unknown>, routeId: string): Record<string, unknown> {
  requireRouteBotId(params, routeId)
  if (params.name !== undefined) {
    requireRouteBoundedNonEmpty(params, 'name', MAX_BOT_NAME_LENGTH, routeId)
  }
  if (
    params.intro !== undefined &&
    params.intro !== null &&
    (typeof params.intro !== 'string' || params.intro.length > 256)
  ) {
    throw new RouteValidationError(routeId, 'intro must be a string within 256 characters')
  }
  if (params.avatar !== undefined && params.avatar !== null) {
    if (typeof params.avatar !== 'string') {
      throw new RouteValidationError(routeId, 'avatar must be a valid data URL or null')
    }
    const normalized = normalizeBotAvatar(params.avatar)
    if (!normalized) {
      throw new RouteValidationError(routeId, 'avatar image format or size is invalid')
    }
    return { ...params, avatar: normalized }
  }
  return params
}

function validateFileListParams(params: Record<string, unknown>, routeId: string): Record<string, unknown> {
  requireRouteBotId(params, routeId)
  let next = params
  if (params.path !== undefined) {
    next = { ...next, path: requireRoutePath(params.path, true, routeId) }
  }
  if (params.query !== undefined && (typeof params.query !== 'string' || params.query.length > 256)) {
    throw new RouteValidationError(routeId, 'query must be a string within 256 characters')
  }
  if (params.limit !== undefined) {
    if (typeof params.limit !== 'number' || !Number.isInteger(params.limit) || params.limit < 1 || params.limit > 500) {
      throw new RouteValidationError(routeId, 'limit must be an integer between 1 and 500')
    }
  }
  return next
}

function validateFileReadChunkParams(params: Record<string, unknown>, routeId: string): Record<string, unknown> {
  requireRouteBotId(params, routeId)
  const safePath = requireRoutePath(params.path, false, routeId)
  const offset = params.offset
  if (typeof offset !== 'number' || !Number.isInteger(offset) || offset < 0) {
    throw new RouteValidationError(routeId, 'offset must be a non-negative integer')
  }
  const length = params.length
  if (typeof length !== 'number' || !Number.isInteger(length) || length < 1 || length > MAX_FILE_CHUNK_BYTES) {
    const code = typeof length === 'number' && length > MAX_FILE_CHUNK_BYTES ? 'payload_too_large' : 'bad_request'
    throw new RouteValidationError(routeId, `length must be an integer between 1 and ${MAX_FILE_CHUNK_BYTES}`, code)
  }
  return { ...params, path: safePath }
}

function validateFileWriteChunkParams(params: Record<string, unknown>, routeId: string): Record<string, unknown> {
  requireRouteBotId(params, routeId)
  const safePath = requireRoutePath(params.path, false, routeId)
  if (params.transferId !== undefined) {
    requireRouteIdentifier(params, 'transferId', routeId)
  }
  const offset = params.offset
  if (typeof offset !== 'number' || !Number.isInteger(offset) || offset < 0) {
    throw new RouteValidationError(routeId, 'offset must be a non-negative integer')
  }
  if (offset > MAX_FILE_TRANSFER_BYTES) {
    throw new RouteValidationError(routeId, 'file exceeds maximum transfer size', 'payload_too_large')
  }
  if (typeof params.eof !== 'boolean') {
    throw new RouteValidationError(routeId, 'eof must be a boolean')
  }
  if (typeof params.data !== 'string') {
    throw new RouteValidationError(routeId, 'data must be a base64 string')
  }
  if (params.data.length > MAX_FILE_CHUNK_BASE64_CHARS) {
    throw new RouteValidationError(routeId, 'chunk payload exceeds limit', 'payload_too_large')
  }
  let decodedBytes = 0
  if (params.data === '') {
    if (!params.eof) {
      throw new RouteValidationError(routeId, 'data must not be empty before eof')
    }
  } else {
    decodedBytes = base64DecodedLength(params.data)
    if (decodedBytes < 0) {
      throw new RouteValidationError(routeId, 'data must be valid base64')
    }
    if (decodedBytes > MAX_FILE_CHUNK_BYTES) {
      throw new RouteValidationError(routeId, 'chunk exceeds maximum byte size', 'payload_too_large')
    }
  }
  if (offset + decodedBytes > MAX_FILE_TRANSFER_BYTES) {
    throw new RouteValidationError(routeId, 'file exceeds maximum transfer size', 'payload_too_large')
  }
  if (params.overwrite !== undefined && typeof params.overwrite !== 'boolean') {
    throw new RouteValidationError(routeId, 'overwrite must be a boolean')
  }
  if (
    params.expectedSha256 !== undefined &&
    (typeof params.expectedSha256 !== 'string' || !SHA256_HEX_PATTERN.test(params.expectedSha256))
  ) {
    throw new RouteValidationError(routeId, 'expectedSha256 must be a 64-character hex digest')
  }
  return { ...params, path: safePath }
}

function base64DecodedLength(data: string): number {
  if (data.length % 4 !== 0 || !STRICT_BASE64_PATTERN.test(data)) return -1
  let padding = 0
  if (data.endsWith('==')) padding = 2
  else if (data.endsWith('=')) padding = 1
  return (data.length * 3) / 4 - padding
}

function requireRouteBotId(params: Record<string, unknown>, routeId: string): string {
  return requireRouteBoundedNonEmpty(params, 'botId', MAX_BOT_ID_LENGTH, routeId)
}

function requireRouteIdentifier(params: Record<string, unknown>, key: string, routeId: string): string {
  return requireRouteBoundedNonEmpty(params, key, MAX_IDENTIFIER_LENGTH, routeId)
}

function requireRouteBoundedNonEmpty(
  params: Record<string, unknown>,
  key: string,
  maxLength: number,
  routeId: string,
): string {
  const value = params[key]
  if (typeof value !== 'string' || value.trim() === '' || value.length > maxLength) {
    throw new RouteValidationError(routeId, `${key} must be a non-empty string within ${maxLength} characters`)
  }
  return value
}

function requireRoutePath(value: unknown, allowEmpty: boolean, routeId: string): string {
  if (typeof value !== 'string') {
    throw new RouteValidationError(routeId, 'path must be a string')
  }
  try {
    return validateRelativeWorkspacePath(value, allowEmpty)
  } catch (error) {
    throw new RouteValidationError(routeId, error instanceof Error ? error.message : 'invalid workspace path')
  }
}

function requireSyncEventKind(record: Record<string, unknown>, key: string): SyncEventKind {
  const value = record[key]
  if (typeof value !== 'string' || !(SYNC_EVENT_KINDS as readonly string[]).includes(value)) {
    throw new ProtocolError(`${key} must be a known sync event kind`)
  }
  return value as SyncEventKind
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
