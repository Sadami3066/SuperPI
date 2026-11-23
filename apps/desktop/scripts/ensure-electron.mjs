#!/usr/bin/env node
import { execFileSync } from 'node:child_process'
import { createRequire } from 'node:module'
import { chmodSync, existsSync, mkdirSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { homedir } from 'node:os'
import path from 'node:path'

const require = createRequire(import.meta.url)
const electronDir = path.dirname(require.resolve('electron/package.json'))
const version = JSON.parse(execFileSync('cat', [path.join(electronDir, 'package.json')], { encoding: 'utf8' })).version
const platform = process.platform
const arch = process.arch

if (platform !== 'linux') {
  process.exit(0)
}

const binary = path.join(electronDir, 'dist', 'electron')
const marker = path.join(electronDir, 'path.txt')
if (existsSync(binary) && statSync(binary).size > 1_000_000 && existsSync(marker)) {
  process.exit(0)
}

const archiveName = `electron-v${version}-linux-${arch === 'x64' ? 'x64' : arch}.zip`
const cacheRoot = process.env.electron_config_cache ?? path.join(homedir(), '.cache', 'electron')
const archive = findArchive(cacheRoot, archiveName)
if (!archive) {
  console.error(`ensure-electron: no cached ${archiveName}; run pnpm install first`)
  process.exit(1)
}

const distDir = path.join(electronDir, 'dist')
rmSync(distDir, { recursive: true, force: true })
mkdirSync(distDir, { recursive: true })
execFileSync('unzip', ['-q', archive, '-d', distDir], { stdio: 'inherit' })
chmodSync(path.join(distDir, 'electron'), 0o755)
writeFileSync(marker, 'electron')
console.log(`ensure-electron: extracted ${archiveName}`)

function findArchive(root, name) {
  if (!existsSync(root)) return null
  for (const entry of readdirSync(root)) {
    const candidate = path.join(root, entry, name)
    if (existsSync(candidate)) return candidate
    const nested = findArchive(path.join(root, entry), name)
    if (nested) return nested
  }
  return null
}
