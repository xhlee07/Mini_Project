import {PGlite} from '@electric-sql/pglite';
import {readFile} from 'node:fs/promises';
import {randomUUID} from 'node:crypto';
import assert from 'node:assert/strict';
const db=new PGlite();
await db.exec(`create role anon;create role authenticated;create schema auth;
create function auth.uid() returns uuid language sql stable as $$select nullif(current_setting('request.jwt.claim.sub',true),'')::uuid$$;
grant usage on schema public,auth to authenticated,anon;grant execute on function auth.uid() to authenticated,anon;
create table public.profiles(id uuid primary key,active boolean default true,role text);`);
await db.exec(await readFile(new URL('../supabase/migrations/20261017211132_virtual_tour_cctv.sql',import.meta.url),'utf8'));
await db.exec(await readFile(new URL('../supabase/migrations/20261019190833_wall_mounted_cctv.sql',import.meta.url),'utf8'));
const customer=randomUUID(),other=randomUUID(),staff=randomUUID(),admin=randomUUID(),session=randomUUID();
for(const [id,role] of [[customer,'customer'],[other,'customer'],[staff,'staff'],[admin,'admin']])await db.query('insert into public.profiles(id,role) values($1,$2)',[id,role]);
async function as(id,role='authenticated'){await db.exec('reset role');await db.query("select set_config('request.jwt.claim.sub',$1,false)",[id??'']);await db.exec(`set role ${role}`);}
async function publish({id=session,seq=1,area='GAMING',x=0,z=23,yaw=0,mode='walk',moving=false,leaving=false}={}){
 return (await db.query('select public.ctfly_tour_presence($1,$2,$3,$4,$5,$6,$7,$8,$9) v',[id,seq,area,x,z,yaw,mode,moving,leaving])).rows[0].v;
}
async function watch(){return (await db.query('select public.ctfly_tour_watch() v')).rows[0].v;}
async function cameras(){return (await db.query('select public.ctfly_tour_cameras() v')).rows[0].v;}
async function turn(id=0,yaw=1,pitch=-.2,hold=15000){return (await db.query('select public.ctfly_tour_turn_camera($1,$2,$3,$4) v',[id,yaw,pitch,hold])).rows[0].v;}
await as(null,'anon');await assert.rejects(cameras,/permission denied/);await assert.rejects(()=>turn(),/permission denied/);
await as(customer);assert.equal((await cameras()).cameras.length,0);await assert.rejects(()=>turn(),/Staff access/);
await assert.rejects(()=>db.query('select * from ctfly_tour.cameras'),/permission denied/);
await as(staff);await turn();
for(const args of [[-1,1,-.2,15000],[5,1,-.2,15000],[0,NaN,-.2,15000],[0,Infinity,-.2,15000],[0,-2,-.2,15000],[0,1,.5,15000],[0,1,-.2,20000]])
 await assert.rejects(()=>turn(...args),/Invalid camera/);
await as(customer);const mechanical=await cameras();assert.equal(mechanical.cameras[0].yaw,1);
assert.ok(mechanical.server_ms>0);assert.equal(mechanical.cameras[0].user_id,undefined);
await as(admin);await turn(1,-1,-.3,500);
await db.exec('reset role');await db.query("update ctfly_tour.cameras set expires_at=clock_timestamp()-interval '1 second'");
await as(customer);assert.equal((await cameras()).cameras.length,0,'Camera resumes patrol after control expires');
await as(null,'anon');await assert.rejects(()=>publish(),/permission denied/);await assert.rejects(watch,/permission denied/);
await as(staff);await assert.rejects(()=>publish(),/Customer access/);assert.equal((await watch()).visitors.length,0);
await as(customer);await assert.rejects(watch,/Staff access/);await assert.rejects(()=>db.query('select * from ctfly_tour.visitors'),/permission denied/);
for(const bad of [{x:Infinity},{x:NaN},{area:'FAKE'},{x:15},{z:-35},{yaw:4},{area:'GAMING',mode:'kart'}])await assert.rejects(()=>publish(bad),/Invalid virtual position/);
await publish({seq:1});await as(staff);let result=await watch();
assert.equal(result.visitors.length,1);assert.equal(result.visitors[0].x,0);assert.equal(result.visitors[0].user_id,undefined);assert.equal(result.visitors[0].email,undefined);
await as(customer);await publish({seq:2,x:5,moving:true});await as(staff);result=await watch();assert.equal(result.visitors[0].x,5);assert.equal(result.visitors[0].moving,true);
await as(other);await assert.rejects(()=>publish({seq:3,x:1}),/another account/);
await as(customer);await publish({seq:4,leaving:true});await publish({seq:3,x:7});
await as(staff);assert.equal((await watch()).visitors.length,0,'Late packet cannot resurrect a departed visitor');
await as(customer);await publish({seq:5,area:'OUTDOOR',x:20,z:85,mode:'kart'});
await as(admin);assert.equal((await watch()).visitors[0].area,'OUTDOOR');
await db.exec('reset role');await db.query("update ctfly_tour.visitors set updated_at=clock_timestamp()-interval '13 seconds' where session_id=$1",[session]);
await as(staff);assert.equal((await watch()).visitors.length,0,'Disconnected visitors expire');
await as(customer);await publish({seq:6});await db.exec('reset role');await db.query('update public.profiles set active=false where id=$1',[customer]);
await as(staff);assert.equal((await watch()).visitors.length,0,'Disabled customer disappears');
await db.exec('reset role');await db.query('update public.profiles set active=true where id=$1',[customer]);
await as(customer);for(let i=0;i<2;i++)await publish({id:randomUUID()});
await assert.rejects(()=>publish({id:randomUUID()}),/three active/);
await db.exec('reset role');await db.query('update public.profiles set active=false where id=$1',[staff]);await as(staff);await assert.rejects(watch,/Staff access/);
await assert.rejects(()=>turn(),/Staff access/);await assert.rejects(cameras,/Account access/);
await db.close();console.log('PASS: customer-only publish, staff-only watch, private table, valid coordinates, cross-device updates, no contacts, ordered exit, expiry, role changes, visit limit.');
