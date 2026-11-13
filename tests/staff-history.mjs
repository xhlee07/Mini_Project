import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';

export async function verifyStaffHistory({ db, snapshot, customer, staff, admin }) {
 const sql=await readFile(new URL('../supabase/migrations/20261015190808_staff_activity_history.sql',import.meta.url),'utf8');
 await db.exec('reset role');await db.exec(sql);await db.exec(sql);
 const as=async id=>{await db.exec('reset role');await db.query("select set_config('request.jwt.claim.sub',$1,false)",[id]);await db.exec('set role authenticated');};
 const query=async(id,module,day=null,category='All',search='',before=null,key=null)=>{
  await as(id);return (await db.query('select public.ctfly_staff_history($1,$2::date,$3,$4,$5::timestamptz,$6) value',[module,day,category,search,before,key])).rows[0].value;
 };
 await assert.rejects(()=>query(customer,'Activity'),/Staff access/);
 await db.exec('reset role;set role anon');
 await assert.rejects(()=>db.query("select public.ctfly_staff_history('Activity')"),/permission denied/);
 await assert.rejects(()=>query(staff,'Users'),/supported history module/);
 await assert.rejects(()=>query(staff,'Activity',null,'Passwords'),/supported history category/);
 await assert.rejects(()=>query(staff,'Activity',null,'All','x'.repeat(101)),/100 characters/);
 await assert.rejects(()=>query(staff,'Activity',null,'All','',new Date().toISOString()),/cursor/);
 await assert.rejects(()=>query(staff,'Report'),/Manager access/);
 await db.exec('reset role;begin');
 try {
  await db.query("insert into public.orders(user_id,total,status,paid,created_at) select $1,650,'Received',true,'1999-01-01T16:00Z'::timestamptz+make_interval(secs=>n) from generate_series(0,319) n",[customer]);
  await db.query("insert into public.orders(user_id,total,created_at) values($1,650,'1999-01-01T15:59:59Z'),($1,650,'1999-01-02T16:00Z')",[customer]);
  let page=await query(staff,'Activity','1999-01-02','Orders');
  const keys=[];
  while(true) {
   assert.ok(page.items.length<=40);
   for(const r of page.items) {
    keys.push(r.history_key);
    assert.equal(r.customer_name,'Ryan Lee');
    for(const secret of ['token','pickup_token','phone','dob','user_push_devices']) assert.equal(Object.hasOwn(r,secret),false);
   }
   if(!page.has_more)break;
   page=await query(staff,'Activity','1999-01-02','Orders','',page.next_before,page.next_key);
  }
  assert.equal(keys.length,320,'all older records are accessible beyond the snapshot limit');
  assert.equal(new Set(keys).size,320,'cursor pages never repeat a record');
  const firstId=keys[0].slice('Order:'.length);
  assert.equal((await query(staff,'Activity','1999-01-02','Orders',firstId)).items.length,1);
  assert.equal((await query(staff,'Activity','1999-01-02','Orders','%')).items.length,0,'wildcards are literal search text');
  assert.equal((await query(staff,'Activity','1999-01-01','Orders')).items.length,1,'day before midnight');
  assert.equal((await query(staff,'Activity','1999-01-03','Orders')).items.length,1,'next midnight excluded');
  assert.equal((await query(staff,'Activity','1901-01-01')).items.length,0);
  const snap=await snapshot(staff);
  assert.equal(snap.orders.length,300,'test exceeds the old snapshot cap');
  assert.equal((await query(staff,'Activity',null,'Bookings')).items[0].category,'Bookings');
  assert.equal((await query(staff,'Activity',null,'Competitions')).items[0].category,'Competitions');
  await db.exec('reset role');
  await db.query("insert into public.redemptions(user_id,reward_id,name,points,status) values($1,1,'History reward',100,'Collected')",[customer]);
  assert.equal((await query(staff,'Activity',null,'Rewards','History reward')).items[0].points,100);
  const tx=(await query(staff,'Transactions')).items;
  assert.ok(tx.some(r=>r.environment==='test'));
  assert.ok(tx.some(r=>r.environment==='counter'),'counter payments visible to authorized staff');
  assert.equal(new Set(tx.map(r=>r.history_key)).size,tx.length);
  assert.ok((await query(staff,'Feedback')).items.every(r=>r.customer_name && r.feedback_category));
  await db.exec('reset role');
  await db.query("insert into public.checklist values($1,'1999-01-02','Test network')",[staff]);
  assert.equal((await query(staff,'Checklist','1999-01-02')).items[0].item,'Test network');
  assert.equal((await query(admin,'Checklist','1999-01-02')).items.length,0,'checklist stays scoped to its author');
  assert.ok((await query(admin,'Report')).items[0].completed_sessions>=1);
  await db.exec('reset role');await db.query('update profiles set active=false where id=$1',[staff]);
  await assert.rejects(()=>query(staff,'Activity'),/Staff access/);
 } finally {await db.exec('rollback;reset role');}
 console.log('PASS: staff history roles, privacy, all four activities, old records, keyset pagination, Malaysia midnight boundaries, transactions, feedback, historical checklists and manager reports.');
}
