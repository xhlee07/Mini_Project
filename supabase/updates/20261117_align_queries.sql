-- Point every saved function at the current CTFLY columns.
-- profiles.profile_id, public."User".user_id, staff-owned staff_id, tickets.assigned_staff.
-- Customer tables keep user_id. Promotion rows keep p.id.
-- auth.users still has id; public."User" does not.
-- Customer tables keep user_id. Staff-owned tables use staff_id.
begin;

-- Rename leftover key columns on every table. Values are unchanged.
do $$
declare
 item record;
 old_att smallint;
 new_att smallint;
begin
 if to_regclass('public.profiles') is not null
    and exists(select 1 from pg_attribute where attrelid='public.profiles'::regclass and attname='id' and not attisdropped)
    and not exists(select 1 from pg_attribute where attrelid='public.profiles'::regclass and attname='profile_id' and not attisdropped) then
  alter table public.profiles rename column id to profile_id;
 end if;

 if to_regclass('public."User"') is not null
    and not exists(select 1 from pg_attribute where attrelid='public."User"'::regclass and attname='user_id' and not attisdropped) then
  if exists(select 1 from pg_attribute where attrelid='public."User"'::regclass and attname='id' and not attisdropped) then
   alter table public."User" rename column id to user_id;
  elsif exists(select 1 from pg_attribute where attrelid='public."User"'::regclass and attname='profile_id' and not attisdropped) then
   alter table public."User" rename column profile_id to user_id;
  end if;
 end if;

 if to_regclass('public.staff') is not null
    and not exists(select 1 from pg_attribute where attrelid='public.staff'::regclass and attname='staff_id' and not attisdropped) then
  if exists(select 1 from pg_attribute where attrelid='public.staff'::regclass and attname='id' and not attisdropped) then
   alter table public.staff rename column id to staff_id;
  elsif exists(select 1 from pg_attribute where attrelid='public.staff'::regclass and attname='profile_id' and not attisdropped) then
   alter table public.staff rename column profile_id to staff_id;
  end if;
 end if;

 for item in
  select * from (values
   ('public','shifts'),
   ('public','leave_requests'),
   ('public','tasks'),
   ('public','checklist'),
   ('public','handovers'),
   ('public','handover_reads'),
   ('public','tickets'),
   ('ctfly_private','staff_devices')
  ) as staff_tables(schema_name,table_name)
 loop
  if to_regclass(format('%I.%I', item.schema_name, item.table_name)) is null then continue; end if;
  select attnum into old_att from pg_attribute
   where attrelid=to_regclass(format('%I.%I', item.schema_name, item.table_name)) and attname='user_id' and not attisdropped;
  select attnum into new_att from pg_attribute
   where attrelid=to_regclass(format('%I.%I', item.schema_name, item.table_name)) and attname='staff_id' and not attisdropped;
  if new_att is null and old_att is not null then
   execute format('alter table %I.%I rename column user_id to staff_id', item.schema_name, item.table_name);
  end if;
 end loop;

 if to_regclass('public.tickets') is not null
    and exists(select 1 from pg_attribute where attrelid='public.tickets'::regclass and attname='assigned_user' and not attisdropped)
    and not exists(select 1 from pg_attribute where attrelid='public.tickets'::regclass and attname='assigned_staff' and not attisdropped) then
  alter table public.tickets rename column assigned_user to assigned_staff;
 end if;
end $$;

do $$
declare
 fn record;
 definition text;
 original_definition text;
 updated text;
 pass int;
 checklist_has_id boolean;
begin
 select exists(
  select 1 from information_schema.columns
  where table_schema='public' and table_name='checklist' and column_name='checklist_id'
 ) into checklist_has_id;

 for fn in
  select p.oid
  from pg_proc p
  join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f' and n.nspname in ('public','private','ctfly_private')
 loop
  definition:=pg_get_functiondef(fn.oid);
  original_definition:=definition;

  definition:=regexp_replace(definition,'\mme\.id\M','me.profile_id','g');
  definition:=regexp_replace(definition,
   '((from|join)[[:space:]]+(public\.)?profiles[[:space:]]+where[[:space:]]+)id\M',
   '\1profile_id','gi');
  definition:=regexp_replace(definition,
   '(update[[:space:]]+(public\.)?profiles[[:space:]]+set[[:space:]]+[^;]*?where[[:space:]]+)id\M',
   '\1profile_id','gi');
  -- Every profiles alias p.id, including "(p.id" and "p.id =".
  -- Promotion rows are the only p.id that must stay.
  definition:=replace(definition,'promotion_id=p.id','promotion_id=p.__keep_id__');
  definition:=replace(definition,'promotion_id = p.id','promotion_id = p.__keep_id__');
  definition:=regexp_replace(definition,'\mp\.id\M','p.profile_id','gi');
  definition:=replace(definition,'p.__keep_id__','p.id');
  definition:=replace(definition,'P.__keep_id__','p.id');
  definition:=replace(definition,'insert into public.profiles(id,','insert into public.profiles(profile_id,');
  definition:=replace(definition,'insert into profiles(id,','insert into profiles(profile_id,');
  definition:=replace(definition,'(''id'',id,''name'',name','(''id'',profile_id,''name'',name');
  definition:=replace(definition,'(id=bills.user_id','(profile_id=bills.user_id');
  definition:=replace(definition,'(id = bills.user_id','(profile_id = bills.user_id');
  definition:=replace(definition,
   'where profile_id=(p_data->>''user_id'')::uuid',
   'where profile_id=coalesce(nullif(p_data->>''profile_id'','''')::uuid,nullif(p_data->>''user_id'','''')::uuid)');
  -- public."User" is aliased as u and its key is user_id, not id.
  definition:=regexp_replace(definition,
   'join[[:space:]]+auth\.users[[:space:]]+u[[:space:]]+on[[:space:]]+u\.id\M',
   'join public."User" u on u.user_id','gi');
  definition:=regexp_replace(definition,
   'select[[:space:]]+u\.email[[:space:]]+into[[:space:]]+email[[:space:]]+from[[:space:]]+auth\.users[[:space:]]+u[[:space:]]+where[[:space:]]+u\.id[[:space:]]*=[[:space:]]*d\.(user_id|staff_id)',
   'select s.email into email from public.staff s where s.staff_id=d.staff_id','gi');
  -- public."User".id is user_id. auth.users.id stays id, so only rewrite u.id
  -- in the same statement as public."User" u.
  for pass in 1..40 loop
   updated:=regexp_replace(definition,
    '(public\."User"[[:space:]]+u\M[^;]{0,255}?)u\.id\M','\1u.user_id','i');
   exit when updated=definition;
   definition:=updated;
  end loop;
  if definition !~* 'auth\.users[[:space:]]+u\M' then
   definition:=regexp_replace(definition,'\mu\.id\M','u.user_id','g');
  end if;
  definition:=regexp_replace(definition,'\m"User"\.id\M','"User".user_id','g');

  -- public.staff.id is staff_id. shifts.id still exists, so do not rewrite
  -- "join shifts s on s.id".
  for pass in 1..40 loop
   updated:=regexp_replace(definition,
    '(public\.staff[[:space:]]+s\M[^;]{0,255}?)s\.id\M','\1s.staff_id','i');
   exit when updated=definition;
   definition:=updated;
  end loop;
  definition:=regexp_replace(definition,
   '\ms\.id[[:space:]]*=[[:space:]]*(auth\.uid\(\)|p\.profile_id|me\.profile_id|p_owner\M|owner_id\M|new\.id|u\.user_id)',
   's.staff_id=\1','gi');
  definition:=regexp_replace(definition,
   '((from|join)[[:space:]]+(public\.)?staff[[:space:]]+where[[:space:]]+)id\M',
   '\1staff_id','gi');
  definition:=regexp_replace(definition,'\mstaff\.id\M','staff.staff_id','gi');

  if position('jsonb_build_object(''id'',me.profile_id)' in definition)=0 then
   definition:=replace(definition,
    '''profile'',to_jsonb(me)',
    '''profile'',to_jsonb(me)||jsonb_build_object(''id'',me.profile_id)');
  end if;

  definition:=replace(definition,'l.user_id=s.user_id','l.staff_id=s.staff_id');
  definition:=replace(definition,'l.staff_id=s.user_id','l.staff_id=s.staff_id');
  definition:=replace(definition,'s.user_id=','s.staff_id=');
  definition:=replace(definition,'from shifts s where user_id=','from shifts s where staff_id=');
  definition:=replace(definition,'from leave_requests l where user_id=','from leave_requests l where staff_id=');
  definition:=replace(definition,'from leave_requests where user_id=','from leave_requests where staff_id=');
  definition:=replace(definition,'from tasks t where user_id=','from tasks t where staff_id=');
  definition:=replace(definition,'from checklist c where user_id=','from checklist c where staff_id=');
  definition:=replace(definition,'from handover_reads h where user_id=','from handover_reads h where staff_id=');
  definition:=replace(definition,'insert into leave_requests(user_id,','insert into leave_requests(staff_id,');
  definition:=replace(definition,'insert into tasks(user_id,','insert into tasks(staff_id,');
  definition:=replace(definition,'insert into handovers(user_id,','insert into handovers(staff_id,');
  definition:=replace(definition,'insert into tickets(user_id,','insert into tickets(staff_id,');
  definition:=replace(definition,'insert into shifts(user_id,','insert into shifts(staff_id,');
  definition:=replace(definition,'and (user_id=me.','and (staff_id=me.');
  definition:=replace(definition,'c.user_id=me.','c.staff_id=me.');
  definition:=replace(definition,'c.user_id=auth.uid()','c.staff_id=auth.uid()');
  definition:=replace(definition,'tk.user_id','tk.staff_id');
  definition:=replace(definition,'assigned_user','assigned_staff');
  definition:=regexp_replace(definition,
   '\m(shifts|leave_requests|tasks|checklist|handovers|handover_reads|tickets|staff_devices)\.user_id\M',
   '\1.staff_id','gi');
  definition:=regexp_replace(definition,'\mprofiles\.id\M','profiles.profile_id','gi');
  definition:=replace(definition,'and user_id<>','and staff_id<>');
  definition:=regexp_replace(definition,
   '(from[[:space:]]+shifts[[:space:]]+where[[:space:]]+id[[:space:]]*=[^;]*[[:space:]]+and[[:space:]]+)user_id',
   '\1staff_id','gi');
  definition:=regexp_replace(definition,
   '(from[[:space:]]+handovers[[:space:]]+where[[:space:]]+id[[:space:]]*=[^;]*[[:space:]]+and[[:space:]]+)user_id',
   '\1staff_id','gi');

  if position('staff_devices' in definition)>0 then
   definition:=replace(definition,'d.user_id','d.staff_id');
   definition:=replace(definition,'staff_devices.user_id','staff_devices.staff_id');
   definition:=replace(definition,'staff_devices(id,user_id','staff_devices(id,staff_id');
   definition:=replace(definition,'where user_id=p_owner','where staff_id=p_owner');
   definition:=replace(definition,'where user_id<>p_owner','where staff_id<>p_owner');
   definition:=replace(definition,'and user_id=p_owner','and staff_id=p_owner');
   definition:=replace(definition,'and user_id<>p_owner','and staff_id<>p_owner');
  end if;

  if checklist_has_id then
   definition:=regexp_replace(definition,
    'insert[[:space:]]+into[[:space:]]+checklist[[:space:]]+values[[:space:]]*\(',
    'insert into checklist(checklist_id,staff_id,day,item) values(gen_random_uuid(),','gi');
  else
   definition:=regexp_replace(definition,
    'insert[[:space:]]+into[[:space:]]+checklist[[:space:]]+values[[:space:]]*\(',
    'insert into checklist(staff_id,day,item) values(','gi');
  end if;

  if definition is distinct from original_definition then
   execute definition;
   raise notice 'Updated %', fn.oid::regprocedure;
  end if;
 end loop;
end $$;

-- Stop here if a renamed key is still referenced. Shifts, orders, products and
-- auth.users still use id, so those references are allowed.
do $$
declare fn record; body text; probe text;
begin
 for fn in
  select p.oid, p.prosrc as src
  from pg_proc p
  join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f' and n.nspname in ('public','private','ctfly_private')
 loop
  body:=fn.src;
  if body ~* 'public\.staff[[:space:]]+s\M[^;]{0,255}?s\.id\M' then
   raise exception 'public.staff still uses s.id in %', fn.oid::regprocedure;
  end if;
  if body ~* 'public\."User"[[:space:]]+u\M[^;]{0,255}?u\.id\M' then
   raise exception 'public."User" still uses u.id in %', fn.oid::regprocedure;
  end if;
  probe:=regexp_replace(body,'promotion_id[[:space:]]*=[[:space:]]*p\.id','','gi');
  if probe ~* 'profiles[[:space:]]+p\M[^;]{0,255}?p\.id\M' then
   raise exception 'public.profiles still uses p.id in %', fn.oid::regprocedure;
  end if;
 end loop;
end $$;

-- Every installed table must have its current key. Customer tables keep user_id.
do $$
declare item record;
begin
 for item in
  select * from (values
   ('public','profiles','profile_id'),
   ('public','User','user_id'),
   ('public','staff','staff_id'),
   ('public','resources','id'),
   ('public','bookings','user_id'),
   ('public','bookings','id'),
   ('public','categories','id'),
   ('public','products','id'),
   ('public','stock_records','id'),
   ('public','orders','user_id'),
   ('public','orders','id'),
   ('public','order_items','id'),
   ('public','events','id'),
   ('public','registrations','user_id'),
   ('public','matches','id'),
   ('public','bills','user_id'),
   ('public','booking_extensions','id'),
   ('public','rewards','id'),
   ('public','redemptions','user_id'),
   ('public','points_transactions','user_id'),
   ('public','favorites','user_id'),
   ('public','app_feedback','user_id'),
   ('public','test_payments','user_id'),
   ('public','promotions','id'),
   ('public','promotions','target_user'),
   ('public','promo_claims','user_id'),
   ('public','receipt_email_jobs','user_id'),
   ('public','order_notifications','user_id'),
   ('public','requests','user_id'),
   ('private','user_push_devices','user_id'),
   ('private','order_push_jobs','user_id'),
   ('public','shifts','id'),
   ('public','shifts','staff_id'),
   ('public','attendance','id'),
   ('public','attendance','shift_id'),
   ('public','leave_requests','staff_id'),
   ('public','tasks','staff_id'),
   ('public','checklist','staff_id'),
   ('public','handovers','staff_id'),
   ('public','handover_reads','staff_id'),
   ('public','tickets','staff_id'),
   ('public','tickets','assigned_staff'),
   ('ctfly_private','staff_devices','id'),
   ('ctfly_private','staff_devices','staff_id'),
   ('ctfly_private','staff_device_challenges','device_id')
  ) as required(schema_name,table_name,column_name)
 loop
  if to_regclass(format('%I.%I', item.schema_name, item.table_name)) is null then
   continue;
  end if;
  if not exists(
   select 1 from information_schema.columns
   where table_schema=item.schema_name and table_name=item.table_name and column_name=item.column_name
  ) then
   raise exception 'Table %.% is missing column %', item.schema_name, item.table_name, item.column_name;
  end if;
 end loop;

 for item in
  select * from (values
   ('public','shifts'),
   ('public','leave_requests'),
   ('public','tasks'),
   ('public','checklist'),
   ('public','handovers'),
   ('public','handover_reads'),
   ('public','tickets'),
   ('ctfly_private','staff_devices')
  ) as staff_owned(schema_name,table_name)
 loop
  if to_regclass(format('%I.%I', item.schema_name, item.table_name)) is null then continue; end if;
  if exists(
   select 1 from information_schema.columns
   where table_schema=item.schema_name and table_name=item.table_name and column_name='user_id'
  ) then
   raise exception 'Table %.% still has user_id; it should be staff_id', item.schema_name, item.table_name;
  end if;
 end loop;
end $$;

notify pgrst, 'reload schema';
commit;
