import { createHash, randomBytes, timingSafeEqual as cryptoTimingSafeEqual, X509Certificate } from 'node:crypto'
import { readFileSync } from 'node:fs'

const PAIR_CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789'

export function sha256(value: string): string {
  return createHash('sha256').update(value).digest('hex')
}

export function randomToken(bytes = 32): string {
  return randomBytes(bytes).toString('base64url')
}

export function newAccountId(): string {
  return `acct-${randomBytes(8).toString('hex')}`
}

export function newDeviceId(): string {
  return `dev-${randomBytes(8).toString('hex')}`
}

export function newPairCode(): string {
  const bytes = randomBytes(8)
  let code = ''
  for (const byte of bytes) {
    code += PAIR_CODE_ALPHABET[byte % PAIR_CODE_ALPHABET.length]
  }
  return code
}

export function hashSecret(secret: string): string {
  return sha256(`secret:${secret}`)
}

export function verifySecret(secret: string, hash: string): boolean {
  const candidate = hashSecret(secret)
  return candidate.length === hash.length && timingSafeEqual(candidate, hash)
}

export function readFingerprint(certPath: string | null): string | null {
  if (!certPath) return null
  try {
    const certificate = new X509Certificate(readFileSync(certPath))
    return certificate.fingerprint256.replaceAll(':', '').toLowerCase()
  } catch {
    return null
  }
}

function timingSafeEqual(left: string, right: string): boolean {
  const leftBuf = Buffer.from(left, 'utf8')
  const rightBuf = Buffer.from(right, 'utf8')
  if (leftBuf.length !== rightBuf.length) return false
  return cryptoTimingSafeEqual(leftBuf, rightBuf)
}
