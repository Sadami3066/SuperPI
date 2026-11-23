import { describe, expect, it } from 'vitest'
import { parsePairingLink, websocketUrl, normalizeFingerprint } from '../src/main/relay/pairing'

describe('pairing links', () => {
  it('parses a full pairing link', () => {
    const parsed = parsePairingLink('https://203.0.113.10:8443/pair#fp=AB:CD:EF&code=abcd2345')
    expect(parsed).toEqual({
      origin: 'https://203.0.113.10:8443',
      fingerprint: 'abcdef',
      pairCode: 'ABCD2345',
    })
  })

  it('accepts a bare origin', () => {
    expect(parsePairingLink('relay.example.com:8443')).toEqual({
      origin: 'https://relay.example.com:8443',
      fingerprint: null,
      pairCode: null,
    })
  })

  it('keeps http origins for plain development relays', () => {
    const parsed = parsePairingLink('http://127.0.0.1:8443')
    expect(parsed?.origin).toBe('http://127.0.0.1:8443')
    expect(parsed?.fingerprint).toBeNull()
  })

  it('reads codes from the query string too', () => {
    const parsed = parsePairingLink('https://relay.test/pair?code=ZZZZ9999&fp=deadbeef')
    expect(parsed?.pairCode).toBe('ZZZZ9999')
    expect(parsed?.fingerprint).toBe('deadbeef')
  })

  it('rejects garbage', () => {
    expect(parsePairingLink('')).toBeNull()
    expect(parsePairingLink('ftp://relay.test')).toBeNull()
    expect(parsePairingLink('   ')).toBeNull()
  })
})

describe('fingerprints and urls', () => {
  it('normalizes fingerprints', () => {
    expect(normalizeFingerprint('AA:BB:CC')).toBe('aabbcc')
    expect(normalizeFingerprint('  ')).toBeNull()
    expect(normalizeFingerprint(null)).toBeNull()
  })

  it('derives the websocket url from an origin', () => {
    expect(websocketUrl('https://relay.test:8443')).toBe('wss://relay.test:8443/v1/ws')
    expect(websocketUrl('http://127.0.0.1:9000')).toBe('ws://127.0.0.1:9000/v1/ws')
  })
})
