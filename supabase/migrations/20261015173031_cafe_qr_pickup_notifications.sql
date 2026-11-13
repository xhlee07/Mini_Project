begin;

alter table public.orders add column if not exists pickup_token uuid;
alter table public.orders add column if not exists ready_at timestamptz;
alter table public.orders add column if not exists received_at timestamptz;
alter table public.orders add column if not exists received_by uuid references public.profiles;
alter table public.orders drop constraint if exists orders_status_check;
update public.orders set status='Received' where status='Delivered';
alter table public.orders add constraint orders_status_check check(status in ('Pending','Preparing','Ready','Received','Cancelled'));
create unique index if not exists orders_pickup_token_idx on public.orders(pickup_token) where pickup_token is not null;
create index if not exists orders_received_by_idx on public.orders(received_by) where received_by is not null;
update public.orders set pickup_token=gen_random_uuid(),ready_at=coalesce(ready_at,now()) where status='Ready' and paid and pickup_token is null;

create table if not exists public.order_notifications (
 id uuid primary key default gen_random_uuid(), user_id uuid not null references public.profiles,
 order_id uuid not null unique references public.orders, created_at timestamptz not null default now(),read_at timestamptz
);
create index if not exists order_notifications_user_idx on public.order_notifications(user_id,created_at desc);
alter table public.order_notifications enable row level security;
revoke all on public.order_notifications from public,anon,authenticated;

create table if not exists private.user_push_devices (
 installation_id uuid primary key, user_id uuid not null references public.profiles,
 token text not null unique check(length(token) between 20 and 4096), language text not null check(language in ('en','zh','ms')),
 updated_at timestamptz not null default now()
);
create index if not exists user_push_devices_user_idx on private.user_push_devices(user_id);
alter table private.user_push_devices enable row level security;
revoke all on private.user_push_devices from public,anon,authenticated;

create table if not exists private.order_push_jobs (
 id uuid primary key default gen_random_uuid(), notice_id uuid not null references public.order_notifications,
 installation_id uuid not null references private.user_push_devices on delete cascade,
 user_id uuid not null references public.profiles, status text not null default 'Queued' check(status in ('Queued','Sending','Sent','Failed','Skipped')),
 attempts int not null default 0, next_attempt timestamptz not null default now(),last_attempt timestamptz,
 unique(notice_id,installation_id)
);
create index if not exists order_push_jobs_queue_idx on private.order_push_jobs(status,next_attempt);
create index if not exists order_push_jobs_device_idx on private.order_push_jobs(installation_id);
create index if not exists order_push_jobs_user_idx on private.order_push_jobs(user_id);
alter table private.order_push_jobs enable row level security;
revoke all on private.order_push_jobs from public,anon,authenticated;

-- Only a staff scan can complete pickup. A QR is bound to a paid, ready order.
create or replace function private.cafe_pickup_action(a text,d jsonb) returns jsonb
language plpgsql set search_path = '' as $$
declare me public.profiles; o public.orders; token_value uuid; installation uuid;
begin
 select * into me from public.profiles where id=auth.uid() and active;
 if me.id is null then raise exception 'Please sign in with an active CTFLY account.';end if;
 if a in ('pickup_preview','order_pickup') then
  if me.role not in ('staff','admin') then raise exception 'Staff access required.';end if;
  if coalesce(d->>'token','') !~ '^ctfly-pickup:[0-9a-fA-F-]{36}$' then raise exception 'This is not a café pickup QR.';end if;
  begin token_value:=substring(d->>'token' from 14)::uuid;exception when invalid_text_representation then raise exception 'This is not a café pickup QR.';end;
  select * into o from public.orders where pickup_token=token_value for update;
  if o.id is null then raise exception 'Pickup QR not found. Ask the customer to open their ready order.';end if;
  if o.status='Received' then raise exception 'This order has already been collected.';end if;
  if not o.paid or o.status<>'Ready' then raise exception 'Only paid orders marked Ready can be collected.';end if;
  if a='order_pickup' then
   update public.orders set status='Received',received_at=now(),received_by=me.id where id=o.id returning * into o;
   update public.order_notifications set read_at=coalesce(read_at,now()) where order_id=o.id;
   update private.order_push_jobs set status='Skipped' where notice_id in (select id from public.order_notifications where order_id=o.id) and status in ('Queued','Failed');
  end if;
  return jsonb_build_object('order',to_jsonb(o),'customer_name',(select name from public.profiles where id=o.user_id),'items',(select coalesce(jsonb_agg(jsonb_build_object('name',name,'qty',qty)),'[]'::jsonb) from public.order_items where order_id=o.id),'message',case when a='order_pickup' then 'Order collected. Enjoy your food!' else 'Pickup verified. Check the items before handover.' end);
 elsif a='register_push_device' then
  if me.role<>'customer' then raise exception 'Customer account required.';end if;
  installation:=(d->>'installation_id')::uuid;
  if installation is null or length(coalesce(d->>'token','')) not between 20 and 4096 or coalesce(d->>'language','') not in ('en','zh','ms') then raise exception 'Invalid notification device.';end if;
  delete from private.user_push_devices where token=d->>'token' and installation_id<>installation;
  insert into private.user_push_devices(installation_id,user_id,token,language) values(installation,me.id,d->>'token',d->>'language')
  on conflict(installation_id) do update set user_id=excluded.user_id,token=excluded.token,language=excluded.language,updated_at=now();
  -- Bound inactive device registrations and avoid accumulating shared-device owners.
  delete from private.user_push_devices where user_id=me.id and installation_id in (select installation_id from private.user_push_devices where user_id=me.id order by updated_at desc offset 5);
  return jsonb_build_object('message','Pickup notifications enabled.');
 elsif a='unregister_push_device' then
  delete from private.user_push_devices where installation_id=(d->>'installation_id')::uuid and user_id=me.id;
  return jsonb_build_object('message','Notification device removed.');
 elsif a='read_order_notice' then
  update public.order_notifications set read_at=coalesce(read_at,now()) where order_id=(d->>'order_id')::uuid and user_id=me.id;
  return jsonb_build_object('message','Notification read.');
 end if;
 raise exception 'Unsupported café action.';
end $$;
revoke all on function private.cafe_pickup_action(text,jsonb) from public,anon,authenticated;

create or replace function private.kick_order_push() returns void
language plpgsql security definer set search_path = '' as $$
begin
 if to_regprocedure('net.http_post(text,jsonb,jsonb,jsonb,integer)') is not null then
  perform net.http_post(url:='https://shfatnjwpdyxdbehvjil.supabase.co/functions/v1/order-notifications',headers:=jsonb_build_object('Content-Type','application/json','X-Order-Worker',(select worker_secret from private.mail_runtime where id=true)),body:='{}'::jsonb,timeout_milliseconds:=60000);
 end if;
end $$;
revoke all on function private.kick_order_push() from public,anon,authenticated;

create or replace function private.prepare_order_pickup() returns trigger
language plpgsql set search_path = '' as $$
begin
 if new.status='Ready' and old.status is distinct from new.status then
  if not new.paid then raise exception 'Pay first, then prepare the order.';end if;
  new.pickup_token:=gen_random_uuid();new.ready_at:=now();
 end if;
 return new;
end $$;
revoke all on function private.prepare_order_pickup() from public,anon,authenticated;
drop trigger if exists prepare_order_pickup on public.orders;
create trigger prepare_order_pickup before update of status on public.orders for each row execute function private.prepare_order_pickup();

create or replace function private.notify_order_ready() returns trigger
language plpgsql security definer set search_path = '' as $$
declare notice uuid;
begin
 insert into public.order_notifications(user_id,order_id) values(new.user_id,new.id) on conflict(order_id) do nothing returning id into notice;
 if notice is not null then
  insert into private.order_push_jobs(notice_id,installation_id,user_id)
   select notice,installation_id,new.user_id from private.user_push_devices where user_id=new.user_id and updated_at>now()-interval '60 days' on conflict do nothing;
  begin perform private.kick_order_push();exception when others then raise warning 'Pickup push scheduling unavailable (%).',sqlstate;end;
 end if;
 return new;
end $$;
revoke all on function private.notify_order_ready() from public,anon,authenticated;
drop trigger if exists notify_order_ready on public.orders;
create trigger notify_order_ready after update of status on public.orders for each row when (new.status='Ready' and old.status is distinct from new.status) execute function private.notify_order_ready();

-- Preserve the existing authenticated API, account locks and request deduplication.
do $$ declare def text; old_branch text;begin
 def:=pg_get_functiondef('public.ctfly_action(text,jsonb,uuid)'::regprocedure);
 if position('private.cafe_pickup_action' in def)=0 then
  if position('if p_action=''save_event''' in def)=0 then raise exception 'Receipt/events migration is required first.';end if;
  def:=replace(def,'if p_action=''save_event''','if p_action in (''pickup_preview'',''order_pickup'',''register_push_device'',''unregister_push_device'',''read_order_notice'') then result:=private.cafe_pickup_action(p_action,p_data); elsif p_action=''save_event''');
 end if;
 old_branch:='or (status=''Ready'' and p_data->>''status''=''Delivered'')';
 def:=replace(def,old_branch,'');
 execute def;
 def:=pg_get_functiondef('public.ctfly_snapshot()'::regprocedure);
 if position('''order_notifications''' in def)=0 then
  def:=replace(def,'return result;','result:=result||jsonb_build_object(''order_notifications'',coalesce((select jsonb_agg(to_jsonb(n) order by n.created_at desc) from public.order_notifications n where n.user_id=auth.uid() and n.created_at>now()-interval ''30 days''),''[]''::jsonb)); return result;');
  execute def;
 end if;
end $$;

create or replace function private.order_push_worker(secret_value text,operation text,d jsonb) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare result jsonb;
begin
 if not exists(select 1 from private.mail_runtime where id=true and worker_secret=secret_value) then raise exception 'Worker authentication failed.';end if;
 if operation='ping' then return jsonb_build_object('authenticated',true);
 elsif operation='claim' then
  update private.order_push_jobs set status='Skipped' where status='Sending' and last_attempt<now()-interval '5 minutes' and attempts>=3;
  update private.order_push_jobs set status='Queued' where status='Sending' and last_attempt<now()-interval '5 minutes' and attempts<3;
  update private.order_push_jobs j set status='Skipped' where status in ('Queued','Failed') and (attempts>=3 or not exists(select 1 from private.user_push_devices v join public.order_notifications n on n.id=j.notice_id join public.orders o on o.id=n.order_id join public.profiles p on p.id=j.user_id where v.installation_id=j.installation_id and v.user_id=j.user_id and p.active and o.status='Ready' and o.paid and n.created_at>now()-interval '1 day'));
  with chosen as(select id from private.order_push_jobs where status in ('Queued','Failed') and attempts<3 and next_attempt<=now() order by next_attempt for update skip locked limit 10), claimed as(update private.order_push_jobs j set status='Sending',attempts=attempts+1,last_attempt=now() from chosen where j.id=chosen.id returning j.*)
  select coalesce(jsonb_agg(jsonb_build_object('id',j.id,'notice_id',n.id,'order_id',n.order_id,'user_id',j.user_id,'token',v.token,'language',v.language)),'[]'::jsonb) into result from claimed j join private.user_push_devices v on v.installation_id=j.installation_id and v.user_id=j.user_id join public.order_notifications n on n.id=j.notice_id;
  return result;
 elsif operation='result' then
  if d->>'status' not in ('Sent','Failed','Skipped') then raise exception 'Invalid push result.';end if;
  update private.order_push_jobs set status=d->>'status',next_attempt=now()+interval '1 minute' where id=(d->>'id')::uuid and status='Sending';
  if coalesce((d->>'invalid_token')::boolean,false) then delete from private.user_push_devices where token=d->>'token';end if;
  return jsonb_build_object('updated',true);
 end if;
 raise exception 'Unsupported worker operation.';
end $$;
revoke all on function private.order_push_worker(text,text,jsonb) from public,anon,authenticated;
create or replace function public.ctfly_order_push_worker(p_secret text,p_operation text,p_data jsonb default '{}'::jsonb) returns jsonb
language sql security invoker set search_path = '' as $$select private.order_push_worker(p_secret,p_operation,p_data)$$;
revoke all on function public.ctfly_order_push_worker(text,text,jsonb) from public,anon,authenticated;
do $$begin if exists(select 1 from pg_roles where rolname='service_role') then
 grant usage on schema private to service_role;
 grant execute on function private.order_push_worker(text,text,jsonb),public.ctfly_order_push_worker(text,text,jsonb) to service_role;
end if;end $$;
do $$begin if to_regclass('cron.job') is not null then
 perform cron.schedule('ctfly-order-push','* * * * *','select private.kick_order_push()');
end if;end $$;
commit;
