import { afterEach, describe, expect, it } from 'vitest'
import { cleanupHarness, createCerts, createService, hasRemoteRepo, startRelay, waitFor } from './helpers/relay-harness'

afterEach(async () => {
  await cleanupHarness()
})

describe.skipIf(!hasRemoteRepo)('desktop relay client against a live relay', () => {
  it('pairs with the login secret and reports the device online', async () => {
    const relay = await startRelay()
    const service = createService()
    try {
      await service.pair({
        link: `http://127.0.0.1:${relay.port}`,
        loginSecret: relay.secret,
        deviceName: 'Integration Desktop',
      })
      await waitFor(() => service.status().state === 'online', 8_000)
      const status = service.status()
      expect(status.state).toBe('online')
      expect(status.deviceName).toBe('Integration Desktop')
      expect(status.origin).toBe(`http://127.0.0.1:${relay.port}`)
      expect(status.devices.some((device) => device.deviceId === status.deviceId)).toBe(true)
    } finally {
      service.dispose()
    }
  }, 20_000)

  it('pins the certificate authority when pairing over https', async () => {
    const certs = createCerts()
    const relay = await startRelay({
      SUPERPI_CERT: certs.cert,
      SUPERPI_KEY: certs.key,
      SUPERPI_CA: certs.ca,
    })
    const service = createService()
    try {
      await service.pair({
        link: `https://127.0.0.1:${relay.port}#fp=${certs.fingerprint}`,
        loginSecret: relay.secret,
        deviceName: 'TLS Desktop',
      })
      await waitFor(() => service.status().state === 'online', 8_000)
      expect(service.status().origin).toBe(`https://127.0.0.1:${relay.port}`)
    } finally {
      service.dispose()
    }
  }, 30_000)

  it('refuses a pairing link whose fingerprint does not match', async () => {
    const certs = createCerts()
    const relay = await startRelay({
      SUPERPI_CERT: certs.cert,
      SUPERPI_KEY: certs.key,
      SUPERPI_CA: certs.ca,
    })
    const service = createService()
    try {
      await expect(
        service.pair({
          link: `https://127.0.0.1:${relay.port}#fp=${'0'.repeat(64)}`,
          loginSecret: relay.secret,
          deviceName: 'TLS Desktop',
        }),
      ).rejects.toThrow(/does not match the pairing link/)
    } finally {
      service.dispose()
    }
  }, 30_000)

  it('rejects a wrong login secret', async () => {
    const relay = await startRelay()
    const service = createService()
    try {
      await expect(
        service.pair({ link: `http://127.0.0.1:${relay.port}`, loginSecret: 'wrong-secret', deviceName: 'Nope' }),
      ).rejects.toThrow(/Pairing failed \(401\)/)
      expect(service.status().state).toBe('disconnected')
    } finally {
      service.dispose()
    }
  }, 20_000)
})
