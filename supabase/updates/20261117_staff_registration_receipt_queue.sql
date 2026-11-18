-- Apply to an existing CTFLY project before deploying staff-device-auth and club-services.
-- Existing accounts, device registrations, receipt jobs and worker settings are preserved.
begin;

-- The authenticated RPC lists/removes devices. Only the Edge Function can register them.
create or replace function ctfly_private.manage_staff_devices(p_owner uuid, p_operation text, p_data jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare device uuid; result jsonb;
begin
 -- Serialize additions/removals and check the active role under the same account lock.
 perform 1 from public.profiles p where p.profile_id=p_owner and p.active and p.role in ('staff','admin')
  and exists(select 1 from public.staff s where s.staff_id=p.profile_id) for update;
 if not found then
  raise exception 'Staff access required.';
 end if;
 if p_operation = 'register' then
  device := (p_data->>'device_id')::uuid;
  if device is null or char_length(trim(coalesce(p_data->>'staff_name',''))) not between 2 and 60 then
   raise exception 'Enter a staff name between 2 and 60 characters.';
  end if;
  if exists(select 1 from ctfly_private.staff_devices where id=device and staff_id<>p_owner) then
   raise exception 'This device is already registered to another account.';
  end if;
  if not exists(select 1 from ctfly_private.staff_devices where id=device and staff_id=p_owner)
   and (select count(*) from ctfly_private.staff_devices where staff_id=p_owner)>=5 then
   raise exception 'Five devices are already registered. Remove a device before adding another.';
  end if;
  insert into ctfly_private.staff_devices(id,staff_id,staff_name,device_name,public_key)
   values(device,p_owner,trim(p_data->>'staff_name'),p_data->>'device_name',p_data->>'public_key')
   on conflict(id) do update set staff_name=excluded.staff_name, device_name=excluded.device_name,
    public_key=excluded.public_key where staff_devices.staff_id=p_owner;
  if not found then raise exception 'This device is already registered to another account.'; end if;
  delete from ctfly_private.staff_device_challenges where device_id=device;
 elsif p_operation='remove' then
  delete from ctfly_private.staff_devices where id=(p_data->>'device_id')::uuid and staff_id=p_owner;
 elsif p_operation<>'list' or p_operation is null then
  raise exception 'Unsupported device operation.';
 end if;
 select coalesce(jsonb_agg(jsonb_build_object('id',id,'staff_name',staff_name,'device_name',device_name,
  'created_at',created_at,'last_used_at',last_used_at) order by created_at,id),'[]'::jsonb)
 into result from ctfly_private.staff_devices where staff_id=p_owner;
 return jsonb_build_object('devices',result,'limit',5);
end $$;
revoke all on function ctfly_private.manage_staff_devices(uuid,text,jsonb) from public,anon,authenticated,service_role;

create or replace function public.ctfly_staff_devices(p_operation text default 'list',p_data jsonb default '{}')
returns jsonb language plpgsql security definer set search_path = '' as $$
begin
 if p_operation is null or p_operation not in ('list','remove') then
  raise exception 'Unsupported device operation.';
 end if;
 return ctfly_private.manage_staff_devices(auth.uid(),p_operation,p_data);
end
$$;
revoke all on function public.ctfly_staff_devices(text,jsonb) from public,anon;
grant execute on function public.ctfly_staff_devices(text,jsonb) to authenticated;

create or replace function public.ctfly_register_staff_device(p_owner uuid,p_data jsonb)
returns jsonb language sql security definer set search_path = '' as $$
 select ctfly_private.manage_staff_devices(p_owner,'register',p_data)
$$;
revoke all on function public.ctfly_register_staff_device(uuid,jsonb) from public,anon,authenticated;
grant execute on function public.ctfly_register_staff_device(uuid,jsonb) to service_role;

drop function if exists ctfly_private.manage_staff_devices(text,jsonb);
revoke usage on schema ctfly_private from authenticated;

-- Only the server's service role can invoke the worker. The additional private secret authenticates cron dispatch.
create or replace function private.receipt_worker(secret_value text,operation text,d jsonb) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare jobs jsonb;state text;begin
 if not exists(select 1 from private.mail_runtime where worker_secret=secret_value) then raise exception 'Worker authentication failed.';end if;
 if operation='configure' then
  update private.mail_runtime set configured=coalesce((d->>'configured')::boolean,false) where id=true;
  if not coalesce((d->>'configured')::boolean,false) then update receipt_email_jobs set status='NotConfigured',last_error='Mail service is not configured.' where status in ('Queued','Failed');
  else update receipt_email_jobs set status='Queued',last_error='' where status='NotConfigured';end if;
  return jsonb_build_object('configured',coalesce((d->>'configured')::boolean,false));
 elsif operation='claim' then
  if not exists(select 1 from private.mail_runtime where configured) then return '[]'::jsonb;end if;
  update receipt_email_jobs set status='Unknown',last_error='Sending was interrupted. Verify delivery before retrying.' where status='Sending' and last_attempt<now()-interval '5 minutes';
  -- Leave other receipts queued until a worker is ready to send them.
  with chosen as(select bill_id from receipt_email_jobs where status='Queued' order by created_at,bill_id for update skip locked limit 1),claimed as(update receipt_email_jobs j set status='Sending',attempts=attempts+1,last_attempt=now() from chosen where j.bill_id=chosen.bill_id returning j.*)
  select coalesce(jsonb_agg(jsonb_build_object('bill',to_jsonb(b),'email',u.email)),'[]'::jsonb) into jobs from claimed j join bills b on b.id=j.bill_id join public."User" u on u.user_id=j.user_id;
  return jobs;
 elsif operation='result' then
  state:=d->>'status';if state not in ('Sent','Failed','Unknown') then raise exception 'Invalid worker result.';end if;
  update receipt_email_jobs set status=state,last_error=left(coalesce(d->>'error',''),400),provider_id=d->>'provider_id',sent_at=case when state='Sent' then now() else null end where bill_id=(d->>'id')::uuid and status='Sending';
  return jsonb_build_object('updated',found);
 end if;
 raise exception 'Invalid worker operation.';
end $$;

notify pgrst, 'reload schema';
commit;
