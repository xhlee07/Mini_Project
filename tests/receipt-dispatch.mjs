import assert from 'node:assert/strict';
import { sqlSection } from './sql-section.mjs';

export async function verifyReceiptDispatch({db, action, snapshot, customer}) {
  await db.exec('reset role');
  const migration = sqlSection('receipt-dispatch');
  await db.exec(migration);
  await db.exec(migration); // Safe to reapply; must leave exactly one trigger.
  // Replace only the network boundary in the local database. No real email.
  await db.exec(`create table private.dispatch_test_outbox(id bigint generated always as identity);
    create or replace function private.kick_receipt_worker() returns void
    language plpgsql security definer set search_path='' as $$begin
      if current_setting('ctfly.test_dispatch_failure',true)='yes' then
        raise exception 'test queue unavailable';
      end if;
      insert into private.dispatch_test_outbox default values;
    end $$;
    update public.receipt_email_jobs set status='Sent';`);
  const raw = async (sql, args=[]) => {await db.exec('reset role'); return db.query(sql,args);};
  const count = async () => Number((await raw('select count(*) n from private.dispatch_test_outbox')).rows[0].n);
  const product = (await snapshot(customer)).products.find(p=>p.active && p.stock>0);
  assert.ok(product, 'test product available');
  const order = await action(customer, 'order', {items:[{id:product.id, qty:1}]});
  assert.equal(await count(), 0, 'unpaid order must not dispatch');
  const quote = await action(customer, 'test_quote', {kind:'Order',id:order.id});
  const {payment} = await action(customer, 'test_checkout', {kind:'Order',id:order.id,method:'TNG',expected_total:quote.total});
  const paid = await action(customer, 'test_resolve', {id:payment.id,outcome:'Success'});
  const id = paid.receipt.id;
  assert.equal(await count(), 1, 'payment queues an immediate dispatch');
  await action(customer, 'test_resolve', {id:payment.id,outcome:'Success'});
  assert.equal(await count(), 1, 'payment callback retry must not queue another email');
  await action(customer, 'receipt_email', {id});
  assert.equal(await count(), 1, 'already queued receipt must not create another request');
  const secret = (await raw('select worker_secret from private.mail_runtime')).rows[0].worker_secret;
  await raw("select private.receipt_worker($1,'configure','{\"configured\":true}')",[secret]);
  const first = (await raw("select private.receipt_worker($1,'claim','{}') r",[secret])).rows[0].r;
  assert.equal(first.length, 1);
  assert.equal(first[0].bill.id, id);
  assert.deepEqual((await raw("select private.receipt_worker($1,'claim','{}') r",[secret])).rows[0].r, [], 'cron and immediate workers cannot claim the same job');
  await raw("select private.receipt_worker($1,'result',$2::jsonb)",[secret,JSON.stringify({id,status:'Sent',provider_id:'local-test'})]);
  assert.equal(await count(), 1, 'Sending and Sent must not trigger another dispatch');
  await raw("update public.receipt_email_jobs set status='Failed' where bill_id=$1",[id]);
  await action(customer, 'receipt_email', {id});
  assert.equal(await count(), 2, 'explicit retry of a failed receipt dispatches immediately');
  await raw('begin');
  await db.query("update public.receipt_email_jobs set status='Failed' where bill_id=$1",[id]);
  await db.query("update public.receipt_email_jobs set status='Queued' where bill_id=$1",[id]);
  await db.exec('rollback');
  assert.equal(await count(), 2, 'rolled back payment/retry must not leave an HTTP request');
  await raw("select set_config('ctfly.test_dispatch_failure','yes',false)");
  await raw("update public.receipt_email_jobs set status='Failed' where bill_id=$1",[id]);
  await action(customer, 'receipt_email', {id});
  assert.equal((await raw('select status from public.receipt_email_jobs where bill_id=$1',[id])).rows[0].status, 'Queued', 'HTTP failure must retain queued receipt for cron');
  assert.equal(await count(), 2);
  await raw("select set_config('ctfly.test_dispatch_failure','no',false)");
  for (const role of ['anon','authenticated']) {
    await db.exec(`set role ${role}`);
    await assert.rejects(()=>db.exec('select private.dispatch_queued_receipt()'),/permission denied/);
    await db.exec('reset role');
  }
  assert.equal((await raw("select count(*)::int n from pg_trigger where tgrelid='public.receipt_email_jobs'::regclass and tgname='dispatch_queued_receipt'")).rows[0].n, 1);

  const nextId = (await raw(`insert into public.bills(user_id,kind,source_id,subtotal,discount,total,points,method)
    values ($1,'Order',gen_random_uuid(),100,0,100,1,'Cash') returning id`, [customer])).rows[0].id;
  const claim = async () => (await raw("select private.receipt_worker($1,'claim','{}') r",[secret])).rows[0].r;
  const jobStatus = async billId => (await raw('select status from public.receipt_email_jobs where bill_id=$1',[billId])).rows[0].status;
  const interrupted = await claim();
  assert.equal(interrupted.length, 1, 'claim only the receipt the worker is about to send');
  const interruptedId = interrupted[0].bill.id;
  const queuedId = interruptedId === id ? nextId : id;
  assert.equal(await jobStatus(queuedId), 'Queued');
  await raw("update public.receipt_email_jobs set last_attempt=now()-interval '6 minutes' where bill_id=$1",[interruptedId]);
  assert.equal((await claim())[0].bill.id, queuedId, 'cron can claim the untouched receipt after an interruption');
  assert.equal(await jobStatus(interruptedId), 'Unknown', 'ambiguous delivery still requires verification');
  await raw("select private.receipt_worker($1,'result',$2::jsonb)",[secret,JSON.stringify({id:queuedId,status:'Sent',provider_id:'local-test'})]);
  console.log('PASS: immediate receipt dispatch on payment/retry; no duplicate claims or rollback sends; interruptions leave untouched receipts queued. No external email.');
}
