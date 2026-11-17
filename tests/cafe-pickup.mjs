import assert from 'node:assert/strict';
import { sqlSection } from './sql-section.mjs';
import {randomUUID} from 'node:crypto';

export async function verifyCafePickup({db,action,snapshot,customer,other,staff,admin}) {
  const raw = async(sql,args=[])=>{await db.exec('reset role');return db.query(sql,args)};
  await raw('select 1');
  const migration = sqlSection('cafe-pickup');
  await db.exec(migration);await db.exec(migration);
  const device = randomUUID(), token = 'fixture-device-token-long-enough';
  await action(customer,'register_push_device',{installation_id:device,token,language:'zh'});
  const product=(await snapshot(customer)).products.find(p=>p.active&&p.stock>0);
  const order=await action(customer,'order',{items:[{id:product.id,qty:1}]});
  await assert.rejects(()=>action(staff,'order_status',{id:order.id,status:'Preparing'}),/Pay first/);
  const quote=await action(customer,'test_quote',{kind:'Order',id:order.id});
  const {payment}=await action(customer,'test_checkout',{kind:'Order',id:order.id,method:'TNG',expected_total:quote.total});
  await action(customer,'test_resolve',{id:payment.id,outcome:'Success'});
  await assert.rejects(()=>action(customer,'order_status',{id:order.id,status:'Preparing'}),/Staff/);
  await action(staff,'order_status',{id:order.id,status:'Preparing'});
  await assert.rejects(()=>action(staff,'order_status',{id:order.id,status:'Received'}),/advance/);
  await action(staff,'order_status',{id:order.id,status:'Ready'});
  const state=await snapshot(customer);
  const ready=state.orders.find(o=>o.id===order.id);
  assert.equal(ready.status,'Ready');assert.ok(ready.pickup_token);assert.ok(ready.ready_at);
  assert.equal(state.order_notifications.filter(n=>n.order_id===order.id).length,1);
  const others=await snapshot(other);
  assert.ok(!others.orders.some(o=>o.id===order.id));
  assert.ok(!others.order_notifications.some(n=>n.order_id===order.id));
  assert.equal((await raw('select count(*)::int n from private.order_push_jobs where user_id=$1',[customer])).rows[0].n,1);
  const pickup='ctfly-pickup:'+ready.pickup_token;
  await assert.rejects(()=>action(customer,'order_pickup',{token:pickup}),/Staff/);
  await assert.rejects(()=>action(other,'pickup_preview',{token:pickup}),/Staff/);
  await assert.rejects(()=>action(staff,'pickup_preview',{token:'ctfly:'+ready.pickup_token}),/not a café/);
  await assert.rejects(()=>action(staff,'order_pickup',{token:'ctfly-pickup:'+randomUUID()}),/not found/);
  await assert.rejects(()=>action(staff,'order_status',{id:order.id,status:'Delivered'}),/advance/);
  await assert.rejects(()=>action(staff,'order_status',{id:order.id,status:'Received'}),/advance/);
  const preview=await action(staff,'pickup_preview',{token:pickup});
  assert.equal(preview.order.id,order.id);assert.ok(preview.customer_name);assert.equal(preview.items.length,1);
  const secret=(await raw('select worker_secret from private.mail_runtime')).rows[0].worker_secret;
  await assert.rejects(()=>raw("select private.order_push_worker('bad','claim','{}')"),/authentication/);
  const jobs=(await raw("select private.order_push_worker($1,'claim','{}') r",[secret])).rows[0].r;
  assert.equal(jobs.length,1);assert.equal(jobs[0].token,token);assert.equal(jobs[0].user_id,customer);
  assert.deepEqual((await raw("select private.order_push_worker($1,'claim','{}') r",[secret])).rows[0].r,[],'two workers must not claim the same push');
  await raw("select private.order_push_worker($1,'result',$2::jsonb)",[secret,JSON.stringify({id:jobs[0].id,status:'Failed'})]);
  const request=randomUUID();
  const received=await action(staff,'order_pickup',{token:pickup},request);
  assert.equal(received.order.status,'Received');assert.ok(received.order.received_at);assert.equal(received.order.received_by,staff);
  assert.equal((await action(staff,'order_pickup',{token:pickup},request)).order.id,order.id,'transport retry must be idempotent');
  await assert.rejects(()=>action(admin,'order_pickup',{token:pickup}),/already been collected/);
  await assert.rejects(()=>action(staff,'pickup_preview',{token:pickup}),/already been collected/);
  assert.ok((await snapshot(customer)).order_notifications.find(n=>n.order_id===order.id).read_at);
  assert.deepEqual((await raw("select private.order_push_worker($1,'claim','{}') r",[secret])).rows[0].r,[],'received orders cannot be pushed later');
  // Device switching must move ownership; the old account cannot remove the new account's registration.
  await action(other,'register_push_device',{installation_id:device,token,language:'en'});
  await action(customer,'unregister_push_device',{installation_id:device});
  assert.equal((await raw('select user_id from private.user_push_devices where installation_id=$1',[device])).rows[0].user_id,other);
  for(const role of ['anon','authenticated']) {
    await db.exec(`set role ${role}`);
    await assert.rejects(()=>db.query('select * from private.user_push_devices'),/permission denied/);
    await assert.rejects(()=>db.query('select * from public.order_notifications'),/permission denied/);
    await assert.rejects(()=>db.query("select public.ctfly_order_push_worker('bad','claim','{}')"),/permission denied/);
    await db.exec('reset role');
  }
  console.log('PASS: café paid/ready QR lifecycle, staff-only single collection, idempotent handover, private notices/devices, push claims, account switching and stale-push prevention. No cloud changes.');
}
