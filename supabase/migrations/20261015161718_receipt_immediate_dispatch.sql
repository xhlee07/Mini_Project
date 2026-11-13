begin;

-- pg_net starts the HTTP request after commit, so mail cannot race an unpaid or
-- rolled-back order. Keep the existing minute cron as a recovery path.
create or replace function private.dispatch_queued_receipt() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
  if tg_op = 'UPDATE' and old.status is not distinct from new.status then
    return new;
  end if;
  begin
    perform private.kick_receipt_worker();
  exception when others then
    -- An unavailable HTTP queue must not undo a customer's successful payment.
    -- Keep the receipt Queued, without logging credentials or customer details.
    raise warning 'Immediate receipt dispatch unavailable (%); scheduled worker will retry.', sqlstate;
  end;
  return new;
end $$;

-- Only the server-owned queue can trigger this privileged call. No new client
-- endpoint or access to the worker secret is introduced.
revoke all on function private.dispatch_queued_receipt() from public, anon, authenticated;
drop trigger if exists dispatch_queued_receipt on public.receipt_email_jobs;
create trigger dispatch_queued_receipt
after insert or update of status on public.receipt_email_jobs
for each row when (new.status = 'Queued')
execute function private.dispatch_queued_receipt();

commit;
