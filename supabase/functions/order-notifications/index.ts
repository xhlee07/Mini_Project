// Server-only FCM worker. Customers never receive service credentials or other users' device tokens.
const env = (key: string) => Deno.env.get(key) ?? '';
function serverKey(): string {
  try { return JSON.parse(env('SUPABASE_SECRET_KEYS')).default || env('SUPABASE_SERVICE_ROLE_KEY'); }
  catch { return env('SUPABASE_SERVICE_ROLE_KEY'); }
}
const base64url = (value: Uint8Array) => btoa(String.fromCharCode(...value)).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '');
const encode = (value: unknown) => base64url(new TextEncoder().encode(JSON.stringify(value)));
type Credentials = { project_id: string; client_email: string; private_key: string };
type Job = { id: string; notice_id: string; order_id: string; user_id: string; token: string; language: string };
let cachedAuth: { token: string; until: number; email: string } | undefined;

async function accessToken(credentials: Credentials): Promise<string> {
  if (cachedAuth && cachedAuth.until > Date.now() && cachedAuth.email === credentials.client_email) return cachedAuth.token;
  const now = Math.floor(Date.now() / 1000);
  const unsigned = encode({ alg: 'RS256', typ: 'JWT' }) + '.' + encode({ iss: credentials.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging', aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600 });
  const pem = credentials.private_key.replace(/-----[A-Z ]+-----|\s/g, '');
  const key = await crypto.subtle.importKey('pkcs8', Uint8Array.from(atob(pem), c => c.charCodeAt(0)), { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign']);
  const signature = new Uint8Array(await crypto.subtle.sign('RSASSA-PKCS1-v1_5', key, new TextEncoder().encode(unsigned)));
  const response = await fetch('https://oauth2.googleapis.com/token', { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: unsigned + '.' + base64url(signature) }), signal: AbortSignal.timeout(10000) });
  if (!response.ok) throw Error('Push authentication unavailable');
  const data = await response.json();
  if (!data.access_token) throw Error('Push authentication unavailable');
  cachedAuth = { token: data.access_token, until: Date.now() + Math.max(0, Math.min(Number(data.expires_in) || 3600, 3600) - 60) * 1000, email: credentials.client_email };
  return cachedAuth.token;
}

Deno.serve(async request => {
  const json = (body: unknown, status = 200) => Response.json(body, { status });
  if (request.method !== 'POST') return json({ message: 'POST required' }, 405);
  const secret = request.headers.get('X-Order-Worker');
  if (!secret) return json({ message: 'Worker authentication required' }, 401);
  const key = serverKey();
  const worker = async (operation: string, data: unknown = {}) => {
    const response = await fetch(env('SUPABASE_URL') + '/rest/v1/rpc/ctfly_order_push_worker', { method: 'POST', headers: { apikey: key, ...(key.startsWith('eyJ') ? { Authorization: 'Bearer ' + key } : {}), 'Content-Type': 'application/json' }, body: JSON.stringify({ p_secret: secret, p_operation: operation, p_data: data }), signal: AbortSignal.timeout(10000) });
    if (!response.ok) throw Error('Worker unavailable');
    return response.json();
  };
  try { await worker('ping'); } catch { return json({ message: 'Worker authentication failed' }, 403); }
  let credentials: Credentials;
  try {
    credentials = JSON.parse(env('FIREBASE_SERVICE_ACCOUNT'));
    if (!/^[a-z][a-z0-9-]{4,62}$/.test(credentials.project_id) || !credentials.client_email || !credentials.private_key) throw Error();
  } catch { return json({ configured: false, processed: 0 }); }
  try {
    // Authenticate before claiming; missing configuration cannot consume a notification job.
    const authorization = await accessToken(credentials);
    const jobs = await worker('claim') as Job[];
    let processed = 0;
    await Promise.all(jobs.map(async job => {
      try {
        const response = await fetch(`https://fcm.googleapis.com/v1/projects/${credentials.project_id}/messages:send`, {
          method: 'POST', headers: { Authorization: 'Bearer ' + authorization, 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: { token: job.token, data: { type: 'order_ready', notice_id: job.notice_id, order_id: job.order_id, user_id: job.user_id }, android: { priority: 'high', ttl: '3600s', collapse_key: job.order_id } } }), signal: AbortSignal.timeout(10000),
        });
        if (response.ok) {
          await worker('result', { id: job.id, status: 'Sent' }); processed++;
        } else {
          const error = await response.json().catch(() => ({}));
          const invalid = error.error?.details?.some((detail: { errorCode?: string }) => detail.errorCode === 'UNREGISTERED') === true;
          await worker('result', { id: job.id, status: invalid ? 'Skipped' : 'Failed', invalid_token: invalid, token: job.token });
        }
      } catch { await worker('result', { id: job.id, status: 'Failed' }); }
    }));
    return json({ configured: true, processed });
  } catch { return json({ message: 'Pickup notifications temporarily unavailable' }, 503); }
});
