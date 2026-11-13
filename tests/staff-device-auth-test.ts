// Real ECDSA proofs against a fake Auth/DB transport. Never sends email or accesses live accounts.
Deno.env.set('SUPABASE_URL','https://fixture.test');
Deno.env.set('SUPABASE_SECRET_KEYS',JSON.stringify({default:'sb_secret_fixture'}));
Deno.env.set('SUPABASE_PUBLISHABLE_KEYS',JSON.stringify({default:'sb_publishable_fixture'}));
const originalServe = Deno.serve;
Deno.serve = (() => ({} as Deno.HttpServer)) as typeof Deno.serve;
const {handler}=await import('../supabase/functions/staff-device-auth/index.ts');
Deno.serve = originalServe;
const device=crypto.randomUUID(), owner=crypto.randomUUID();
const pair=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},false,['sign','verify']);
const b64=(v:Uint8Array)=>btoa(String.fromCharCode(...v));
const publicKey=b64(new Uint8Array(await crypto.subtle.exportKey('spki',pair.publicKey)));
async function sign(message:string){
 const raw=new Uint8Array(await crypto.subtle.sign({name:'ECDSA',hash:'SHA-256'},pair.privateKey,new TextEncoder().encode(message)));
 const ints=[raw.slice(0,32),raw.slice(32)].map(v=>{while(v.length>1&&v[0]===0)v=v.slice(1);return v[0]&128?new Uint8Array([0,...v]):v;});
 return b64(new Uint8Array([0x30,4+ints[0].length+ints[1].length,2,ints[0].length,...ints[0],2,ints[1].length,...ints[1]]));
}
let registered=false, active=true, generations=0, sessions=0;
const challenges=new Map<string,string>();
globalThis.fetch=async(input:RequestInfo|URL,init?:RequestInit)=>{
 const url=String(input), h=new Headers(init?.headers), b=init?.body?JSON.parse(String(init.body)):{};
 if(url.endsWith('/auth/v1/user'))return Response.json({id:owner},{status:h.get('Authorization')==='Bearer eyJ.staff'?200:401});
 if(url.endsWith('/ctfly_staff_devices')){
  if(b.p_operation==='register')registered=true;
  if(b.p_operation==='remove')registered=false;
  return Response.json({devices:registered?[{id:device,staff_name:b.p_data.staff_name??'Test staff',device_name:'Test phone'}]:[],limit:5});
 }
 if(url.endsWith('/ctfly_staff_device_auth')){
  if(!registered||!active)return Response.json({message:'Unavailable'},{status:400});
  if(b.p_operation==='challenge'){const id=crypto.randomUUID(),nonce=crypto.randomUUID();challenges.set(id,nonce);return Response.json({challenge_id:id,nonce});}
  if(b.p_operation==='consume'){
   const nonce=challenges.get(b.p_challenge);challenges.delete(b.p_challenge);
   return nonce?Response.json({nonce,public_key:publicKey,owner,email:'staff@example.test'}):Response.json({message:'Expired'},{status:400});
  }
  return Response.json({owner});
 }
 if(url.endsWith('/admin/generate_link')){generations++;if(h.get('apikey')!=='sb_secret_fixture')throw Error('Admin credentials missing');return Response.json({id:owner,hashed_token:'internal-only'});}
 if(url.endsWith('/auth/v1/verify')){sessions++;return Response.json({user:{id:owner},access_token:'new-session',refresh_token:'new-refresh'});}
 throw Error('Unexpected request');
};
const call=(body:unknown,token='')=>handler(new Request('https://fixture.test/function',{method:'POST',headers:{'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{})},body:JSON.stringify(body)}));
function check(value:unknown,label:string){if(!value)throw Error(label);}
const registration={operation:'register',device_id:device,staff_name:'Jamie',device_name:'Phone',public_key:publicKey,signature:await sign(`ctfly-enroll:${owner}:${device}`)};
check((await call(registration)).status===401,'Registration needs password-authenticated account');
check((await call({...registration,signature:await sign('wrong')} ,'eyJ.staff')).status===403,'Registration needs the device private key');
check((await call(registration,'eyJ.staff')).status===200,'Registration succeeds');
check((await call({operation:'list'})).status===401,'Device names are private');
async function login(signatureMessage?:string){
 const c=await (await call({operation:'challenge',device_id:device})).json();
 return {operation:'login',device_id:device,challenge_id:c.challenge_id,signature:await sign(signatureMessage??`ctfly-login:${device}:${c.challenge_id}:${c.nonce}`)};
}
check((await call(await login('wrong-device-proof'))).status===403,'Wrong proof denied');
check(generations===0,'No Auth token generated for wrong proof');
const proof=await login();const response=await call(proof);const session=await response.json();
check(response.status===200&&session.access_token==='new-session','Fresh session issued without existing login');
check(!('hashed_token' in session),'Internal login token never returned');
check(response.headers.get('Cache-Control')==='no-store','Session cannot be cached');
check((await call(proof)).status===403&&generations===1,'Replay cannot issue a second session');
check((await call(await login())).status===200&&sessions===2,'Immediate repeat login after sign-out is supported');
const stale=await login();registered=false;
check((await call(stale)).status===403&&sessions===2,'Removed device cannot sign in');
registered=true;active=false;
check((await call({operation:'challenge',device_id:device})).status!==200,'Inactive staff denied');
console.log('PASS: real ECDSA enrollment, unauthorized/wrong proof, fresh post-sign-out sessions, replay, removal and inactive staff.');
