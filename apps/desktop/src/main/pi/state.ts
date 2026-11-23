import type { ModelOption, PiStatePayload } from '../../shared/ipc'

export function piStatePayload(raw: unknown): PiStatePayload {
  const state = isRecord(raw) ? raw : {}
  const model = isRecord(state.model) ? state.model : null
  return {
    model:
      model !== null && typeof model.provider === 'string' && typeof model.id === 'string'
        ? {
            provider: model.provider,
            id: model.id,
            name: typeof model.name === 'string' && model.name !== '' ? model.name : model.id,
          }
        : null,
    thinkingLevel: typeof state.thinkingLevel === 'string' ? state.thinkingLevel : 'off',
    sessionFile: typeof state.sessionFile === 'string' ? state.sessionFile : null,
    sessionName: typeof state.sessionName === 'string' && state.sessionName !== '' ? state.sessionName : null,
  }
}

export function modelOptions(raw: unknown): ModelOption[] {
  const models = isRecord(raw) && Array.isArray(raw.models) ? raw.models : []
  const options: ModelOption[] = []
  for (const entry of models) {
    if (!isRecord(entry) || typeof entry.provider !== 'string' || typeof entry.id !== 'string') continue
    options.push({
      provider: entry.provider,
      id: entry.id,
      name: typeof entry.name === 'string' && entry.name !== '' ? entry.name : entry.id,
      reasoning: entry.reasoning === true,
    })
  }
  return options
}

export function thinkingLevelOptions(raw: unknown): string[] {
  const levels = isRecord(raw) && Array.isArray(raw.levels) ? raw.levels : []
  return levels.filter((level): level is string => typeof level === 'string')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
