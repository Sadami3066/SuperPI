import WebSocket from 'ws'
import {
  PROTOCOL_VERSION,
  encodeFrame,
  parseServerFrame,
  type BotInfo,
  type ClientFrame,
  type DeviceInfo,
  type ServerFrame,
} from '@superpi/protocol'
import { websocketUrl } from './pairing.ts'

export type RelayConnectionState = 'connecting' | 'online' | 'offline' | 'error'

export interface RelayClientOptions {
  origin: string
  caPem: string | null
  deviceId: string
  token: string
  bots: () => BotInfo[]
  onState: (state: RelayConnectionState, error?: string) => void
  onDevices: (devices: DeviceInfo[]) => void
  onFrame?: (frame: ServerFrame) => void
}

const MAX_BACKOFF_MS = 30_000

export class RelayClient {
  private socket: WebSocket | null = null
  private stopped = true
  private retryTimer: ReturnType<typeof setTimeout> | null = null
  private backoffMs = 1_000

  constructor(private readonly options: RelayClientOptions) {}

  get connected(): boolean {
    return this.socket?.readyState === WebSocket.OPEN
  }

  connect(): void {
    this.stopped = false
    this.open()
  }

  disconnect(): void {
    this.stopped = true
    if (this.retryTimer) {
      clearTimeout(this.retryTimer)
      this.retryTimer = null
    }
    const socket = this.socket
    this.socket = null
    if (socket) socket.close()
  }

  send(frame: ClientFrame): void {
    if (this.socket?.readyState === WebSocket.OPEN) {
      this.socket.send(encodeFrame(frame))
    }
  }

  private open(): void {
    if (this.stopped) return
    this.options.onState('connecting')
    const url = websocketUrl(this.options.origin)
    const socket = new WebSocket(url, {
      headers: { authorization: `Bearer ${this.options.token}` },
      ca: this.options.caPem && url.startsWith('wss:') ? [this.options.caPem] : undefined,
    })
    this.socket = socket

    socket.on('open', () => {
      this.backoffMs = 1_000
      this.send({
        type: 'hello',
        protocolVersion: PROTOCOL_VERSION,
        deviceId: this.options.deviceId,
        platform: 'desktop',
        appVersion: '0.1.0',
        bots: this.options.bots(),
        capabilities: ['remote-bot'],
      })
    })

    socket.on('message', (raw) => {
      let frame: ServerFrame
      try {
        frame = parseServerFrame(JSON.parse(raw.toString()))
      } catch (error) {
        this.options.onState('error', error instanceof Error ? error.message : 'invalid relay frame')
        return
      }
      if (frame.type === 'hello_ok') {
        this.options.onState('online')
        this.options.onDevices(frame.devices)
        return
      }
      this.options.onFrame?.(frame)
    })

    socket.on('close', (code) => {
      if (this.socket !== socket) return
      this.socket = null
      if (this.stopped) {
        this.options.onState('offline')
        return
      }
      this.options.onState('offline', code === 1000 ? undefined : `relay closed the connection (${code})`)
      this.scheduleReconnect()
    })

    socket.on('error', (error) => {
      this.options.onState('error', error.message)
    })
  }

  private scheduleReconnect(): void {
    if (this.stopped || this.retryTimer) return
    const delay = this.backoffMs + Math.floor(Math.random() * 250)
    this.backoffMs = Math.min(this.backoffMs * 2, MAX_BACKOFF_MS)
    this.retryTimer = setTimeout(() => {
      this.retryTimer = null
      this.open()
    }, delay)
  }
}
