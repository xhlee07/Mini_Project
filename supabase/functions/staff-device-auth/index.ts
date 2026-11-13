// No fingerprint images, passwords, private keys, or reusable login tokens are stored here.
const env = (name: string) => Deno.env.get(name) ?? '';
function serverKey(name: string, fallback: string): string {
  try { return JSON.parse(env(name)).default || env(fallback); } catch { return env(fallback); }
}
const base = env('SUPABASE_URL');
const admin = serverKey('SUPABASE_SECRET_KEYS', 'SUPABASE_SERVICE_ROLE_KEY');
const anon = serverKey('SUPABASE_PUBLISHABLE_KEYS', 'SUPABASE_ANON_KEY');
const json = (data: unknown, status = 200) => Response.json(data, {
  status, headers: { 'Cache-Control': 'no-store' },
});
class Rejection extends Error {
  constructor(message: string, readonly status = 400, readonly code = 'device_request_failed') { super(message); }
}
async function request(path: string, body: unknown, key: string, token = '') {
  const response = await fetch(base + path, {
    method: 'POST',
    headers: { apikey: key, 'Content-Type': 'application/json',
      ...(token ? { Authorization: 'Bearer ' + token } : key.startsWith('eyJ') ? { Authorization: 'Bearer ' + key } : {}) },
    body: JSON.stringify(body), signal: AbortSignal.timeout(15000),
  });
  const data = await response.json();
  if (!response.ok) throw new Rejection(data.message ?? data.msg ?? 'Unable to verify this device.', 400);
  return data;
}
function bytes(value: string) {
  if (typeof value !== 'string' || value.length > 1024) throw new Rejection('Invalid device proof.');
  return Uint8Array.from(atob(value), c => c.charCodeAt(0));
}
// Android Signature uses ASN.1 DER; WebCrypto ECDSA expects fixed-width r || s.
export function rawSignature(der: Uint8Array): ArrayBuffer {
  if (der.length < 8 || der.length > 72 || der[0] !== 0x30 || der[1] !== der.length - 2) throw new Rejection('Invalid device proof.');
  const result = new Uint8Array(64);
  let offset = 2;
  for (let n = 0; n < 2; n++) {
    if (der[offset++] !== 2) throw new Rejection('Invalid device proof.');
    const length = der[offset++];
    let integer = der.slice(offset, offset + length);
    if (integer.length !== length || length < 1 || length > 33 || (integer[0] & 0x80)) throw new Rejection('Invalid device proof.');
    if (integer.length === 33 && integer[0] === 0) integer = integer.slice(1);
    if (integer.length > 32) throw new Rejection('Invalid device proof.');
    result.set(integer, n * 32 + 32 - integer.length);
    offset += length;
  }
  if (offset !== der.length) throw new Rejection('Invalid device proof.');
  return result.buffer;
}
async function verify(publicKey: string, signature: string, message: string) {
  try {
    const key = await crypto.subtle.importKey('spki', bytes(publicKey), { name: 'ECDSA', namedCurve: 'P-256' }, false, ['verify']);
    return await crypto.subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, key, rawSignature(bytes(signature)), new TextEncoder().encode(message));
  } catch { return false; }
}
const uuid = (value: unknown): value is string => typeof value === 'string' && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value);
export async function handler(req: Request): Promise<Response> {
  try {
    if (req.method !== 'POST') return json({ message: 'POST required.' }, 405);
    if (Number(req.headers.get('content-length')) > 8192) throw new Rejection('Request too large.');
    const raw = await req.text();
    if (raw.length > 8192) throw new Rejection('Request too large.');
    const body = JSON.parse(raw);
    if (!['list', 'register', 'remove', 'challenge', 'login'].includes(body.operation)) throw new Rejection('Unsupported device operation.');
    const device = body.device_id;
    if (body.operation !== 'list' && !uuid(device)) throw new Rejection('Invalid device registration.');
    if (['list', 'register', 'remove'].includes(body.operation)) {
      const token = req.headers.get('Authorization')?.replace(/^Bearer\s+/i, '') ?? '';
      if (!token || !token.startsWith('eyJ')) throw new Rejection('Please sign in first.', 401);
      // Validate the real user JWT, never trust a client-supplied owner or role.
      const userResponse = await fetch(base + '/auth/v1/user', { headers: { apikey: anon, Authorization: 'Bearer ' + token }, signal: AbortSignal.timeout(15000) });
      if (!userResponse.ok) throw new Rejection('Please sign in again.', 401);
      const user = await userResponse.json();
      if (body.operation === 'register' && !(await verify(body.public_key, body.signature, `ctfly-enroll:${user.id}:${device}`))) throw new Rejection('Fingerprint verification failed.', 403);
      return json(await request('/rest/v1/rpc/ctfly_staff_devices', {
        p_operation: body.operation,
        p_data: { device_id: device, staff_name: body.staff_name, device_name: body.device_name, public_key: body.public_key },
      }, anon, token));
    }
    const auth = (operation: string) => request('/rest/v1/rpc/ctfly_staff_device_auth', { p_operation: operation, p_device: device, p_challenge: body.challenge_id ?? null }, admin);
    if (body.operation === 'challenge') return json(await auth('challenge'));
    if (!uuid(body.challenge_id)) throw new Rejection('Invalid fingerprint request.');
    let proof;
    try { proof = await auth('consume'); }
    catch { throw new Rejection('Device registration or fingerprint request is unavailable. Sign in with your password to check this phone.', 403, 'device_unavailable'); }
    if (!(await verify(proof.public_key, body.signature, `ctfly-login:${device}:${body.challenge_id}:${proof.nonce}`))) throw new Rejection('Fingerprint verification failed.', 403);
    // Generate and consume an internal one-time Auth token only after device proof succeeds.
    // generate_link does not send email. The one-time token is never returned to the app.
    const link = await request('/auth/v1/admin/generate_link', { type: 'magiclink', email: proof.email }, admin);
    if (link.id !== proof.owner || !link.hashed_token) throw new Rejection('Staff account is unavailable.', 403);
    const session = await request('/auth/v1/verify', { token_hash: link.hashed_token, type: 'email' }, anon);
    if (session.user?.id !== proof.owner || !session.access_token || !session.refresh_token) throw new Rejection('Staff account is unavailable.', 403);
    // Recheck removal/role changes that occurred while Auth issued the session.
    try { await auth('check'); }
    catch {
      await fetch(base + '/auth/v1/logout?scope=local', { method: 'POST', headers: { apikey: anon, Authorization: 'Bearer ' + session.access_token } });
      throw new Rejection('Device registration is unavailable.', 403, 'device_unavailable');
    }
    return json(session);
  } catch (error) {
    if (error instanceof Rejection) return json({ message: error.message, code: error.code }, error.status);
    return json({ message: 'Unable to verify this device. Please try again.' }, 400);
  }
}
Deno.serve(handler);
