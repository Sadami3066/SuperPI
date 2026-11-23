export interface PairingInput {
  origin: string
  fingerprint: string | null
  pairCode: string | null
}

export function parsePairingLink(raw: string): PairingInput | null {
  const text = raw.trim()
  if (text === '') return null
  const withScheme = /^[a-z][a-z0-9+.-]*:\/\//i.test(text) ? text : `https://${text}`
  let url: URL
  try {
    url = new URL(withScheme)
  } catch {
    return null
  }
  if (url.protocol !== 'https:' && url.protocol !== 'http:') return null
  const hashParams = new URLSearchParams(url.hash.startsWith('#') ? url.hash.slice(1) : url.hash)
  const fingerprint = normalizeFingerprint(hashParams.get('fp') ?? url.searchParams.get('fp'))
  const pairCode = hashParams.get('code') ?? url.searchParams.get('code')
  return {
    origin: url.origin,
    fingerprint,
    pairCode: pairCode ? pairCode.trim().toUpperCase() : null,
  }
}

export function normalizeFingerprint(value: string | null | undefined): string | null {
  if (!value) return null
  const normalized = value.replaceAll(':', '').trim().toLowerCase()
  return normalized === '' ? null : normalized
}

export function websocketUrl(origin: string): string {
  const url = new URL('/v1/ws', origin)
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
  return url.toString()
}
