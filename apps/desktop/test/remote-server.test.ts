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
  readonly prompts: Array<{ message: string; behavior?: string }> = []
  readonly steers: string[] = []
  readonly followUps: string[] = []
  aborts = 0
  newSessions = 0

  constructor(readonly id: string) {}

  onEvent(listener: (event: PiRpcEvent) => void): () => void {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  emit(event: PiRpcEvent): void {
    for (const listener of this.listeners) listener(event)
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
}

class FakeHost implements RemoteBotHost {
  readonly created: FakeClient[] = []
  private readonly byBot = new Map<string, FakeClient>()

  constructor(private readonly bots: string[]) {}

  botIds(): string[] {
    return this.bots
  }

  async client(botId: string): Promise<RemoteBotClient> {
    if (!this.bots.includes(botId)) throw new Error(`unknown bot: ${botId}`)
    const existing = this.byBot.get(botId)
    if (existing) return existing
    const client = new FakeClient(`session-${this.created.length + 1}`)
    this.created.push(client)
    this.byBot.set(botId, client)
    return client
  }
}

function setup(bots = ['pi', 'work']): { frames: ClientFrame[]; host: FakeHost; server: RemoteAgentServer } {
  const frames: ClientFrame[] = []
  const host = new FakeHost(bots)
  const server = new RemoteAgentServer({ host, send: (frame) => frames.push(frame) })
  return { frames, host, server }
}

function route(id: string, method: InboundRouteFrame['method'], params: Record<string, unknown>): InboundRouteFrame {
  return { type: 'route', id, to: 'desktop-device', from: 'phone-device', method, params }
}

function events(frames: ClientFrame[]): Array<Extract<ClientFrame, { type: 'route_event' }>> {
  return frames.filter((frame): frame is Extract<ClientFrame, { type: 'route_event' }> => frame.type === 'route_event')
}

describe('remote agent server', () => {
  it('rejects an unknown bot before starting any process', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'missing', text: 'hi' }))
    expect(frames).toEqual([{ type: 'route_error', id: 'r1', code: 'bot_not_found', message: expect.stringContaining('missing') }])
    expect(host.created).toHaveLength(0)
    server.dispose()
  })

  it('rejects requests without a bot or text', async () => {
    const { frames, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { text: 'hi' }))
    await server.handle(route('r2', 'bot.prompt', { botId: 'pi', text: '   ' }))
    expect(frames.map((frame) => frame.type === 'route_error' && frame.code)).toEqual(['bot_not_found', 'bad_request'])
    server.dispose()
  })

  it('starts a session and streams pi events under the prompt route id', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'run pwd' }))
    const client = host.created[0]
    expect(client?.newSessions).toBe(1)
    expect(frames[0]).toEqual({ type: 'route_ok', id: 'r1', result: { sessionId: 'session-1' } })
    expect(client?.prompts).toEqual([{ message: 'run pwd', behavior: undefined }])

    client?.emit({ type: 'agent_start' })
    client?.emit({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'hello' } })
    client?.emit({ type: 'tool_execution_start', toolCallId: 'call_1', toolName: 'bash', args: { command: 'pwd' } })
    client?.emit({
      type: 'tool_execution_end',
      toolCallId: 'call_1',
      toolName: 'bash',
      result: { content: [{ type: 'text', text: '/home/me' }] },
      isError: false,
    })
    client?.emit({ type: 'agent_end' })
    client?.emit({ type: 'agent_settled' })

    const streamed = events(frames)
    expect(streamed.map((frame) => frame.event.type)).toEqual([
      'agent_start',
      'message_update',
      'tool_call',
      'tool_result',
      'agent_end',
      'agent_settled',
    ])
    expect(streamed.map((frame) => frame.id)).toEqual(['r1', 'r1', 'r1', 'r1', 'r1', 'r1'])
    expect(streamed.map((frame) => frame.seq)).toEqual([0, 1, 2, 3, 4, 5])
    expect(streamed[1]?.event).toEqual({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'hello' } })
    expect(streamed[2]?.event).toEqual({ type: 'tool_call', callId: 'call_1', name: 'bash', args: { command: 'pwd' } })
    expect(streamed[3]?.event).toEqual({ type: 'tool_result', callId: 'call_1', result: '/home/me', isError: false })
    server.dispose()
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
    const streamed = events(frames)
    expect(streamed.at(-1)).toMatchObject({ id: 'r2', seq: 0 })
    server.dispose()
  })

  it('aborts a session and reports whether it is busy', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'long job' }))
    const client = host.created[0]
    client?.emit({ type: 'agent_start' })
    await server.handle(route('r2', 'bot.state', { sessionId: 'session-1' }))
    await server.handle(route('r3', 'bot.abort', { sessionId: 'session-1' }))
    await server.handle(route('r4', 'bot.state', { sessionId: 'session-1' }))
    expect(frames.find((frame) => frame.type === 'route_ok' && frame.id === 'r2')).toEqual({
      type: 'route_ok',
      id: 'r2',
      result: { sessionId: 'session-1', busy: true },
    })
    expect(frames.find((frame) => frame.type === 'route_ok' && frame.id === 'r3')).toEqual({
      type: 'route_ok',
      id: 'r3',
      result: { sessionId: 'session-1' },
    })
    expect(client?.aborts).toBe(1)
    expect(frames.find((frame) => frame.type === 'route_ok' && frame.id === 'r4')).toEqual({
      type: 'route_ok',
      id: 'r4',
      result: { sessionId: 'session-1', busy: false },
    })
    server.dispose()
  })

  it('steers and queues follow-ups inside the session', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'start' }))
    const client = host.created[0]
    await server.handle(route('r2', 'bot.steer', { sessionId: 'session-1', text: 'look left' }))
    await server.handle(route('r3', 'bot.follow_up', { sessionId: 'session-1', text: 'then right' }))
    expect(client?.steers).toEqual(['look left'])
    expect(client?.followUps).toEqual(['then right'])
    expect(frames.filter((frame) => frame.type === 'route_ok')).toHaveLength(3)
    server.dispose()
  })

  it('rejects actions on a session that is not active', async () => {
    const { frames, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'hi', sessionId: 'gone' }))
    await server.handle(route('r2', 'bot.abort', { sessionId: 'gone' }))
    expect(frames.map((frame) => (frame.type === 'route_error' ? frame.code : null))).toEqual([
      'session_not_found',
      'session_not_found',
    ])
    server.dispose()
  })

  it('does not leak one bot session events into another route', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'first bot' }))
    await server.handle(route('r2', 'bot.prompt', { botId: 'work', text: 'second bot' }))
    host.created[0]?.emit({ type: 'agent_start' })
    host.created[0]?.emit({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'pi only' } })
    host.created[1]?.emit({ type: 'agent_start' })
    host.created[1]?.emit({ type: 'message_update', assistantMessageEvent: { type: 'text_delta', delta: 'work only' } })
    const streamed = events(frames)
    expect(streamed.map((frame) => [frame.id, frame.event.type])).toEqual([
      ['r1', 'agent_start'],
      ['r1', 'message_update'],
      ['r2', 'agent_start'],
      ['r2', 'message_update'],
    ])
    server.dispose()
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
    server.dispose()
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

describe('remote session lifecycle', () => {
  it('replaces an idle remote session when a prompt starts a new one', async () => {
    const { host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'first' }))
    const client = host.created[0]
    client?.emit({ type: 'agent_settled' })
    await server.handle(route('r2', 'bot.prompt', { botId: 'pi', text: 'fresh' }))
    expect(host.created).toHaveLength(1)
    expect(client?.newSessions).toBe(2)
    client?.emit({ type: 'agent_start' })
    const frames: ClientFrame[] = []
    server.dispose()
    expect(frames).toEqual([])
  })

  it('refuses a new session while the bot is busy with another one', async () => {
    const { frames, host, server } = setup()
    await server.handle(route('r1', 'bot.prompt', { botId: 'pi', text: 'first' }))
    host.created[0]?.emit({ type: 'agent_start' })
    await server.handle(route('r2', 'bot.prompt', { botId: 'pi', text: 'second chat' }))
    expect(frames.at(-1)).toMatchObject({ type: 'route_error', id: 'r2', code: 'bad_request' })
    server.dispose()
  })
})
