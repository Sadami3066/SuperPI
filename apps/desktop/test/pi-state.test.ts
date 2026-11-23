import { describe, expect, it } from 'vitest'
import { modelOptions, piStatePayload, thinkingLevelOptions } from '../src/main/pi/state'

describe('pi state mapping', () => {
  it('maps the session state into the surface the window uses', () => {
    expect(
      piStatePayload({
        model: { provider: 'opencode-go', id: 'deepseek-v4.1-flash', name: 'DeepSeek V4.1 Flash 1017' },
        thinkingLevel: 'max',
        sessionFile: '/home/me/.pi/agent/sessions/--work--/a.jsonl',
        sessionName: 'Release prep',
        messageCount: 3,
      }),
    ).toEqual({
      model: { provider: 'opencode-go', id: 'deepseek-v4.1-flash', name: 'DeepSeek V4.1 Flash 1017' },
      thinkingLevel: 'max',
      sessionFile: '/home/me/.pi/agent/sessions/--work--/a.jsonl',
      sessionName: 'Release prep',
    })
  })

  it('keeps a usable state when fields are missing', () => {
    expect(piStatePayload(undefined)).toEqual({
      model: null,
      thinkingLevel: 'off',
      sessionFile: null,
      sessionName: null,
    })
    expect(piStatePayload({ model: { provider: 'p', id: 'm' }, sessionName: '' }).model).toEqual({
      provider: 'p',
      id: 'm',
      name: 'm',
    })
  })

  it('turns the available models into picker entries', () => {
    const options = modelOptions({
      models: [
        { provider: 'opencode-go', id: 'a', name: 'Model A', reasoning: true, contextWindow: 1000 },
        { provider: 'opencode-go', id: 'b' },
        { provider: 'nope' },
        null,
      ],
    })
    expect(options).toEqual([
      { provider: 'opencode-go', id: 'a', name: 'Model A', reasoning: true },
      { provider: 'opencode-go', id: 'b', name: 'b', reasoning: false },
    ])
    expect(modelOptions({})).toEqual([])
  })

  it('keeps only string thinking levels', () => {
    expect(thinkingLevelOptions({ levels: ['low', 3, 'max'] })).toEqual(['low', 'max'])
    expect(thinkingLevelOptions(null)).toEqual([])
  })
})
