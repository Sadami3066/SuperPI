import { describe, expect, it } from 'vitest'
import type { ClientFrame, InboundRouteFrame } from '@superpi/protocol'
import type { PiRpcEvent } from '../src/main/pi/rpc-client'
import {
  RemoteAgentServer,
  mapPiEvent,
  resultText,
  type RemoteBotClient,
  type RemoteBotHost,
} from '../src/main/relay/remote-server'

class FakeClient implements RemoteBotClient {
  readonly listeners = new Set<(event: PiRpcEvent) => void>()
  readonly exitListeners = new Set<(error: Error | null) => void>()
  readonly prompts: Array<{ message: string; behavior?: string }> = []
  readonly steers: string[] = []
  readonly followUps: string[] = []
  aborts = 0
  newSessions = 0
  stopped = false

  constructor(readonly id: string) {}

  onEvent(listener: (event: PiRpcEvent) => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  onExit(listener: (error: Error | null) => void): () => void {
    this.exitListeners.add(listener)
    return () => {
      this.exitListeners.delete(listener)
    }
  }

  emit(event: PiRpcEvent): void {
    for (const listener of this.listeners) listener(event)
  }

  exit(): void {
    for (const listener of this.exitListeners) listener(null)
  }

  async prompt(message: string, options?: { streamingBehavior?: 'steer' | 'followUp' }): Promise<unknown> {
    this.prompts.push({ message, behavior: options?.streamingBehavior })
    return { disposition: 'started' }
  }

  async steer(message: string): Promise<unknown> {
    this.steers.push(message)
    return {}
  }

  async followUp(message: string): Promise<unknown> {
    this.followUps.push(message)
    return {}
  }

  async abort(): Promise<unknown> {
    this.aborts += 1
    return {}
  }

  async newSession(): Promise<unknown> {
    this.newSessions += 1
    return {}
  }

  async getState<T = unknown>(): Promise<T> {
    return { sessionId: this.id, sessionFile: `/tmp/${this.id}.jsonl` } as T
  }

  async stop(): Promise<void> {
    this.stopped = true
  }
}

class FakeHost implements RemoteBotHost {
  readonly created: FakeClient[] = []
  readonly local = new FakeClient('local-session')

  constructor(private readonly bots: string[]) {}

  botIds(): string[] {
    return this.bots
  }

  async createClient(_botId: string): Promise<RemoteBotClient> {
    const client = new FakeClient(`session-${this.created.length + 1}`)
    this.created.push(client)
    return client
  }
}

function setup(
  bots = ['pi', 'work'],
  options: { idleTimeoutMs?: number } = {},
): { frames: ClientFrame[]; host: FakeHost; server: RemoteAgentServer } {
  const frames: ClientFrame[] = []
  const host = new FakeHost(bots)
  const server = new RemoteAgentServer({
    host,
    send: (frame) => frames.push(frame),
    idleTimeoutMs: options.idleTimeoutMs,
  })
  return { frames, host, server }
}

function route(id: string, method: InboundRouteFrame['method'], params: Record<string, unknown>): InboundRouteFrame {
  return { type: 'route', id, to: 'desktop-device', from: 'phone-device', method, params }
}

function events(frames: ClientFrame[]): Array<Extract<ClientFrame, { type: 'route_event' }>> {
  return frames.filter((frame): frame is Extract<ClientFrame, { type: 'route_event' }> => frame.type === 'route_event')
}

async function sleep(ms: number): Promise<void> {
  await new Promise((resolve) => setTimeout(resolve, ms))
}

describe('remote agent server isolation', () => {
  it('rejects an unknown bot before starting a process', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'missing', text: 'hi' }))
    expect(frames).toEqual([{ type: 'route_error', id: 'r1', code: 'bot_not_found', message: expect.stringContaining('missing') }])
    expect(host.created).toHaveLength(0)
    await server.dispose()
  })

  it('rejects requests without a bot or text', async () => {
    const { frames, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { text: 'hi' }))
    await server.handle(route('r2', 'bot.prompt', { botId: 'pi', text: '   ' }))
    expect(frames.map((frame) => frame.type === 'route_error' && frame.code)).toEqual(['bot_not_found', 'bad_request'])
    await server.dispose()
  })

  it('starts a dedicated pi client for the first remote prompt and streams its events', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'run pwd' }))
    expect(host.created).toHaveLength(1)
    const client = host.created[0]
    expect(client?.newSessions).toBe(1)
    expect(frames[0]).toEqual({ type: 'route_ok', id: 'r1', result: { sessionId: 'session-1' } })
    expect(client?.prompts).toEqual([{ message: 'run pwd', behavior: undefined }])

    client?.emit({ type: 'agent_start' })
    client?.emit({ type: 'tool_execution_start', toolCallId: 'call_1', toolName: 'bash', args: { command: 'pwd' } })
    client?.emit({
      type: 'tool_execution_end',
      toolCallId: 'call_1',
      toolName: 'bash',
      result: { content: [{ type: 'text', text: '/home/me' }] },
      isError: false,
    })
    client?.emit({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'hello' } })
    client?.emit({ type: 'agent_end' })
    client?.emit({ type: 'agent_settled' })

    expect(events(frames).map((frame) => frame.event.type)).toEqual([
      'agent_start',
      'tool_call',
      'tool_result',
      'message_update',
      'agent_end',
      'agent_settled',
    ])
    expect(events(frames).map((frame) => [frame.id, frame.seq])).toEqual([
      ['r1', 0],
      ['r1', 1],
      ['r1', 2],
      ['r1', 3],
      ['r1', 4],
      ['r1', 5],
    ])
    expect(events(frames)[2]?.event).toEqual({ type: 'tool_result', callId: 'call_1', result: '/home/me', isError: false })
    await server.dispose()
  })

  it('never touches the local client', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'remote only' }))
    const remote = host.created[0]
    remote?.emit({ type: 'agent_start' })
    remote?.emit({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'remote text' } })
    expect(host.local.prompts).toEqual([])
    expect(host.local.listeners.size).toBe(0)
    expect(host.local.exitListeners.size).toBe(0)
    expect(remote).not.toBe(host.local)
    expect(frames.some((frame) => frame.type === 'route_event')).toBe(true)
    await server.dispose()
  })

  it('reuses the remote client for later turns and separate clients for other bots', async () => {
    const { host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'first' }))
    host.created[0]?.emit({ type: 'agent_settled' })
    await server.handle(route('r2', 'bot.prompt', { botId: 'pi', text: 'second', sessionId: 'session-1' }))
    expect(host.created).toHaveLength(1)
    expect(host.created[0]?.prompts.map((prompt) => prompt.message)).toEqual(['first', 'second'])

    await server.handle(route('r3', 'bot.prompt', { botId: 'work', text: 'other bot' }))
    expect(host.created).toHaveLength(2)
    expect(host.created[1]).not.toBe(host.created[0])
    await server.dispose()
  })

  it('queues a follow-up prompt on the running session and moves the route id', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'first' }))
    const client = host.created[0]
    client?.emit({ type: 'agent_start' })
    await server.handle(route('r2', 'bot.prompt', { botId: 'pi', text: 'second', sessionId: 'session-1' }))
    expect(frames.at(-1)).toEqual({ type: 'route_ok', id: 'r2', result: { sessionId: 'session-1' } })
    expect(client?.prompts[1]).toEqual({ message: 'second', behavior: 'followUp' })
    client?.emit({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'later' } })
    expect(events(frames).at(-1)).toMatchObject({ id: 'r2', seq: 0 })
    await server.dispose()
  })

  it('aborts a session and reports whether it is busy', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'long job' }))
    const client = host.created[0]
    client?.emit({ type: 'agent_start' })
    await server.handle(route('r2', 'bot.state', { sessionId: 'session-1' }))
    await server.handle(route('r3', 'bot.abort', { sessionId: 'session-1' }))
    await server.handle(route('r4', 'bot.state', { sessionId: 'session-1' }))
    expect(frames.find((frame) => frame.type === 'route_ok' && frame.id === 'r2')).toMatchObject({ result: { busy: true } })
    expect(frames.find((frame) => frame.type === 'route_ok' && frame.id === 'r3')).toEqual({
      type: 'route_ok',
      id: 'r3',
      result: { sessionId: 'session-1' },
    })
    expect(client?.aborts).toBe(1)
    expect(frames.find((frame) => frame.type === 'route_ok' && frame.id === 'r4')).toMatchObject({ result: { busy: false } })
    await server.dispose()
  })

  it('steers and queues follow-ups inside the remote session', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'start' }))
    const client = host.created[0]
    await server.handle(route('r2', 'bot.steer', { sessionId: 'session-1', text: 'look left' }))
    await server.handle(route('r3', 'bot.follow_up', { sessionId: 'session-1', text: 'then right' }))
    expect(client?.steers).toEqual(['look left'])
    expect(client?.followUps).toEqual(['then right'])
    expect(frames.filter((frame) => frame.type === 'route_ok')).toHaveLength(3)
    await server.dispose()
  })

  it('rejects actions on a session that is not active', async () => {
    const { frames, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'hi', sessionId: 'gone' }))
    await server.handle(route('r2', 'bot.abort', { sessionId: 'gone' }))
    expect(frames.map((frame) => (frame.type === 'route_error' ? frame.code : null))).toEqual([
      'session_not_found',
      'session_not_found',
    ])
    await server.dispose()
  })

  it('does not leak one bot session events into another route', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'first bot' }))
    await server.handle(route('r2', 'bot.prompt', { botId: 'work', text: 'second bot' }))
    host.created[0]?.emit({ type: 'agent_start' })
    host.created[0]?.emit({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'pi only' } })
    host.created[1]?.emit({ type: 'agent_start' })
    host.created[1]?.emit({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'work only' } })
    expect(events(frames).map((frame) => [frame.id, frame.event.type])).toEqual([
      ['r1', 'agent_start'],
      ['r1', 'message_update'],
      ['r2', 'agent_start'],
      ['r2', 'message_update'],
    ])
    await server.dispose()
  })

  it('replays buffered events after a resume cursor', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'first' }))
    const client = host.created[0]
    client?.emit({ type: 'agent_start' })
    client?.emit({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'one' } })
    client?.emit({ type: 'agent_settled' })
    frames.length = 0
    await server.handle(route('r2', 'bot.resume', { sessionId: 'session-1', afterSeq: 0 }))
    expect(frames[0]).toEqual({ type: 'route_ok', id: 'r2', result: { sessionId: 'session-1', busy: false } })
    const replayed = events(frames)
    expect(replayed.map((frame) => frame.seq)).toEqual([1, 2])
    expect(replayed.every((frame) => frame.id === 'r2')).toBe(true)
    await server.dispose()
  })
})

describe('remote client lifecycle', () => {
  it('idle-closes the remote client and lets the next prompt start fresh', async () => {
    const { host, server } = setup(['pi'], { idleTimeoutMs: 20 })
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'hello' }))
    const client = host.created[0]
    client?.emit({ type: 'agent_settled' })
    await sleep(80)
    expect(client?.stopped).toBe(true)

    await server.handle(route('r2', 'bot.prompt', { botId: 'pi', text: 'hello again' }))
    expect(host.created).toHaveLength(2)
    expect(host.created[1]?.newSessions).toBe(1)
    await server.dispose()
  })

  it('closes every remote client on dispose', async () => {
    const { host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'first' }))
    await server.handle(route('r2', 'bot.prompt', { botId: 'work', text: 'second' }))
    await server.dispose()
    expect(host.created.map((client) => client.stopped)).toEqual([true, true])
    expect(host.local.stopped).toBe(false)
  })

  it('drops the session when the remote pi process exits', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'hello' }))
    host.created[0]?.exit()
    await server.handle(route('r2', 'bot.prompt', { botId: 'pi', text: 'again', sessionId: 'session-1' }))
    expect(frames.at(-1)).toMatchObject({ type: 'route_error', id: 'r2', code: 'session_not_found' })
    await server.handle(route('r3', 'bot.prompt', { botId: 'pi', text: 'fresh' }))
    expect(host.created).toHaveLength(2)
    await server.dispose()
  })
})

describe('pi event mapping', () => {
  it('maps thinking deltas to reasoning deltas', () => {
    expect(mapPiEvent({ type: 'message_update', assistantMessageEvent: { type: 'thinking_delta', delta: 'hmm' } })).toEqual([
      { type: 'message_update', assistantMessageEvent: { type: 'reasoning_delta', delta: 'hmm' } },
    ])
  })

  it('drops events the relay has no shape for', () => {
    expect(mapPiEvent({ type: 'turn_start' })).toEqual([])
    expect(mapPiEvent({ type: 'message_update', assistantMessageEvent: { type: 'toolcall_delta', delta: '{' } })).toEqual([])
    expect(mapPiEvent({ type: 'tool_execution_start', toolCallId: 'call_1' })).toEqual([])
  })

  it('reads text out of tool execution results', () => {
    expect(resultText({ content: [{ type: 'text', text: 'a' }, { type: 'text', text: 'b' }] })).toBe('a\nb')
    expect(resultText('plain')).toBe('plain')
    expect(resultText({ details: {} })).toBe('')
  })
})
