-- Keep Supabase Auth for passwords and sessions. Customer-owned foreign keys
-- target public."User".user_id; staff-owned foreign keys target staff.staff_id.
-- Apply after the CTFLY schema and 20261117_split_user_staff_tables.sql.
-- This does not copy passwords.
begin;

-- Rename the existing shared profile-key columns without changing their UUID values.
do $$
begin
 if exists(select 1 from information_schema.columns where table_schema='public' and table_name='User' and column_name='profile_id')
    and not exists(select 1 from information_schema.columns where table_schema='public' and table_name='User' and column_name='user_id') then
  alter table public."User" rename column profile_id to user_id;
 end if;
 if exists(select 1 from information_schema.columns where table_schema='public' and table_name='staff' and column_name='profile_id')
    and not exists(select 1 from information_schema.columns where table_schema='public' and table_name='staff' and column_name='staff_id') then
  alter table public.staff rename column profile_id to staff_id;
 end if;
 if exists(select 1 from information_schema.columns where table_schema='public' and table_name='User' and column_name='id')
    and not exists(select 1 from information_schema.columns where table_schema='public' and table_name='User' and column_name='user_id') then
  alter table public."User" rename column id to user_id;
 end if;
 if exists(select 1 from information_schema.columns where table_schema='public' and table_name='staff' and column_name='id')
    and not exists(select 1 from information_schema.columns where table_schema='public' and table_name='staff' and column_name='staff_id') then
  alter table public.staff rename column id to staff_id;
 end if;
 if not exists(select 1 from information_schema.columns where table_schema='public' and table_name='User' and column_name='user_id') then
  raise exception 'public."User".user_id is missing; run the role-table setup first.';
 end if;
 if not exists(select 1 from information_schema.columns where table_schema='public' and table_name='staff' and column_name='staff_id') then
  raise exception 'public.staff.staff_id is missing; run the role-table setup first.';
 end if;
end $$;

comment on column public."User".user_id is 'Customer ID shared with public.profiles.profile_id and Supabase Auth.';
comment on column public.staff.staff_id is 'Staff ID shared with public.profiles.profile_id and Supabase Auth.';

alter table public."User" enable row level security;
alter table public.staff enable row level security;
drop policy if exists "Users can read their own auth record" on public."User";
drop policy if exists "Customers can read their own role row" on public."User";
create policy "Customers can read their own role row" on public."User"
 for select to authenticated using ((select auth.uid()) = user_id);
drop policy if exists "Staff can read their own role row" on public.staff;
create policy "Staff can read their own role row" on public.staff
 for select to authenticated using ((select auth.uid()) = staff_id);

-- Keep the role rows synchronized from Auth while storing no password hashes.
create or replace function public.sync_auth_role_record(p_id uuid,p_profile_role text) returns void
language plpgsql security definer set search_path = '' as $$
declare auth_user auth.users%rowtype; identity_rows jsonb := '[]'::jsonb;
begin
 select * into auth_user from auth.users where id=p_id;
 if not found then return; end if;
 begin
  select coalesce(jsonb_agg(to_jsonb(i) order by i.created_at),'[]'::jsonb)
  into identity_rows from auth.identities i where i.user_id=p_id;
 exception when others then identity_rows:='[]'::jsonb; end;
 if p_profile_role='customer' then
  delete from public.staff where staff_id=p_id;
  insert into public."User"(
   user_id,aud,role,email,email_confirmed_at,phone,phone_confirmed_at,confirmed_at,
   last_sign_in_at,app_metadata,user_metadata,identities,created_at,updated_at,is_anonymous
  ) values (
   auth_user.id,auth_user.aud,auth_user.role::text,auth_user.email,auth_user.email_confirmed_at,
   auth_user.phone,auth_user.phone_confirmed_at,auth_user.confirmed_at,auth_user.last_sign_in_at,
   coalesce(auth_user.raw_app_meta_data,'{}'::jsonb),coalesce(auth_user.raw_user_meta_data,'{}'::jsonb),
   identity_rows,coalesce(auth_user.created_at,now()),auth_user.updated_at,coalesce(auth_user.is_anonymous,false)
  ) on conflict(user_id) do update set
   aud=excluded.aud,role=excluded.role,email=excluded.email,email_confirmed_at=excluded.email_confirmed_at,
   phone=excluded.phone,phone_confirmed_at=excluded.phone_confirmed_at,confirmed_at=excluded.confirmed_at,
   last_sign_in_at=excluded.last_sign_in_at,app_metadata=excluded.app_metadata,
   user_metadata=excluded.user_metadata,identities=excluded.identities,created_at=excluded.created_at,
   updated_at=excluded.updated_at,is_anonymous=excluded.is_anonymous;
 else
  delete from public."User" where user_id=p_id;
  insert into public.staff(
   staff_id,aud,role,email,email_confirmed_at,phone,phone_confirmed_at,confirmed_at,
   last_sign_in_at,app_metadata,user_metadata,identities,created_at,updated_at,is_anonymous
  ) values (
   auth_user.id,auth_user.aud,auth_user.role::text,auth_user.email,auth_user.email_confirmed_at,
   auth_user.phone,auth_user.phone_confirmed_at,auth_user.confirmed_at,auth_user.last_sign_in_at,
   coalesce(auth_user.raw_app_meta_data,'{}'::jsonb),coalesce(auth_user.raw_user_meta_data,'{}'::jsonb),
   identity_rows,coalesce(auth_user.created_at,now()),auth_user.updated_at,coalesce(auth_user.is_anonymous,false)
  ) on conflict(staff_id) do update set
   aud=excluded.aud,role=excluded.role,email=excluded.email,email_confirmed_at=excluded.email_confirmed_at,
   phone=excluded.phone,phone_confirmed_at=excluded.phone_confirmed_at,confirmed_at=excluded.confirmed_at,
   last_sign_in_at=excluded.last_sign_in_at,app_metadata=excluded.app_metadata,
   user_metadata=excluded.user_metadata,identities=excluded.identities,created_at=excluded.created_at,
   updated_at=excluded.updated_at,is_anonymous=excluded.is_anonymous;
 end if;
end $$;
revoke all on function public.sync_auth_role_record(uuid,text) from public, anon, authenticated;

create or replace function public.sync_profile_role_tables() returns trigger
language plpgsql security definer set search_path = '' as $$
begin
 if tg_op='UPDATE' and new.role is not distinct from old.role then return new; end if;
 begin
  perform public.sync_auth_role_record(new.profile_id,new.role);
 exception when others then
  -- Signup must still create the Auth user if the mirror copy cannot read auth.users yet.
  if new.role='customer' then
   insert into public."User"(user_id,created_at) values(new.profile_id,now()) on conflict(user_id) do nothing;
  else
   insert into public.staff(staff_id,created_at) values(new.profile_id,now()) on conflict(staff_id) do nothing;
  end if;
 end;
 return new;
end $$;
revoke all on function public.sync_profile_role_tables() from public, anon, authenticated;
drop trigger if exists sync_profile_role_tables on public.profiles;
create trigger sync_profile_role_tables
 after insert or update on public.profiles
 for each row execute function public.sync_profile_role_tables();

create or replace function public.sync_auth_user_role_tables() returns trigger
language plpgsql security definer set search_path = '' as $$
declare profile_role text; user_json jsonb := to_jsonb(new);
begin
 begin
 select role into profile_role from public.profiles where profile_id=new.id;
 if profile_role is null then return new; end if;
 if profile_role='customer' then
  delete from public.staff where staff_id=new.id;
  insert into public."User"(
   user_id,aud,role,email,email_confirmed_at,phone,phone_confirmed_at,confirmed_at,last_sign_in_at,
   app_metadata,user_metadata,created_at,updated_at,is_anonymous
  ) values (
   new.id,user_json->>'aud',user_json->>'role',user_json->>'email',
   nullif(user_json->>'email_confirmed_at','')::timestamptz,user_json->>'phone',
   nullif(user_json->>'phone_confirmed_at','')::timestamptz,nullif(user_json->>'confirmed_at','')::timestamptz,
   nullif(user_json->>'last_sign_in_at','')::timestamptz,
   coalesce(user_json->'raw_app_meta_data','{}'::jsonb),coalesce(user_json->'raw_user_meta_data','{}'::jsonb),
   coalesce(nullif(user_json->>'created_at','')::timestamptz,now()),nullif(user_json->>'updated_at','')::timestamptz,
   coalesce((user_json->>'is_anonymous')::boolean,false)
  ) on conflict(user_id) do update set
   aud=excluded.aud,role=excluded.role,email=excluded.email,email_confirmed_at=excluded.email_confirmed_at,
   phone=excluded.phone,phone_confirmed_at=excluded.phone_confirmed_at,confirmed_at=excluded.confirmed_at,
   last_sign_in_at=excluded.last_sign_in_at,app_metadata=excluded.app_metadata,
   user_metadata=excluded.user_metadata,created_at=excluded.created_at,updated_at=excluded.updated_at,
   is_anonymous=excluded.is_anonymous;
 else
  delete from public."User" where user_id=new.id;
  insert into public.staff(
   staff_id,aud,role,email,email_confirmed_at,phone,phone_confirmed_at,confirmed_at,last_sign_in_at,
   app_metadata,user_metadata,created_at,updated_at,is_anonymous
  ) values (
   new.id,user_json->>'aud',user_json->>'role',user_json->>'email',
   nullif(user_json->>'email_confirmed_at','')::timestamptz,user_json->>'phone',
   nullif(user_json->>'phone_confirmed_at','')::timestamptz,nullif(user_json->>'confirmed_at','')::timestamptz,
   nullif(user_json->>'last_sign_in_at','')::timestamptz,
   coalesce(user_json->'raw_app_meta_data','{}'::jsonb),coalesce(user_json->'raw_user_meta_data','{}'::jsonb),
   coalesce(nullif(user_json->>'created_at','')::timestamptz,now()),nullif(user_json->>'updated_at','')::timestamptz,
   coalesce((user_json->>'is_anonymous')::boolean,false)
  ) on conflict(staff_id) do update set
   aud=excluded.aud,role=excluded.role,email=excluded.email,email_confirmed_at=excluded.email_confirmed_at,
   phone=excluded.phone,phone_confirmed_at=excluded.phone_confirmed_at,confirmed_at=excluded.confirmed_at,
   last_sign_in_at=excluded.last_sign_in_at,app_metadata=excluded.app_metadata,
   user_metadata=excluded.user_metadata,created_at=excluded.created_at,updated_at=excluded.updated_at,
   is_anonymous=excluded.is_anonymous;
 end if;
 exception when others then null;
 end;
 return new;
end $$;
revoke all on function public.sync_auth_user_role_tables() from public, anon, authenticated;
drop trigger if exists z_sync_auth_user_role_tables on auth.users;
create trigger z_sync_auth_user_role_tables
 after insert or update on auth.users
 for each row execute function public.sync_auth_user_role_tables();

create or replace function public.sync_auth_identity_role_tables() returns trigger
language plpgsql security definer set search_path = '' as $$
declare owner_id uuid; identity_json jsonb;
begin
 if tg_op='DELETE' then owner_id:=old.user_id; identity_json:='[]'::jsonb;
 else owner_id:=new.user_id; identity_json:=jsonb_build_array(to_jsonb(new)); end if;
 begin
  update public."User" set identities=identity_json where user_id=owner_id;
  update public.staff set identities=identity_json where staff_id=owner_id;
 exception when others then null;
 end;
 if tg_op='DELETE' then return old; end if;
 return new;
end $$;
revoke all on function public.sync_auth_identity_role_tables() from public, anon, authenticated;
drop trigger if exists z_sync_auth_identity_role_tables on auth.identities;
create trigger z_sync_auth_identity_role_tables
 after insert or update or delete on auth.identities
 for each row execute function public.sync_auth_identity_role_tables();


-- Update stored functions for the profile_id key while preserving the app's JSON id field.
do $$
declare fn record; definition text; original_definition text;
begin
 for fn in
  select p.oid from pg_proc p join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f' and n.nspname in ('public','private','ctfly_private')
   and p.prosrc like '%profiles%'
 loop
  definition:=pg_get_functiondef(fn.oid);
  original_definition:=definition;
  definition:=replace(definition,'me.id','me.profile_id');
  definition:=replace(definition,'from profiles where id=','from profiles where profile_id=');
  definition:=replace(definition,'from public.profiles where id=','from public.profiles where profile_id=');
  definition:=replace(definition,'from profiles where id =','from profiles where profile_id =');
  definition:=replace(definition,'from public.profiles where id =','from public.profiles where profile_id =');
  definition:=regexp_replace(definition,
   $pattern$((from|join)[[:space:]]+(public\.)?profiles[[:space:]]+where[[:space:]]+)id([[:space:]]*=)$pattern$,
   $replacement$\1profile_id\4$replacement$,'gi');
  definition:=regexp_replace(definition,
   $pattern$(update[[:space:]]+(public\.)?profiles[[:space:]]+set[[:space:]]+[^;]*?where[[:space:]]+)id([[:space:]]*=)$pattern$,
   $replacement$\1profile_id\3$replacement$,'gi');
  definition:=regexp_replace(definition,
   $pattern$((from|join)[[:space:]]+(public\.)?profiles[[:space:]]+p[[:space:]]+(where|on)[[:space:]]+)p\.id$pattern$,
   $replacement$\1p.profile_id$replacement$,'gi');
  -- Also catch "and p.id" and a join predicate that is not flush against the alias.
  definition:=regexp_replace(definition,
   $pattern$(\m(on|where|and)[[:space:]]+)p\.id\M$pattern$,
   $replacement$\1p.profile_id$replacement$,'gi');
  definition:=replace(definition,'insert into public.profiles(id,','insert into public.profiles(profile_id,');
  definition:=replace(definition,$old$'id',id,'name',name$old$,$new$'id',profile_id,'name',name$new$);
  if fn.oid='public.ctfly_snapshot()'::regprocedure then
   definition:=replace(definition,$old$'profile',to_jsonb(me)$old$,$new$'profile',to_jsonb(me)||jsonb_build_object('id',me.profile_id)$new$);
  end if;
  if definition is distinct from original_definition then execute definition; end if;
 end loop;
end $$;

-- Refill a missing role row from the existing profile role before adding the new FKs.
do $$
declare profile_row record;
begin
 for profile_row in select profile_id,role from public.profiles loop
  perform public.sync_auth_role_record(profile_row.profile_id,profile_row.role);
 end loop;
end $$;

-- Normalize legacy staff-owned user_id columns to staff_id before rewiring their FKs.
-- This is a no-op when the columns have already been renamed by the project owner.
do $$
declare item record; old_att smallint; new_att smallint; old_assigned_att smallint; new_assigned_att smallint;
begin
 for item in select * from (values
  ('public.shifts'::regclass,'shifts'),
  ('public.leave_requests'::regclass,'leave_requests'),
  ('public.tasks'::regclass,'tasks'),
  ('public.checklist'::regclass,'checklist'),
  ('public.handovers'::regclass,'handovers'),
  ('public.handover_reads'::regclass,'handover_reads'),
  ('public.tickets'::regclass,'tickets'),
  ('ctfly_private.staff_devices'::regclass,'staff_devices')
 ) as tables(table_oid,table_name)
 loop
  select attnum into old_att from pg_attribute where attrelid=item.table_oid and attname='user_id' and not attisdropped;
  select attnum into new_att from pg_attribute where attrelid=item.table_oid and attname='staff_id' and not attisdropped;
  if new_att is null and old_att is not null then
   execute format('alter table %s rename column user_id to staff_id',item.table_oid);
  elsif new_att is null then
   raise exception 'Missing staff key column on %; expected staff_id (or legacy user_id).',item.table_name;
  end if;
 end loop;
 select attnum into old_assigned_att from pg_attribute where attrelid='public.tickets'::regclass and attname='assigned_user' and not attisdropped;
 select attnum into new_assigned_att from pg_attribute where attrelid='public.tickets'::regclass and attname='assigned_staff' and not attisdropped;
 if new_assigned_att is null and old_assigned_att is not null then
  alter table public.tickets rename column assigned_user to assigned_staff;
 elsif new_assigned_att is null then
  raise exception 'Missing public.tickets.assigned_staff (or legacy assigned_user).';
 end if;
end $$;

-- Customer data now points to User.user_id. Staff-owned data points to staff.staff_id.
-- Existing UUID values are unchanged; only the FK target is changed.
do $$
declare link record; child_att smallint; target_att smallint; old_fk record;
begin
 for link in
  select * from (values
   ('public.bookings'::regclass,'user_id','public."User"'::regclass,'user_id','bookings_user_role_fk',''),
   ('public.orders'::regclass,'user_id','public."User"'::regclass,'user_id','orders_user_role_fk',''),
   ('public.registrations'::regclass,'user_id','public."User"'::regclass,'user_id','registrations_user_role_fk',''),
   ('public.bills'::regclass,'user_id','public."User"'::regclass,'user_id','bills_user_role_fk',''),
   ('public.redemptions'::regclass,'user_id','public."User"'::regclass,'user_id','redemptions_user_role_fk',''),
   ('public.points_transactions'::regclass,'user_id','public."User"'::regclass,'user_id','points_user_role_fk',''),
   ('public.favorites'::regclass,'user_id','public."User"'::regclass,'user_id','favorites_user_role_fk',''),
   ('public.app_feedback'::regclass,'user_id','public."User"'::regclass,'user_id','feedback_user_role_fk',''),
   ('public.test_payments'::regclass,'user_id','public."User"'::regclass,'user_id','test_payments_user_role_fk',''),
   ('public.promotions'::regclass,'target_user','public."User"'::regclass,'user_id','promotions_target_user_role_fk',''),
   ('public.promo_claims'::regclass,'user_id','public."User"'::regclass,'user_id','promo_claims_user_role_fk',''),
   ('public.receipt_email_jobs'::regclass,'user_id','public."User"'::regclass,'user_id','receipt_jobs_user_role_fk',''),
   ('public.order_notifications'::regclass,'user_id','public."User"'::regclass,'user_id','order_notices_user_role_fk',''),
   ('private.user_push_devices'::regclass,'user_id','public."User"'::regclass,'user_id','push_devices_user_role_fk',''),
   ('private.order_push_jobs'::regclass,'user_id','public."User"'::regclass,'user_id','push_jobs_user_role_fk',''),
   ('public.shifts'::regclass,'staff_id','public.staff'::regclass,'staff_id','shifts_staff_role_fk',''),
   ('public.leave_requests'::regclass,'staff_id','public.staff'::regclass,'staff_id','leave_staff_role_fk',''),
   ('public.tasks'::regclass,'staff_id','public.staff'::regclass,'staff_id','tasks_staff_role_fk',''),
   ('public.checklist'::regclass,'staff_id','public.staff'::regclass,'staff_id','checklist_staff_role_fk',''),
   ('public.handovers'::regclass,'staff_id','public.staff'::regclass,'staff_id','handovers_staff_role_fk',''),
   ('public.handover_reads'::regclass,'staff_id','public.staff'::regclass,'staff_id','handover_reads_staff_role_fk',''),
   ('public.tickets'::regclass,'staff_id','public.staff'::regclass,'staff_id','tickets_staff_role_fk',''),
   ('public.tickets'::regclass,'assigned_staff','public.staff'::regclass,'staff_id','tickets_assignee_staff_fk',''),
   ('public.app_feedback'::regclass,'updated_by','public.staff'::regclass,'staff_id','feedback_updated_by_staff_fk',''),
   ('public.events'::regclass,'created_by','public.staff'::regclass,'staff_id','events_created_by_staff_fk',''),
   ('public.promotions'::regclass,'created_by','public.staff'::regclass,'staff_id','promotions_created_by_staff_fk',''),
   ('public.orders'::regclass,'received_by','public.staff'::regclass,'staff_id','orders_received_by_staff_fk',''),
   ('ctfly_private.staff_devices'::regclass,'staff_id','public.staff'::regclass,'staff_id','devices_staff_role_fk',' on delete cascade')
  ) as links(source_table,source_column,target_table,target_column,constraint_name,on_delete)
 loop
  select attnum into child_att from pg_attribute where attrelid=link.source_table and attname=link.source_column and not attisdropped;
  select attnum into target_att from pg_attribute where attrelid=link.target_table and attname=link.target_column and not attisdropped;
  if child_att is null then raise exception 'Missing source FK column %.%',link.source_table,link.source_column; end if;
  if target_att is null then raise exception 'Missing referenced FK column %.%',link.target_table,link.target_column; end if;
  for old_fk in
   select conname from pg_constraint where conrelid=link.source_table and contype='f'
    and confrelid='public.profiles'::regclass and conkey=ARRAY[child_att]::smallint[]
  loop
   execute format('alter table %s drop constraint %I',link.source_table,old_fk.conname);
  end loop;
  if not exists(select 1 from pg_constraint where conrelid=link.source_table and contype='f'
   and confrelid=link.target_table and conkey=ARRAY[child_att]::smallint[]
   and confkey=ARRAY[target_att]::smallint[]) then
   execute format('alter table %s add constraint %I foreign key (%I) references %s (%I)%s',
    link.source_table,link.constraint_name,link.source_column,link.target_table,link.target_column,link.on_delete);
  end if;
 end loop;
end $$;

-- Use the role-table keys for authenticated customer/staff API eligibility.
-- Some installations already have an aliased profile query in these two functions,
-- so gate the selected profile row instead of matching the full SELECT text exactly.
do $$
declare signature text; target regprocedure; definition text; updated_definition text;
begin
 for signature in select unnest(array[
  'public.ctfly_snapshot()',
  'public.ctfly_action(text,jsonb,uuid)'
 ]) loop
  target:=to_regprocedure(signature);
  if target is null then raise exception 'Required function is missing: %',signature; end if;
  definition:=pg_get_functiondef(target);
  if position('public."User"' in definition)>0
     and position('public.staff' in definition)>0
     and position('user_id' in definition)>0
     and position('staff_id' in definition)>0 then
   continue;
  end if;
  updated_definition:=regexp_replace(definition,
   'if[[:space:]]+me\.(profile_id|id)[[:space:]]+is[[:space:]]+null[[:space:]]+then',
   'if me.profile_id is null or not ((me.role = ''customer'' and exists (select 1 from public."User" u where u.user_id = me.profile_id)) or (me.role in (''staff'', ''admin'') and exists (select 1 from public.staff s where s.staff_id = me.profile_id))) then',
   'gi');
  if updated_definition is not distinct from definition then
   raise exception 'Could not add role-table eligibility to %; inspect its profile null check before applying this update.',signature;
  end if;
  execute updated_definition;
 end loop;
end $$;

-- Recreate the quote function with the current staff-table eligibility check.
-- Replacing the whole small RPC avoids depending on the deployed function's whitespace or aliases.
create or replace function public.ctfly_quote(p_kind text,p_id uuid) returns jsonb
language plpgsql security definer set search_path=public,pg_temp as $$
declare uid uuid; amount int; spent_total int; discount_total int;
begin
 if not exists(select 1 from public.profiles p where p.profile_id=auth.uid() and p.active
  and p.role in ('staff','admin') and exists(select 1 from public.staff s where s.staff_id=p.profile_id)) then
  raise exception 'Staff access required.';
 end if;
 if p_kind='Booking' then select user_id,total into uid,amount from public.bookings where id=p_id and status='Reserved' and not paid;
 elsif p_kind='Order' then select user_id,total into uid,amount from public.orders where id=p_id and status<>'Cancelled' and not paid;
 elsif p_kind='Event' then select user_id,fee into uid,amount from public.registrations where id=p_id and status='Registered' and not paid;
 elsif p_kind in ('Extension30','Extension60') then
  select b.user_id,(r.rate*(case when p_kind='Extension30' then 30 else 60 end)+30)/60 into uid,amount
  from public.bookings b join public.resources r on r.id=b.resource_id
  where b.id=p_id and b.paid and b.status in ('Confirmed','Active') and b.ends_at>now();
 end if;
 if uid is null then raise exception 'Already paid or no longer payable.'; end if;
 select spent into spent_total from public.profiles where profile_id=uid;
 discount_total:=amount*(case when spent_total>=100000 then 10 when spent_total>=30000 then 5 else 0 end)/100;
 return jsonb_build_object('id',p_id,'kind',p_kind,'subtotal',amount,'discount',discount_total,'total',amount-discount_total);
end $$;

-- Apply the remaining staff guards and user-table email lookup without exact-source matching.
do $$
declare signature text; target regprocedure; definition text; original_definition text; updated_definition text;
begin
 for signature in select unnest(array[
  'public.ctfly_staff_history(text,date,text,text,timestamptz,text)',
  'public.ctfly_staff_transactions(date,text,text,text,timestamptz,text)',
  'ctfly_private.manage_staff_devices(uuid,text,jsonb)',
  'public.ctfly_staff_device_auth(text,uuid,uuid)',
  'private.receipt_worker(text,text,jsonb)'
 ]) loop
  target:=to_regprocedure(signature);
  if target is null then raise exception 'Required function is missing: %',signature; end if;
  definition:=pg_get_functiondef(target);
  original_definition:=definition;
  if signature='public.ctfly_staff_history(text,date,text,text,timestamptz,text)' then
   -- Add an independent guard at function entry. This works across older
   -- profile aliases and avoids assuming the existing guard's exact text.
   if position('CTFLY_STAFF_TABLE_GUARD' in definition)=0 then
    definition:=regexp_replace(definition,
     '([[:space:]])begin([[:space:]])',
     $replacement$\1begin
 -- CTFLY_STAFF_TABLE_GUARD
 if not exists(select 1 from public.profiles p where p.profile_id=auth.uid() and p.active and p.role in ('staff','admin') and exists(select 1 from public.staff s where s.staff_id=p.profile_id)) then
  raise exception 'Staff access required.';
 end if;
\2$replacement$,
     'i');
   end if;
  elsif signature='public.ctfly_staff_transactions(date,text,text,text,timestamptz,text)' then
   if position('public.staff' in definition)=0 or position('staff_id' in definition)=0 then
    definition:=regexp_replace(definition,
     $pattern$where[[:space:]]+(p\.)?profile_id[[:space:]]*=[[:space:]]*auth.uid\(\)[[:space:]]+and[[:space:]]+(p\.)?active[[:space:]]+and[[:space:]]+(p\.)?role[[:space:]]+in[[:space:]]*\([[:space:]]*'staff'[[:space:]]*,[[:space:]]*'admin'[[:space:]]*\)$pattern$,
     $replacement$where profile_id = auth.uid() and active and role in ('staff', 'admin') and exists(select 1 from public.staff s where s.staff_id=auth.uid())$replacement$,
     'gi');
   end if;
  elsif signature='ctfly_private.manage_staff_devices(uuid,text,jsonb)' then
   if position('public.staff' in definition)=0 or position('staff_id' in definition)=0 then
    definition:=regexp_replace(definition,
     $pattern$perform[[:space:]]+1[[:space:]]+from[[:space:]]+(public\.)?profiles([[:space:]]+[a-z_][a-z_0-9]*)?[[:space:]]+where[[:space:]]+[^;]*profile_id[[:space:]]*=[[:space:]]*p_owner[[:space:]]+and[[:space:]]+active[[:space:]]+and[[:space:]]+role[[:space:]]+in[[:space:]]*\([[:space:]]*'staff'[[:space:]]*,[[:space:]]*'admin'[[:space:]]*\)[[:space:]]+for[[:space:]]+update;$pattern$,
     $replacement$perform 1 from public.profiles p where p.profile_id=p_owner and p.active and p.role in ('staff','admin') and exists(select 1 from public.staff s where s.staff_id=p.profile_id) for update;$replacement$,
     'gi');
   end if;
  elsif signature='public.ctfly_staff_device_auth(text,uuid,uuid)' then
   if position('public.staff' in definition)=0 or position('staff_id' in definition)=0 then
    definition:=regexp_replace(definition,
     $pattern$if[[:space:]]+d\.id[[:space:]]+is[[:space:]]+null[[:space:]]+or[[:space:]]+not[[:space:]]+exists[[:space:]]*\([[:space:]]*select[[:space:]]+1[[:space:]]+from[[:space:]]+(public\.)?profiles([[:space:]]+[a-z_][a-z_0-9]*)?[[:space:]]+where[[:space:]]+[^;]*role[[:space:]]+in[[:space:]]*\([[:space:]]*'staff'[[:space:]]*,[[:space:]]*'admin'[[:space:]]*\)[[:space:]]*\)[[:space:]]*then$pattern$,
     $replacement$if d.id is null or not exists(select 1 from public.profiles p where p.profile_id=d.user_id and p.active and p.role in ('staff','admin') and exists(select 1 from public.staff s where s.staff_id=p.profile_id)) then$replacement$,
     'gi');
   end if;
   if position('select s.email into email from public.staff' in definition)=0 then
    definition:=regexp_replace(definition,
     $pattern$select[[:space:]]+u\.email[[:space:]]+into[[:space:]]+email[[:space:]]+from[[:space:]]+auth\.users[[:space:]]+u[[:space:]]+where[[:space:]]+u\.id[[:space:]]*=[[:space:]]*d\.(user_id|staff_id)[[:space:]]*;$pattern$,
     $replacement$select s.email into email from public.staff s where s.staff_id=d.staff_id;$replacement$,
     'gi');
   end if;
  elsif signature='private.receipt_worker(text,text,jsonb)' then
   if position('join public."User" u on u.user_id=j.user_id' in definition)=0 then
    definition:=regexp_replace(definition,
     $pattern$join[[:space:]]+auth\.users[[:space:]]+u[[:space:]]+on[[:space:]]+u\.id[[:space:]]*=[[:space:]]*j\.user_id$pattern$,
     $replacement$join public."User" u on u.user_id=j.user_id$replacement$,
     'gi');
   end if;
  end if;
  if definition is distinct from original_definition then execute definition; end if;
  if signature='public.ctfly_staff_history(text,date,text,text,timestamptz,text)'
     and position('CTFLY_STAFF_TABLE_GUARD' in definition)=0
     and position('exists(select 1 from public.staff s where s.staff_id=p.profile_id)' in definition)=0 then
   raise exception 'Could not add staff-table eligibility to %; inspect its function body.',signature;
  end if;
 end loop;
end $$;

do $$
declare change record; definition text; target regprocedure; already_updated boolean;
begin
 for change in
  select * from (values
   ('public.ctfly_staff_transactions(date,text,text,text,timestamptz,text)'::text,
    $old$where profile_id = auth.uid() and active and role in ('staff', 'admin')$old$,
    $new$where profile_id = auth.uid() and active and role in ('staff', 'admin') and exists(select 1 from public.staff s where s.staff_id=auth.uid())$new$),
   ('ctfly_private.manage_staff_devices(uuid,text,jsonb)'::text,
    $old$perform 1 from public.profiles where profile_id=p_owner and active and role in ('staff','admin') for update;$old$,
    $new$perform 1 from public.profiles p where p.profile_id=p_owner and p.active and p.role in ('staff','admin') and exists(select 1 from public.staff s where s.staff_id=p.profile_id) for update;$new$),
   ('public.ctfly_staff_device_auth(text,uuid,uuid)'::text,
    $old$if d.id is null or not exists(select 1 from public.profiles where profile_id=d.user_id and active and role in ('staff','admin')) then$old$,
    $new$if d.id is null or not exists(select 1 from public.profiles p where p.profile_id=d.user_id and p.active and p.role in ('staff','admin') and exists(select 1 from public.staff s where s.staff_id=p.profile_id)) then$new$),
   ('public.ctfly_staff_device_auth(text,uuid,uuid)'::text,
    $old$select u.email into email from auth.users u where u.id=d.user_id;$old$,
    $new$select s.email into email from public.staff s where s.staff_id=d.user_id;$new$),
   ('private.receipt_worker(text,text,jsonb)'::text,
    $old$join auth.users u on u.id=j.user_id;$old$,
    $new$join public."User" u on u.user_id=j.user_id;$new$)
  ) as changes(signature,old_source,new_source)
 loop
  target:=to_regprocedure(change.signature);
  if target is null then raise exception 'Required function is missing: %',change.signature;end if;
  definition:=pg_get_functiondef(target);
  already_updated:=case
   when change.signature in (
    'public.ctfly_staff_transactions(date,text,text,text,timestamptz,text)',
    'ctfly_private.manage_staff_devices(uuid,text,jsonb)'
   ) then position('public.staff' in definition)>0 and position('staff_id' in definition)>0
   when change.signature='public.ctfly_staff_device_auth(text,uuid,uuid)' and change.old_source like 'select u.email%'
    then position('select s.email into email from public.staff' in definition)>0
   when change.signature='public.ctfly_staff_device_auth(text,uuid,uuid)'
    then position('public.staff' in definition)>0 and position('staff_id' in definition)>0
   when change.signature='private.receipt_worker(text,text,jsonb)'
    then position('public."User"' in definition)>0 and position('user_id' in definition)>0
   else false
  end;
  if position(change.new_source in definition)=0 and not already_updated then
   if position(change.old_source in definition)=0 then raise exception 'Expected source fragment not found in %',change.signature;end if;
   execute replace(definition,change.old_source,change.new_source);
  end if;
 end loop;
end $$;

-- Update stored RPC source after staff-owned columns were renamed from user_id to staff_id.
-- Keep accepting the legacy RPC payload key while the app transitions to staff_id.
do $$
declare signature text; target regprocedure; definition text; original_definition text;
begin
 for signature in select unnest(array[
  'public.ctfly_snapshot()',
  'public.ctfly_action(text,jsonb,uuid)',
  'public.ctfly_staff_history(text,date,text,text,timestamptz,text)',
  'ctfly_private.manage_staff_devices(uuid,text,jsonb)',
  'public.ctfly_staff_device_auth(text,uuid,uuid)'
 ]) loop
  target:=to_regprocedure(signature);
  if target is null then continue; end if;
  definition:=pg_get_functiondef(target);
  original_definition:=definition;
  if signature='public.ctfly_snapshot()' then
   definition:=regexp_replace(definition,'(from[[:space:]]+shifts[[:space:]]+s[[:space:]]+where[[:space:]]+)user_id','\1staff_id','gi');
   definition:=replace(definition,'s.user_id=me.profile_id','s.staff_id=me.profile_id');
   definition:=regexp_replace(definition,'(from[[:space:]]+leave_requests[[:space:]]+l[[:space:]]+where[[:space:]]+)user_id','\1staff_id','gi');
   definition:=regexp_replace(definition,'(from[[:space:]]+tasks[[:space:]]+t[[:space:]]+where[[:space:]]+)user_id','\1staff_id','gi');
   definition:=regexp_replace(definition,'(from[[:space:]]+checklist[[:space:]]+c[[:space:]]+where[[:space:]]+)user_id','\1staff_id','gi');
   definition:=regexp_replace(definition,'(from[[:space:]]+handover_reads[[:space:]]+h[[:space:]]+where[[:space:]]+)user_id','\1staff_id','gi');
  elsif signature='public.ctfly_action(text,jsonb,uuid)' then
   definition:=regexp_replace(definition,'(from[[:space:]]+shifts[[:space:]]+where[[:space:]]+id[[:space:]]*=[^;]*[[:space:]]+and[[:space:]]+)user_id','\1staff_id','gi');
   definition:=regexp_replace(definition,'(insert[[:space:]]+into[[:space:]]+leave_requests[[:space:]]*\([[:space:]]*)user_id','\1staff_id','gi');
   definition:=regexp_replace(definition,'(insert[[:space:]]+into[[:space:]]+tasks[[:space:]]*\([[:space:]]*)user_id','\1staff_id','gi');
   definition:=replace(definition,'and (user_id=me.profile_id','and (staff_id=me.profile_id');
   definition:=replace(definition,'c.user_id=me.profile_id','c.staff_id=me.profile_id');
   definition:=regexp_replace(definition,
    'insert[[:space:]]+into[[:space:]]+checklist[[:space:]]+values[[:space:]]*\([[:space:]]*me\.profile_id[[:space:]]*,',
    'insert into checklist(checklist_id,staff_id,day,item) values(gen_random_uuid(),me.profile_id,',
    'gi');
   definition:=regexp_replace(definition,'(insert[[:space:]]+into[[:space:]]+handovers[[:space:]]*\([[:space:]]*)user_id','\1staff_id','gi');
   definition:=regexp_replace(definition,'(from[[:space:]]+handovers[[:space:]]+where[^;]*[[:space:]]+)user_id','\1staff_id','gi');
   definition:=regexp_replace(definition,'(insert[[:space:]]+into[[:space:]]+tickets[[:space:]]*\([[:space:]]*)user_id','\1staff_id','gi');
   definition:=replace(definition,'tk.user_id','tk.staff_id');
   definition:=regexp_replace(definition,'(from[[:space:]]+leave_requests[[:space:]]+where[[:space:]]+)user_id','\1staff_id','gi');
   definition:=regexp_replace(definition,'(insert[[:space:]]+into[[:space:]]+shifts[[:space:]]*\([[:space:]]*)user_id','\1staff_id','gi');
   definition:=replace(definition,'l.user_id=s.user_id','l.staff_id=s.staff_id');
   definition:=replace(definition,'assigned_user','assigned_staff');
   definition:=regexp_replace(definition,
    $pattern$coalesce\(nullif\(p_data->>'user_id',[[:space:]]*''\)::uuid,[[:space:]]*me.profile_id\)$pattern$,
    $replacement$coalesce(nullif(p_data->>'staff_id','')::uuid,nullif(p_data->>'user_id','')::uuid,me.profile_id)$replacement$,
    'gi');
   definition:=regexp_replace(definition,
    $pattern$uid[[:space:]]*:=[[:space:]]*\(p_data->>'user_id'\)::uuid;$pattern$,
    $replacement$uid:=coalesce(nullif(p_data->>'staff_id','')::uuid,nullif(p_data->>'user_id','')::uuid);$replacement$,
    'gi');
  elsif signature='public.ctfly_staff_history(text,date,text,text,timestamptz,text)' then
   definition:=replace(definition,'c.user_id=auth.uid()','c.staff_id=auth.uid()');
  elsif signature='ctfly_private.manage_staff_devices(uuid,text,jsonb)' then
   definition:=replace(definition,'user_id','staff_id');
  elsif signature='public.ctfly_staff_device_auth(text,uuid,uuid)' then
   definition:=replace(definition,'d.user_id','d.staff_id');
  end if;
  if definition is distinct from original_definition then execute definition; end if;
 end loop;
end $$;

notify pgrst, 'reload schema';
commit;
