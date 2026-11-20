import { afterEach, describe, expect, it } from 'vitest'
import { MemoryClient } from '../src/main/relay/memory-client'
import {
  cleanupHarness,
  createService,
  enrollDevice,
  hasRemoteRepo,
  startRelay,
  tempDir,
  waitFor,
  writeMemory,
} from './helpers/relay-harness'

afterEach(async () => {
  await cleanupHarness()
})

describe.skipIf(!hasRemoteRepo)('shared memory sync', () => {
  it('mirrors a relay snapshot into the local cache', async () => {
    const relay = await startRelay()
    const device = await enrollDevice(relay.port, relay.secret, 'Desktop', 'desktop')
    await writeMemory(relay.port, device.token, {
      op: 'append',
      path: 'profile.md',
      text: 'Prefers short answers',
      idempotencyKey: 'm1',
    })

    const client = new MemoryClient(tempDir('superpi-memory-cache-'))
    const summary = await client.sync({
      origin: `http://127.0.0.1:${relay.port}`,
      caPem: null,
      deviceId: device.deviceId,
      token: device.token,
      deviceName: 'Desktop',
      accountId: device.accountId,
      enrolledAt: Date.now(),
    })
    expect(summary.version).toBe(1)
    expect(summary.files).toContain('profile.md')
    expect(client.readFile('profile.md')).toContain('Prefers short answers')
    expect(client.cached()?.version).toBe(1)
  }, 20_000)

  it('refreshes the cache when another device writes memory', async () => {
    const relay = await startRelay()
    const summaries: Array<{ version: number } | null> = []
    const service = createService({ onMemory: (summary) => summaries.push(summary) })
    try {
      await service.pair({ link: `http://127.0.0.1:${relay.port}`, loginSecret: relay.secret, deviceName: 'Desktop' })
      await waitFor(() => service.status().state === 'online', 8_000)
      const phone = await enrollDevice(relay.port, relay.secret, 'Phone', 'android')

      await writeMemory(relay.port, phone.token, {
        op: 'append',
        path: 'context.md',
        text: 'Working on the desktop client',
        idempotencyKey: 'p1',
      })
      await waitFor(() => service.memoryStatus()?.version === 1, 8_000)
      expect(service.memoryStatus()?.files).toContain('context.md')

      await writeMemory(relay.port, phone.token, {
        op: 'append',
        path: 'context.md',
        text: 'Also on memory sync',
        idempotencyKey: 'p2',
      })
      await waitFor(() => service.memoryStatus()?.version === 2, 8_000)
      expect(summaries.some((summary) => summary?.version === 2)).toBe(true)
    } finally {
      await service.dispose()
    }
  }, 25_000)
})
