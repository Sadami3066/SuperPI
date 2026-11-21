export const MEMORY_WRITE_OPS = ['append', 'replace_section', 'overwrite'] as const

export type MemoryWriteOp = (typeof MEMORY_WRITE_OPS)[number]

export interface MemoryWriteRequest {
  op: MemoryWriteOp
  path: string
  section?: string
  text: string
  baseVersion?: number
  idempotencyKey: string
}

export interface MemoryWriteResponse {
  version: number
}

export interface MemorySnapshotResponse {
  version: number
  files: Record<string, string>
  versions: Record<string, number>
}

export interface MemoryFileResponse {
  version: number
  fileVersion: number
  path: string
  content: string
}

export const MEMORY_PATHS = ['profile.md', 'context.md', 'cookbook.md', 'summaries.log'] as const

export type MemoryPath = (typeof MEMORY_PATHS)[number] | `daily/${string}.md`

const DAILY_PATH_PATTERN = /^daily\/(?:[A-Za-z0-9_-]+\/){0,5}[A-Za-z0-9_-]+\.md$/
const MAX_DAILY_PATH_LENGTH = 128

export function isMemoryPath(path: string): path is MemoryPath {
  if (path.startsWith('daily/')) {
    return path.length <= MAX_DAILY_PATH_LENGTH && DAILY_PATH_PATTERN.test(path)
  }
  return (MEMORY_PATHS as readonly string[]).includes(path)
}
