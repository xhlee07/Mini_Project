-- Rename leftover primary keys, then point installed functions at the new names.
-- ctfly_sessions.session_id, resources.resource_id, bookings.booking_id,
-- booking_extensions.booking_extension_id.
-- Orders, bills, products, events, and registrations keep id.
-- Safe to run again: a column that is already renamed is left alone.
begin;

do $$
declare
 item record;
begin
 for item in
  select * from (values
   ('public','ctfly_sessions','id','session_id'),
   ('public','resources','id','resource_id'),
   ('public','bookings','id','booking_id'),
   ('public','booking_extensions','id','booking_extension_id')
  ) as keys(schema_name,table_name,old_name,new_name)
 loop
  if to_regclass(format('%I.%I', item.schema_name, item.table_name)) is null then continue; end if;
  if exists(
   select 1 from information_schema.columns
   where table_schema=item.schema_name and table_name=item.table_name and column_name=item.old_name
  ) and not exists(
   select 1 from information_schema.columns
   where table_schema=item.schema_name and table_name=item.table_name and column_name=item.new_name
  ) then
   execute format('alter table %I.%I rename column %I to %I', item.schema_name, item.table_name, item.old_name, item.new_name);
  end if;
 end loop;
end $$;

do $$
declare
 fn record;
 definition text;
 original_definition text;
 updated text;
begin
 for fn in
  select p.oid
  from pg_proc p
  join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f' and n.nspname in ('public','private','ctfly_private')
 loop
  definition:=pg_get_functiondef(fn.oid);
  original_definition:=definition;

  definition:=replace(definition,'jsonb_agg(r order by id) from resources r','jsonb_agg(r order by resource_id) from resources r');
  definition:=replace(definition,'jsonb_agg(r order by id),''[]''::jsonb) from resources r','jsonb_agg(r order by resource_id),''[]''::jsonb) from resources r');
  definition:=replace(definition,'join resources r on r.id=','join resources r on r.resource_id=');
  definition:=replace(definition,'join public.resources r on r.id=','join public.resources r on r.resource_id=');
  definition:=replace(definition,'from resources where id=','from resources where resource_id=');
  definition:=replace(definition,'from resources r where r.id=','from resources r where r.resource_id=');
  definition:=replace(definition,'perform 1 from resources where id=','perform 1 from resources where resource_id=');
  definition:=replace(definition,'if r.id is null or r.status','if r.resource_id is null or r.status');
  definition:=replace(definition,'values(me.profile_id,r.id,','values(me.profile_id,r.resource_id,');
  definition:=replace(definition,'status=p_data->>''status'' where id=bid','status=p_data->>''status'' where resource_id=bid');
  definition:=regexp_replace(definition,
   'if p_action=''save_resource'' then[[:space:]]+if bid is not null then',
   'if p_action=''save_resource'' then bid:=nullif(coalesce(p_data->>''resource_id'',p_data->>''id''),'''')::bigint; if bid is not null then',
   'g');

  definition:=replace(definition,'where id=b.id','where booking_id=b.booking_id');
  definition:=regexp_replace(definition,
   '(insert into (?:public\.)?bookings\([^;]*?)returning id into',
   '\1returning booking_id into','gi');
  definition:=replace(definition,'from bookings where id=','from bookings where booking_id=');
  definition:=replace(definition,'from public.bookings where id=','from public.bookings where booking_id=');
  definition:=replace(definition,'perform 1 from bookings where id=','perform 1 from bookings where booking_id=');
  definition:=regexp_replace(definition,
   '(update (?:public\.)?bookings set [^;]*?) where id=',
   '\1 where booking_id=','gi');
  definition:=replace(definition,'where b.id=p_id and b.paid and b.status in (''Confirmed'',''Active'')','where b.booking_id=p_id and b.paid and b.status in (''Confirmed'',''Active'')');
  definition:=replace(definition,'where b.id=p_id and b.user_id=','where b.booking_id=p_id and b.user_id=');
  definition:=replace(definition,'bookings b where b.id=','bookings b where b.booking_id=');
  definition:=replace(definition,'if b.id is null or not b.paid','if b.booking_id is null or not b.paid');
  definition:=replace(definition,'where id=b.id','where booking_id=b.booking_id');
  definition:=replace(definition,'values(b.id,n,amount)','values(b.booking_id,n,amount)');
  definition:=replace(definition,'booking_id=session.id','booking_id=session.booking_id');
  definition:=replace(definition,'''booking_id'',session.id','''booking_id'',session.booking_id');
  definition:=replace(definition,'''Booking:''||b.id','''Booking:''||b.booking_id');
  definition:=replace(definition,'r.name as title,b.id::text as reference','r.name as title,b.booking_id::text as reference');
  definition:=replace(definition,'jsonb_build_object(''id'',b.id,''category'',''Bookings''','jsonb_build_object(''id'',b.booking_id,''category'',''Bookings''');
  definition:=replace(definition,'from booking_extensions where id=','from booking_extensions where booking_extension_id=');
  definition:=replace(definition,'insert into booking_extensions(booking_id,minutes,amount) values(b.booking_id,n,amount) returning id into oid','insert into booking_extensions(booking_id,minutes,amount) values(b.booking_id,n,amount) returning booking_extension_id into oid');

  if definition is distinct from original_definition then
   execute definition;
   raise notice 'Updated %', fn.oid::regprocedure;
  end if;
 end loop;
end $$;

do $$
declare fn record; body text;
begin
 for fn in
  select p.oid::regprocedure as signature, p.prosrc as src
  from pg_proc p
  join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f' and n.nspname in ('public','private','ctfly_private')
 loop
  body:=fn.src;
  if body ~* 'join (public\.)?resources[[:space:]]+r[[:space:]]+on[[:space:]]+r\.id\M' then
   raise exception 'resources still uses r.id in %', fn.signature;
  end if;
  if body ~* 'from (public\.)?resources[[:space:]]+where[[:space:]]+id\M' then
   raise exception 'resources still uses id in %', fn.signature;
  end if;
  if body ~* 'from (public\.)?bookings[[:space:]]+where[[:space:]]+id\M' then
   raise exception 'bookings still uses id in %', fn.signature;
  end if;
  if body ~* 'update (public\.)?bookings[[:space:]]+set[^;]{0,400}?where[[:space:]]+id\M' then
   raise exception 'bookings update still uses id in %', fn.signature;
  end if;
  if body ~ 'Booking:''\|\|b\.id' then
   raise exception 'staff history still uses b.id in %', fn.signature;
  end if;
  if body ~ 'where booking_id=b\.id' or body ~ 'values\(b\.id,' then
   raise exception 'booking row still uses b.id in %', fn.signature;
  end if;
  if body ~* 'from (public\.)?booking_extensions[[:space:]]+where[[:space:]]+id\M' then
   raise exception 'booking_extensions still uses id in %', fn.signature;
  end if;
  if body ~* 'insert into (public\.)?booking_extensions\([^;]*returning id\M' then
   raise exception 'booking_extensions insert still returns id in %', fn.signature;
  end if;
 end loop;
end $$;

commit;
