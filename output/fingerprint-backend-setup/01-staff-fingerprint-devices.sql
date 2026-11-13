begin;

-- Device public keys only. Fingerprints and private keys never leave the phone.
create schema if not exists ctfly_private;
revoke all on schema ctfly_private from public, anon, authenticated;
create table ctfly_private.staff_devices (
 id uuid primary key,
 user_id uuid not null references public.profiles(id) on delete cascade,
 staff_name text not null check (char_length(staff_name) between 2 and 60),
 device_name text not null check (char_length(device_name) between 1 and 100),
 public_key text not null check (char_length(public_key) between 80 and 300),
 created_at timestamptz not null default now(),
 last_used_at timestamptz
);
create index staff_devices_user_idx on ctfly_private.staff_devices(user_id);
alter table ctfly_private.staff_devices enable row level security;
create table ctfly_private.staff_device_challenges (
 id uuid primary key default gen_random_uuid(),
 device_id uuid not null references ctfly_private.staff_devices(id) on delete cascade,
 nonce text not null,
 expires_at timestamptz not null default now() + interval '2 minutes'
);
create index staff_device_challenges_device_idx on ctfly_private.staff_device_challenges(device_id);
alter table ctfly_private.staff_device_challenges enable row level security;

-- Private implementation, with a narrow authenticated RPC wrapper below.
create function ctfly_private.manage_staff_devices(p_operation text, p_data jsonb)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare owner uuid := auth.uid(); device uuid; result jsonb;
begin
 if owner is null or not exists(select 1 from public.profiles where id=owner and active and role in ('staff','admin')) then
  raise exception 'Staff access required.';
 end if;
 -- Serialize additions/removals per account so parallel requests cannot exceed five.
 perform 1 from public.profiles where id=owner for update;
 if p_operation = 'register' then
  device := (p_data->>'device_id')::uuid;
  if device is null or char_length(trim(coalesce(p_data->>'staff_name',''))) not between 2 and 60 then
   raise exception 'Enter a staff name between 2 and 60 characters.';
  end if;
  if exists(select 1 from ctfly_private.staff_devices where id=device and user_id<>owner) then
   raise exception 'This device is already registered to another account.';
  end if;
  if not exists(select 1 from ctfly_private.staff_devices where id=device and user_id=owner)
   and (select count(*) from ctfly_private.staff_devices where user_id=owner)>=5 then
   raise exception 'Five devices are already registered. Remove a device before adding another.';
  end if;
  insert into ctfly_private.staff_devices(id,user_id,staff_name,device_name,public_key)
   values(device,owner,trim(p_data->>'staff_name'),p_data->>'device_name',p_data->>'public_key')
   on conflict(id) do update set staff_name=excluded.staff_name, device_name=excluded.device_name,
    public_key=excluded.public_key where staff_devices.user_id=owner;
  delete from ctfly_private.staff_device_challenges where device_id=device;
 elsif p_operation='remove' then
  delete from ctfly_private.staff_devices where id=(p_data->>'device_id')::uuid and user_id=owner;
 elsif p_operation<>'list' or p_operation is null then
  raise exception 'Unsupported device operation.';
 end if;
 select coalesce(jsonb_agg(jsonb_build_object('id',id,'staff_name',staff_name,'device_name',device_name,
  'created_at',created_at,'last_used_at',last_used_at) order by created_at,id),'[]'::jsonb)
 into result from ctfly_private.staff_devices where user_id=owner;
 return jsonb_build_object('devices',result,'limit',5);
end $$;
revoke all on function ctfly_private.manage_staff_devices(text,jsonb) from public,anon,authenticated;
grant usage on schema ctfly_private to authenticated;
grant execute on function ctfly_private.manage_staff_devices(text,jsonb) to authenticated;

create function public.ctfly_staff_devices(p_operation text default 'list',p_data jsonb default '{}')
returns jsonb language sql security invoker set search_path = '' as $$
 select ctfly_private.manage_staff_devices(p_operation,p_data)
$$;
revoke all on function public.ctfly_staff_devices(text,jsonb) from public,anon;
grant execute on function public.ctfly_staff_devices(text,jsonb) to authenticated;

-- Only the signature-verifying Edge Function may issue/consume login challenges.
create function public.ctfly_staff_device_auth(p_operation text,p_device uuid,p_challenge uuid default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare d ctfly_private.staff_devices; c ctfly_private.staff_device_challenges; email text;
begin
 select * into d from ctfly_private.staff_devices where id=p_device for update;
 if d.id is null or not exists(select 1 from public.profiles where id=d.user_id and active and role in ('staff','admin')) then
  raise exception 'Device registration is unavailable. Sign in with email and password to add this phone again.';
 end if;
 if p_operation='challenge' then
  delete from ctfly_private.staff_device_challenges where device_id=p_device and expires_at<now();
  if (select count(*) from ctfly_private.staff_device_challenges where device_id=p_device)>=10 then
   raise exception 'Too many sign-in attempts. Please wait two minutes.';
  end if;
  insert into ctfly_private.staff_device_challenges(device_id,nonce)
   values(p_device,gen_random_uuid()::text||gen_random_uuid()::text) returning * into c;
  return jsonb_build_object('challenge_id',c.id,'nonce',c.nonce);
 elsif p_operation='consume' then
  delete from ctfly_private.staff_device_challenges where id=p_challenge and device_id=p_device and expires_at>now() returning * into c;
  if c.id is null then raise exception 'Fingerprint request expired. Please try again.';end if;
  select u.email into email from auth.users u where u.id=d.user_id;
  return jsonb_build_object('owner',d.user_id,'email',email,'public_key',d.public_key,'nonce',c.nonce);
 elsif p_operation='check' then
  update ctfly_private.staff_devices set last_used_at=now() where id=p_device;
  return jsonb_build_object('owner',d.user_id);
 end if;
 raise exception 'Unsupported device operation.';
end $$;
revoke all on function public.ctfly_staff_device_auth(text,uuid,uuid) from public,anon,authenticated;
grant execute on function public.ctfly_staff_device_auth(text,uuid,uuid) to service_role;
commit;
