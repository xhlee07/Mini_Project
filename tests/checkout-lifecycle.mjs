import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';

export async function verifyCheckoutLifecycle({db, action, snapshot, customer, other}) {
  const product = (await snapshot(customer)).products.find(p => p.active && p.stock > 3);
  assert.ok(product, 'A stocked product is required');
  const stock = async () => (await snapshot(customer)).products.find(p => p.id === product.id).stock;
  const initial = await stock();

  // Back from the quote screen, before any simulator transaction exists.
  const quoteOnly = await action(customer, 'order', {items: [{id: product.id, qty: 1}]});
  assert.equal(await stock(), initial - 1);
  await action(customer, 'cancel_order', {id: quoteOnly.id});
  assert.equal(await stock(), initial, 'Leaving releases the reservation');

  // Back after selecting a payment method: cancelled once, even if the response is lost.
  const held = await action(customer, 'order', {items: [{id: product.id, qty: 2}]});
  const q = await action(customer, 'test_quote', {kind: 'Order', id: held.id});
  const {payment} = await action(customer, 'test_checkout', {kind: 'Order', id: held.id,
    method: 'TNG', expected_total: q.total});
  await assert.rejects(() => action(other, 'cancel_order', {id: held.id}), /pending unpaid/);
  const cancellation = randomUUID();
  await action(customer, 'cancel_order', {id: held.id}, cancellation);
  await action(customer, 'cancel_order', {id: held.id}, cancellation);
  const cancelled = await snapshot(customer);
  assert.equal(cancelled.orders.find(o => o.id === held.id).status, 'Cancelled');
  assert.equal(cancelled.test_payments.find(p => p.id === payment.id).status, 'Cancelled');
  assert.equal(await stock(), initial, 'Retry never restores stock twice');
  const late = await action(customer, 'test_resolve', {id: payment.id, outcome: 'Success'});
  assert.equal(late.payment.status, 'Cancelled', 'Late success cannot resurrect a cancelled hold');
  assert.ok(!(await snapshot(customer)).bills.some(b => b.source_id === held.id));

  // A successful payment wins the source lock: exiting cannot cancel or restore its stock.
  const placed = await action(customer, 'order', {items: [{id: product.id, qty: 1}]});
  const paidQuote = await action(customer, 'test_quote', {kind: 'Order', id: placed.id});
  const {payment: paid} = await action(customer, 'test_checkout', {kind: 'Order', id: placed.id,
    method: 'Card', expected_total: paidQuote.total});
  await action(customer, 'test_resolve', {id: paid.id, outcome: 'Success'});
  await assert.rejects(() => action(customer, 'cancel_order', {id: placed.id}), /pending unpaid/);
  const settled = await snapshot(customer);
  assert.equal(settled.orders.find(o => o.id === placed.id).paid, true);
  assert.equal(settled.bills.filter(b => b.source_id === placed.id).length, 1);
  assert.equal(await stock(), initial - 1);
  console.log('PASS: checkout exit before/after payment initiation, ownership, cancellation retries, stock restoration, late success rejection, paid-order protection.');
}
