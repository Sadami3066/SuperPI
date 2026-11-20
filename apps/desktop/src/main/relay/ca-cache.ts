import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs'
import path from 'node:path'

export function relayCaPath(homeDir: string): string {
  return path.join(homeDir, 'cache', 'relay-ca.pem')
}

export function materializeRelayCa(homeDir: string, caPem: string): string {
  const pem = `${caPem.trim()}\n`
  if (pem.trim() === '') throw new Error('the relay CA is empty')
  const file = relayCaPath(homeDir)
  mkdirSync(path.dirname(file), { recursive: true })
  if (readIfPresent(file) !== pem) {
    const temporary = `${file}.tmp`
    writeFileSync(temporary, pem, { encoding: 'utf8', mode: 0o600 })
    renameSync(temporary, file)
  }
  chmodSync(file, 0o600)
  return file
}

export function removeRelayCa(homeDir: string): void {
  try {
    rmSync(relayCaPath(homeDir), { force: true })
  } catch (failure) {
    console.error(`relay CA was not removed: ${failure instanceof Error ? failure.message : String(failure)}`)
  }
}

function readIfPresent(file: string): string | null {
  if (!existsSync(file)) return null
  try {
    return readFileSync(file, 'utf8')
  } catch {
    return null
  }
}
