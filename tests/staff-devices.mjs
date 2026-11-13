import { PGlite } from '@electric-sql/pglite';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import assert from 'node:assert/strict';
const db = new PGlite();
await db.exec(`create role anon; create role authenticated; create role service_role;
create schema auth; create table auth.users(id uuid primary key,email text);
create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
grant usage on schema public,auth to authenticated,anon,service_role;
create table public.profiles(id uuid primary key references auth.users,active boolean default true,role text);
grant execute on function auth.uid() to authenticated,anon;`);
await db.exec(await readFile(new URL('../supabase/migrations/20261017182405_staff_fingerprint_devices.sql',import.meta.url),'utf8'));
const owner=randomUUID(), other=randomUUID(), customer=randomUUID();
for(const [id,role] of [[owner,'staff'],[other,'admin'],[customer,'customer']]){
  await db.query('insert into auth.users values($1,$2)',[id,`${id}@example.test`]);
  await db.query('insert into public.profiles(id,role) values($1,$2)',[id,role]);
}
async function as(id,role='authenticated'){
  await db.exec('reset role');
  await db.query("select set_config('request.jwt.claim.sub',$1,false)",[id??'']);
  await db.exec(`set role ${role}`);
}
async function manage(operation,data={}){return (await db.query('select public.ctfly_staff_devices($1,$2::jsonb) v',[operation,JSON.stringify(data)])).rows[0].v;}
const ids=Array.from({length:6},()=>randomUUID());
const data=(id,name='Test staff')=>({device_id:id,staff_name:name,device_name:'Test phone',public_key:'A'.repeat(124)});
await as(null,'anon');
await assert.rejects(()=>manage('list'),/permission denied/);
await as(customer);await assert.rejects(()=>manage('register',data(ids[0])),/Staff access/);
await as(owner);await assert.rejects(()=>manage('register',data(ids[0],' ')),/staff name/);
for(let n=0;n<5;n++)assert.equal((await manage('register',data(ids[n],`Staff ${n+1}`))).devices.length,n+1);
await assert.rejects(()=>manage('register',data(ids[5])),/Five devices/);
assert.equal((await manage('register',data(ids[0],'Updated staff'))).devices.length,5,'Re-enrolling the same phone does not use a new slot');
assert.equal((await manage('list')).devices[0].public_key,undefined,'Device list never reveals login proofs');
await assert.rejects(()=>db.query("select public.ctfly_staff_device_auth('challenge',$1)",[ids[0]]),/permission denied/);
await assert.rejects(()=>db.query('select * from ctfly_private.staff_devices'),/permission denied/);
await as(other);assert.equal((await manage('list')).devices.length,0);
await assert.rejects(()=>manage('register',data(ids[0])),/another account/);
await manage('remove',{device_id:ids[0]});
await as(owner);assert.equal((await manage('list')).devices.length,5,'Another account cannot remove a device');
await as(null,'service_role');
const auth=async(op,id=ids[0],challenge=null)=>(await db.query('select public.ctfly_staff_device_auth($1,$2,$3) v',[op,id,challenge])).rows[0].v;
const c=await auth('challenge');assert.ok(c.nonce.length>=64);
assert.equal((await auth('consume',ids[0],c.challenge_id)).owner,owner);
await assert.rejects(()=>auth('consume',ids[0],c.challenge_id),/expired/);
const exp=await auth('challenge');await db.exec('reset role');
await db.query("update ctfly_private.staff_device_challenges set expires_at=now()-interval '1 second' where id=$1",[exp.challenge_id]);
await as(null,'service_role');await assert.rejects(()=>auth('consume',ids[0],exp.challenge_id),/expired/);
const revoked=await auth('challenge');
await as(owner);await manage('remove',{device_id:ids[0]});
assert.equal((await manage('register',data(ids[5]))).devices.length,5,'Removing a phone frees one slot');
await as(null,'service_role');await assert.rejects(()=>auth('consume',ids[0],revoked.challenge_id),/unavailable/);
await db.exec('reset role');await db.query('update public.profiles set active=false where id=$1',[owner]);
await as(null,'service_role');await assert.rejects(()=>auth('challenge',ids[1]),/unavailable/);
await db.close();
console.log('PASS: five-device limit, names, replacement/removal, account isolation, permissions, single-use/expired/revoked challenges, inactive staff.');
