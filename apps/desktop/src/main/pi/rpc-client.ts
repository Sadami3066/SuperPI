import { once } from 'node:events'
import { spawn, type ChildProcess } from 'node:child_process'

export interface PiRpcOptions {
  command: string
  args?: string[]
  cwd: string
  env?: NodeJS.ProcessEnv
  requestTimeoutMs?: number
}

export interface PiRpcEvent {
  type: string
  [key: string]: unknown
}

interface PendingRequest {
  resolve: (value: unknown) => void
  reject: (error: Error) => void
  timer: ReturnType<typeof setTimeout>
}

const DEFAULT_TIMEOUT_MS = 60_000
const STDERR_LIMIT = 64 * 1024

export class PiRpcClient {
  private child: ChildProcess | null = null
  private buffer = ''
  private nextId = 0
  private readonly pending = new Map<string, PendingRequest>()
  private readonly eventListeners = new Set<(event: PiRpcEvent) => void>()
  private readonly exitListeners = new Set<(error: Error | null) => void>()
  private stderrText = ''
  private stopped = false
  private exitDetail: { code: number | null; signal: string | null } | null = null

  constructor(private readonly options: PiRpcOptions) {}

  get pid(): number | null {
    return this.child?.pid ?? null
  }

  get running(): boolean {
    return this.child !== null
  }

  async start(): Promise<void> {
    if (this.child) return
    this.stopped = false
    const child = spawn(this.options.command, this.options.args ?? [], {
      cwd: this.options.cwd,
      env: this.options.env ?? process.env,
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    this.child = child
    child.stdout?.setEncoding('utf8')
    child.stdout?.on('data', (chunk: string) => this.consume(chunk))
    child.stderr?.setEncoding('utf8')
    child.stderr?.on('data', (chunk: string) => {
      this.stderrText = (this.stderrText + chunk).slice(-STDERR_LIMIT)
    })
    child.on('exit', (code, signal) => this.handleExit(code, signal))
    child.on('error', (error) => this.handleProcessError(error))
    try {
      await once(child, 'spawn')
    } catch (error) {
      this.child = null
      throw error instanceof Error ? error : new Error(String(error))
    }
  }

  onEvent(listener: (event: PiRpcEvent) => void): () => void {
    this.eventListeners.add(listener)
    return () => this.eventListeners.delete(listener)
  }

  onExit(listener: (error: Error | null) => void): () => void {
    this.exitListeners.add(listener)
    return () => this.exitListeners.delete(listener)
  }

  getStderr(): string {
    return this.stderrText
  }

  getLastExit(): { code: number | null; signal: string | null } | null {
    return this.exitDetail
  }

  send<T = unknown>(command: Record<string, unknown>, timeoutMs = this.options.requestTimeoutMs ?? DEFAULT_TIMEOUT_MS): Promise<T> {
    const child = this.child
    const stdin = child?.stdin
    if (!child || !stdin || stdin.destroyed) {
      return Promise.reject(new Error('pi rpc process is not running'))
    }
    const id = `c${++this.nextId}`
    return new Promise<T>((resolve, reject) => {
      const timer = setTimeout(() => {
        this.pending.delete(id)
        reject(new Error(`pi command ${String(command.type)} timed out after ${timeoutMs}ms`))
      }, timeoutMs)
      this.pending.set(id, { resolve: resolve as (value: unknown) => void, reject, timer })
      stdin.write(`${JSON.stringify({ ...command, id })}\n`)
    })
  }

  prompt(message: string, options: { images?: unknown[]; streamingBehavior?: 'steer' | 'followUp' } = {}): Promise<unknown> {
    const command: Record<string, unknown> = { type: 'prompt', message }
    if (options.images && options.images.length > 0) command.images = options.images
    if (options.streamingBehavior) command.streamingBehavior = options.streamingBehavior
    return this.send(command)
  }

  steer(message: string): Promise<unknown> {
    return this.send({ type: 'steer', message })
  }

  followUp(message: string): Promise<unknown> {
    return this.send({ type: 'follow_up', message })
  }

  abort(): Promise<unknown> {
    return this.send({ type: 'abort' })
  }

  getState<T = unknown>(): Promise<T> {
    return this.send<T>({ type: 'get_state' })
  }

  getMessages<T = unknown>(): Promise<T> {
    return this.send<T>({ type: 'get_messages' })
  }

  newSession<T = unknown>(): Promise<T> {
    return this.send<T>({ type: 'new_session' })
  }

  switchSession<T = unknown>(sessionPath: string): Promise<T> {
    return this.send<T>({ type: 'switch_session', sessionPath })
  }

  setSessionName(name: string): Promise<unknown> {
    return this.send({ type: 'set_session_name', name })
  }

  setModel(provider: string, modelId: string): Promise<unknown> {
    return this.send({ type: 'set_model', provider, modelId })
  }

  async stop(): Promise<void> {
    const child = this.child
    if (!child) return
    this.stopped = true
    if (child.exitCode === null && child.signalCode === null) {
      child.stdin?.end()
      if (!(await waitForExit(child, 5_000))) {
        child.kill('SIGTERM')
        if (!(await waitForExit(child, 2_000))) {
          child.kill('SIGKILL')
          await waitForExit(child, 1_000)
        }
      }
    }
    this.child = null
  }

  private consume(chunk: string): void {
    this.buffer += chunk
    let index = this.buffer.indexOf('\n')
    while (index >= 0) {
      const line = this.buffer.slice(0, index)
      this.buffer = this.buffer.slice(index + 1)
      this.handleLine(line.endsWith('\r') ? line.slice(0, -1) : line)
      index = this.buffer.indexOf('\n')
    }
  }

  private handleLine(line: string): void {
    if (line.trim() === '') return
    let record: unknown
    try {
      record = JSON.parse(line)
    } catch {
      this.emitEvent({ type: 'client_error', message: 'pi sent a line that is not valid JSON' })
      return
    }
    if (!isRecord(record)) return
    if (record.type === 'response') {
      this.handleResponse(record)
      return
    }
    this.emitEvent(record as PiRpcEvent)
  }

  private handleResponse(record: Record<string, unknown>): void {
    const id = typeof record.id === 'string' ? record.id : null
    const pending = id ? this.pending.get(id) : undefined
    if (!pending) {
      this.emitEvent({ type: 'client_error', message: 'pi sent a response without a matching request', response: record })
      return
    }
    if (id) this.pending.delete(id)
    clearTimeout(pending.timer)
    if (record.success === true) {
      pending.resolve(record.data)
      return
    }
    const message = typeof record.error === 'string' ? record.error : 'pi command failed'
    pending.reject(new Error(message))
  }

  private handleExit(code: number | null, signal: string | null): void {
    this.exitDetail = { code, signal }
    const wasRunning = this.child !== null
    this.child = null
    const detail = `pi rpc process exited (code ${String(code)}, signal ${String(signal)})`
    for (const [id, pending] of this.pending) {
      this.pending.delete(id)
      clearTimeout(pending.timer)
      pending.reject(new Error(`${detail} before answering ${id}`))
    }
    if (!wasRunning) return
    const error = this.stopped ? null : new Error(detail)
    for (const listener of this.exitListeners) listener(error)
  }

  private handleProcessError(error: Error): void {
    this.emitEvent({ type: 'client_error', message: error.message })
  }

  private emitEvent(event: PiRpcEvent): void {
    for (const listener of this.eventListeners) listener(event)
  }
}

async function waitForExit(child: ChildProcess, timeoutMs: number): Promise<boolean> {
  if (child.exitCode !== null || child.signalCode !== null) return true
  return new Promise<boolean>((resolve) => {
    const timer = setTimeout(() => {
      child.removeListener('exit', onExit)
      resolve(false)
    }, timeoutMs)
    function onExit(): void {
      clearTimeout(timer)
      resolve(true)
    }
    child.once('exit', onExit)
  })
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
