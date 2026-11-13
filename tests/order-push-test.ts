// In-process HTTP/auth contract test; never connects to Firebase or sends a notification.
let handler: (request: Request) => Promise<Response>;
const originalServe = Deno.serve;
Deno.serve = ((callback: typeof handler) => { handler = callback; return {} as Deno.HttpServer; }) as typeof Deno.serve;
Deno.env.set('SUPABASE_URL', 'https://fixture.supabase.test');
Deno.env.set('SUPABASE_SECRET_KEYS', JSON.stringify({default:'sb_secret_fixture'}));
Deno.env.delete('FIREBASE_SERVICE_ACCOUNT');
let claims = 0;
let result: Record<string, unknown> = {};
let payload: any;
let rejectDevice = false;
globalThis.fetch = async (input: RequestInfo | URL, init?: RequestInit) => {
  const url = String(input);
  if (url.endsWith('/ctfly_order_push_worker')) {
    const body = JSON.parse(String(init?.body));
    if (new Headers(init?.headers).get('apikey') !== 'sb_secret_fixture') throw Error('Missing server key');
    if (body.p_secret !== 'worker-secret') return Response.json({}, {status:403});
    if (body.p_operation === 'claim') {
      claims++;
      return Response.json([{id:'job-1',notice_id:'notice-1',order_id:'order-1',user_id:'owner-1',token:'private-device-token',language:'en'}]);
    }
    if (body.p_operation === 'result') result = body.p_data;
    return Response.json({authenticated:true});
  }
  if (url === 'https://oauth2.googleapis.com/token') {
    const jwt = new URLSearchParams(String(init?.body)).get('assertion')!;
    const claim = JSON.parse(atob(jwt.split('.')[1].replaceAll('-', '+').replaceAll('_', '/')));
    if (claim.scope !== 'https://www.googleapis.com/auth/firebase.messaging') throw Error('Overbroad scope');
    return Response.json({access_token:'mock-access-token',expires_in:3600});
  }
  if (url === 'https://fcm.googleapis.com/v1/projects/fixture-project/messages:send') {
    if (new Headers(init?.headers).get('Authorization') !== 'Bearer mock-access-token') throw Error('Missing OAuth token');
    payload = JSON.parse(String(init?.body));
    return rejectDevice ? Response.json({error:{details:[{errorCode:'UNREGISTERED'}]}},{status:404}) : Response.json({name:'messages/mock-1'});
  }
  throw Error('Unexpected external request');
};
await import('../supabase/functions/order-notifications/index.ts');
const call = (secret?: string) => handler(new Request('https://fixture/functions', {method:'POST',headers:secret?{'X-Order-Worker':secret}:{},body:JSON.stringify({token:'attacker-token',user_id:'attacker'})}));
function check(value: boolean, label: string) { if (!value) throw Error(label); }
check((await call()).status === 401, 'Unsigned requests denied');
check((await call('wrong')).status === 403, 'Wrong worker secret denied');
check((await (await call('worker-secret')).json()).configured === false && claims === 0, 'Missing Firebase configuration does not consume jobs');
const pair = await crypto.subtle.generateKey({name:'RSASSA-PKCS1-v1_5',modulusLength:2048,publicExponent:new Uint8Array([1,0,1]),hash:'SHA-256'},true,['sign','verify']);
const bytes = new Uint8Array(await crypto.subtle.exportKey('pkcs8',pair.privateKey));
const privateKey = '-----BEGIN PRIVATE KEY-----\n' + btoa(String.fromCharCode(...bytes)) + '\n-----END PRIVATE KEY-----';
Deno.env.set('FIREBASE_SERVICE_ACCOUNT',JSON.stringify({project_id:'fixture-project',client_email:'worker@fixture-project.iam.gserviceaccount.com',private_key:privateKey}));
const delivered = await (await call('worker-secret')).json();
check(delivered.processed === 1 && result.status === 'Sent','Successful push recorded');
check(payload.message.token === 'private-device-token' && payload.message.data.user_id === 'owner-1','Device and owner come only from server-owned jobs');
check(!payload.message.notification && !JSON.stringify(payload).includes('ctfly-pickup:'),'Data-only push contains no pickup secret and cannot auto-display for a logged-out account');
check(payload.message.android.priority === 'high','Pickup alert uses high priority');
rejectDevice = true;
await call('worker-secret');
check(result.status === 'Skipped' && result.invalid_token === true,'Expired tokens are removed instead of endlessly retried');
Deno.serve = originalServe;
console.log('PASS: push worker authentication, missing configuration, scoped OAuth, server-owned recipient, data-only private payload, high priority and invalid-token cleanup. No external requests.');
