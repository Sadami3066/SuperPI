export const APP_CSP_HEADER =
  "default-src 'self' superpi:; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob: http://127.0.0.1:* http://localhost:*; font-src 'self' data:; connect-src 'self' http://127.0.0.1:* http://localhost:* ws://127.0.0.1:* ws://localhost:*; frame-src 'self' blob: data:; media-src 'self' blob: data: http://127.0.0.1:* http://localhost:*; worker-src 'self' blob:;";

export function isAllowedAppNavigation(targetUrl: string, allowedOrigin: string): boolean {
  try {
    const target = new URL(targetUrl);
    const allowed = new URL(allowedOrigin);
    return (
      target.protocol === allowed.protocol &&
      target.hostname === allowed.hostname &&
      target.port === allowed.port
    );
  } catch {
    return false;
  }
}
