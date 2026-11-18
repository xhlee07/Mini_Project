-- Sign in with public."User" and public.staff. Supabase auth.users is not used.
-- Customer passwords live on public."User". Staff and admin passwords live on public.staff.
-- Existing functions keep their names and arguments. public.ctfly_uid() replaces auth.uid().
begin;

create extension if not exists pgcrypto;

alter table public."User" add column if not exists password_hash text;
alter table public."User" add column if not exists recovery_code_hash text;
alter table public."User" add column if not exists recovery_expires timestamptz;
alter table public.staff add column if not exists password_hash text;
alter table public.staff add column if not exists recovery_code_hash text;
alter table public.staff add column if not exists recovery_expires timestamptz;

create table if not exists public.ctfly_sessions (
 id uuid primary key default gen_random_uuid(),
 profile_id uuid not null references public.profiles(profile_id) on delete cascade,
 access_hash text not null unique,
 refresh_hash text not null unique,
 expires_at timestamptz not null,
 created_at timestamptz not null default now());
alter table public.ctfly_sessions enable row level security;
revoke all on public.ctfly_sessions from public, anon, authenticated;

do $$
declare constraint_name name;
begin
 select con.conname into constraint_name
  from pg_constraint con
  join pg_class source on source.oid=con.confrelid
  join pg_namespace ns on ns.oid=source.relnamespace
  where con.conrelid='public.profiles'::regclass and con.contype='f'
    and ns.nspname='auth' and source.relname='users';
 if constraint_name is not null then
  execute format('alter table public.profiles drop constraint %I', constraint_name);
 end if;
end $$;

alter table public.profiles alter column profile_id set default gen_random_uuid();

drop trigger if exists on_ctfly_signup on auth.users;
drop trigger if exists z_sync_auth_user_role_tables on auth.users;
drop trigger if exists z_sync_auth_identity_role_tables on auth.identities;
drop function if exists public.sync_auth_role_record(uuid, text);
drop function if exists public.sync_auth_user_role_tables();
drop function if exists public.sync_auth_identity_role_tables();
drop function if exists public.create_profile();

create unique index if not exists user_email_key on public."User"(lower(email)) where email is not null;
create unique index if not exists staff_email_key on public.staff(lower(email)) where email is not null;

-- One-time copy so people who already registered can keep their password.
-- No later function reads auth.users.
do $$
begin
 if to_regclass('auth.users') is not null
    and exists(select 1 from information_schema.columns
     where table_schema='auth' and table_name='users' and column_name='encrypted_password') then
  insert into public."User"(user_id,email,password_hash,email_confirmed_at,confirmed_at,created_at)
  select p.profile_id, a.email, a.encrypted_password, coalesce(a.email_confirmed_at,now()), coalesce(a.email_confirmed_at,now()), coalesce(a.created_at,now())
   from public.profiles p
   join auth.users a on a.id=p.profile_id
   where p.role='customer'
  on conflict(user_id) do update set
   email=coalesce(public."User".email, excluded.email),
   password_hash=coalesce(public."User".password_hash, excluded.password_hash),
   email_confirmed_at=coalesce(public."User".email_confirmed_at, excluded.email_confirmed_at),
   confirmed_at=coalesce(public."User".confirmed_at, excluded.confirmed_at);
  insert into public.staff(staff_id,email,password_hash,email_confirmed_at,confirmed_at,created_at)
  select p.profile_id, a.email, a.encrypted_password, coalesce(a.email_confirmed_at,now()), coalesce(a.email_confirmed_at,now()), coalesce(a.created_at,now())
   from public.profiles p
   join auth.users a on a.id=p.profile_id
   where p.role in ('staff','admin')
  on conflict(staff_id) do update set
   email=coalesce(public.staff.email, excluded.email),
   password_hash=coalesce(public.staff.password_hash, excluded.password_hash),
   email_confirmed_at=coalesce(public.staff.email_confirmed_at, excluded.email_confirmed_at),
   confirmed_at=coalesce(public.staff.confirmed_at, excluded.confirmed_at);
 end if;
end $$;

-- BEGIN account functions
create or replace function public.ctfly_uid() returns uuid
language plpgsql stable security definer set search_path=public, extensions, pg_temp as $$
declare headers text; token text; uid uuid;
begin
 begin
  headers:=current_setting('request.headers', true);
 exception when others then headers:=null; end;
 if headers is not null and headers<>'' then
  token:=nullif(btrim(headers::json->>'x-ctfly-session'),'');
 end if;
 if token is null then
  begin token:=nullif(btrim(current_setting('request.header.x-ctfly-session', true)),'');
  exception when others then token:=null; end;
 end if;
 if token is null then return null; end if;
 select s.profile_id into uid from public.ctfly_sessions s
  where s.access_hash=encode(digest(token,'sha256'),'hex') and s.expires_at>now();
 return uid;
end $$;

create or replace function public.sync_profile_role_tables() returns trigger
language plpgsql security definer set search_path='' as $$
begin
 if tg_op='UPDATE' and new.role is not distinct from old.role then return new; end if;
 if new.role='customer' then
  delete from public.staff where staff_id=new.profile_id;
  insert into public."User"(user_id,created_at) values(new.profile_id,now()) on conflict(user_id) do nothing;
 else
  delete from public."User" where user_id=new.profile_id;
  insert into public.staff(staff_id,created_at) values(new.profile_id,now()) on conflict(staff_id) do nothing;
 end if;
 return new;
end $$;

create or replace function public.ctfly_issue_session(p_profile uuid) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare access_token text; refresh_token text; email text; expires_in int:=2592000;
begin
 if not exists(select 1 from public.profiles p where p.profile_id=p_profile and p.active
  and ((p.role='customer' and exists(select 1 from public."User" u where u.user_id=p.profile_id))
   or (p.role in ('staff','admin') and exists(select 1 from public.staff s where s.staff_id=p.profile_id)))) then
  raise exception 'Please sign in with an active CTFLY account with a matching User or staff record.';
 end if;
 access_token:=encode(gen_random_bytes(32),'hex');
 refresh_token:=encode(gen_random_bytes(32),'hex');
 insert into public.ctfly_sessions(profile_id,access_hash,refresh_hash,expires_at)
  values(p_profile,encode(digest(access_token,'sha256'),'hex'),encode(digest(refresh_token,'sha256'),'hex'),now()+make_interval(secs=>expires_in));
 update public."User" set last_sign_in_at=now() where user_id=p_profile;
 update public.staff set last_sign_in_at=now() where staff_id=p_profile;
 select coalesce(u.email,s.email) into email
  from public.profiles p
  left join public."User" u on u.user_id=p.profile_id
  left join public.staff s on s.staff_id=p.profile_id
  where p.profile_id=p_profile;
 return jsonb_build_object('access_token',access_token,'refresh_token',refresh_token,'expires_in',expires_in,'token_type','bearer','user',jsonb_build_object('id',p_profile,'email',email));
end $$;

create or replace function public.ctfly_login(p_email text, p_password text) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare rec record;
begin
 begin
  select * into strict rec from (
   select p.profile_id, u.password_hash, u.email_confirmed_at
    from public."User" u join public.profiles p on p.profile_id=u.user_id
    where lower(u.email)=lower(btrim(p_email)) and p.active
   union all
   select p.profile_id, s.password_hash, s.email_confirmed_at
    from public.staff s join public.profiles p on p.profile_id=s.staff_id
    where lower(s.email)=lower(btrim(p_email)) and p.active
  ) accounts;
 exception when no_data_found or too_many_rows then
  raise exception 'Email or password is incorrect.';
 end;
 if rec.password_hash is null or crypt(p_password, rec.password_hash) is distinct from rec.password_hash then
  raise exception 'Email or password is incorrect.';
 end if;
 if rec.email_confirmed_at is null then
  raise exception 'Confirm your email before signing in.';
 end if;
 return public.ctfly_issue_session(rec.profile_id);
end $$;

create or replace function public.ctfly_signup(p_email text, p_password text, p_name text, p_dob text, p_phone text) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare birthday date; pid uuid; normalized text;
begin
 normalized:=lower(btrim(p_email));
 if normalized !~ '^[^[:space:]@]+@[^[:space:]@]+\.[^[:space:]@]+$' then
  raise exception 'Enter a valid email address.';
 end if;
 if length(coalesce(p_password,''))<8 then
  raise exception 'Use at least 8 characters and match both passwords.';
 end if;
 if length(btrim(coalesce(p_name,'')))<2 or length(btrim(p_name))>80 then
  raise exception 'Enter your name.';
 end if;
 begin
  birthday:=nullif(btrim(p_dob),'')::date;
 exception when invalid_text_representation then birthday:=null; end;
 if birthday is null then
  birthday:=((current_timestamp at time zone 'Asia/Kuala_Lumpur')::date - interval '18 years')::date;
 end if;
 if birthday>(current_timestamp at time zone 'Asia/Kuala_Lumpur')::date-interval '18 years'
    or birthday<(current_timestamp at time zone 'Asia/Kuala_Lumpur')::date-interval '120 years' then
  raise exception 'You must be 18 or older. Enter a valid birth date.';
 end if;
 if exists(select 1 from public."User" where lower(email)=normalized)
    or exists(select 1 from public.staff where lower(email)=normalized) then
  raise exception 'An account with this email already exists.';
 end if;
 insert into public.profiles(name,dob,phone,role) values(btrim(p_name),birthday,coalesce(p_phone,''),'customer') returning profile_id into pid;
 update public."User" set email=normalized, password_hash=crypt(p_password, gen_salt('bf',10)),
  email_confirmed_at=now(), confirmed_at=now(), updated_at=now()
  where user_id=pid;
 if not found then
  insert into public."User"(user_id,email,password_hash,email_confirmed_at,confirmed_at,created_at)
   values(pid,normalized,crypt(p_password, gen_salt('bf',10)),now(),now(),now());
 end if;
 return public.ctfly_issue_session(pid);
end $$;

create or replace function public.ctfly_recover(p_email text) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare rec record; code text;
begin
 begin
  select * into strict rec from (
   select u.user_id as profile_id, 'customer' as kind
    from public."User" u join public.profiles p on p.profile_id=u.user_id
    where lower(u.email)=lower(btrim(p_email)) and p.active
   union all
   select s.staff_id, 'staff'
    from public.staff s join public.profiles p on p.profile_id=s.staff_id
    where lower(s.email)=lower(btrim(p_email)) and p.active
  ) accounts;
 exception when no_data_found or too_many_rows then
  return jsonb_build_object('message','If an account exists, a recovery code has been prepared.');
 end;
 code:=lpad((mod(abs(hashtext(gen_random_uuid()::text)),1000000))::text,6,'0');
 if rec.kind='customer' then
  update public."User" set recovery_code_hash=encode(digest(code||user_id::text,'sha256'),'hex'), recovery_expires=now()+interval '15 minutes' where user_id=rec.profile_id;
 else
  update public.staff set recovery_code_hash=encode(digest(code||staff_id::text,'sha256'),'hex'), recovery_expires=now()+interval '15 minutes' where staff_id=rec.profile_id;
 end if;
 return jsonb_build_object('message','Email delivery is not configured. Your recovery code is '||code);
end $$;

create or replace function public.ctfly_resend(p_email text) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
begin
 return public.ctfly_recover(p_email);
end $$;

create or replace function public.ctfly_verify(p_email text, p_code text, p_type text) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare rec record; expected text;
begin
 if p_type not in ('signup','recovery') then raise exception 'Enter the code from your email.'; end if;
 begin
  select * into strict rec from (
   select u.user_id as profile_id, u.recovery_code_hash, u.recovery_expires, 'customer' as kind
    from public."User" u where lower(u.email)=lower(btrim(p_email))
   union all
   select s.staff_id, s.recovery_code_hash, s.recovery_expires, 'staff'
    from public.staff s where lower(s.email)=lower(btrim(p_email))
  ) accounts;
 exception when no_data_found or too_many_rows then
  raise exception 'Enter the code from your email.';
 end;
 expected:=encode(digest(btrim(p_code)||rec.profile_id::text,'sha256'),'hex');
 if rec.recovery_code_hash is null or rec.recovery_expires is null or rec.recovery_expires<now()
    or rec.recovery_code_hash is distinct from expected then
  raise exception 'Enter the code from your email.';
 end if;
 if rec.kind='customer' then
  update public."User" set recovery_code_hash=null, recovery_expires=null, email_confirmed_at=coalesce(email_confirmed_at,now()), confirmed_at=coalesce(confirmed_at,now()) where user_id=rec.profile_id;
 else
  update public.staff set recovery_code_hash=null, recovery_expires=null, email_confirmed_at=coalesce(email_confirmed_at,now()), confirmed_at=coalesce(confirmed_at,now()) where staff_id=rec.profile_id;
 end if;
 return public.ctfly_issue_session(rec.profile_id);
end $$;

create or replace function public.ctfly_new_password(p_password text) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare uid uuid; hashed text; changed boolean:=false;
begin
 uid:=public.ctfly_uid();
 if uid is null then raise exception 'Please sign in.'; end if;
 if length(coalesce(p_password,''))<8 then raise exception 'Use at least 8 characters and match both passwords.'; end if;
 hashed:=crypt(p_password, gen_salt('bf',10));
 update public."User" set password_hash=hashed, recovery_code_hash=null, recovery_expires=null, updated_at=now() where user_id=uid;
 if found then changed:=true; end if;
 update public.staff set password_hash=hashed, recovery_code_hash=null, recovery_expires=null, updated_at=now() where staff_id=uid;
 if found then changed:=true; end if;
 if not changed then raise exception 'Please sign in.'; end if;
 return jsonb_build_object('message','Password saved');
end $$;

create or replace function public.ctfly_refresh_token(p_refresh text) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare pid uuid;
begin
 select profile_id into pid from public.ctfly_sessions
  where refresh_hash=encode(digest(coalesce(p_refresh,''),'sha256'),'hex') and expires_at>now();
 if pid is null then raise exception 'refresh_token_not_found'; end if;
 delete from public.ctfly_sessions where refresh_hash=encode(digest(p_refresh,'sha256'),'hex');
 return public.ctfly_issue_session(pid);
end $$;

create or replace function public.ctfly_logout() returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare headers text; token text;
begin
 begin headers:=current_setting('request.headers', true); exception when others then headers:=null; end;
 if headers is not null and headers<>'' then token:=nullif(btrim(headers::json->>'x-ctfly-session'),''); end if;
 if token is not null then
  delete from public.ctfly_sessions where access_hash=encode(digest(token,'sha256'),'hex');
 end if;
 return jsonb_build_object('message','Signed out');
end $$;

create or replace function public.ctfly_session_profile() returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare uid uuid; email text;
begin
 uid:=public.ctfly_uid();
 if uid is null then raise exception 'Sign in required.'; end if;
 select coalesce(u.email,s.email) into email
  from public.profiles p
  left join public."User" u on u.user_id=p.profile_id
  left join public.staff s on s.staff_id=p.profile_id
  where p.profile_id=uid and p.active;
 if email is null and uid is not null and not exists(select 1 from public.profiles where profile_id=uid and active) then
  raise exception 'Sign in required.';
 end if;
 return jsonb_build_object('id',uid,'email',coalesce(email,''));
end $$;

create or replace function public.ctfly_open_session(p_profile uuid) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
begin
 if not exists(select 1 from public.profiles p where p.profile_id=p_profile and p.active and p.role in ('staff','admin')
  and exists(select 1 from public.staff s where s.staff_id=p.profile_id)) then
  raise exception 'Staff account is unavailable.';
 end if;
 return public.ctfly_issue_session(p_profile);
end $$;
-- END account functions

revoke all on function public.ctfly_uid(), public.sync_profile_role_tables(), public.ctfly_issue_session(uuid),
 public.ctfly_login(text,text), public.ctfly_signup(text,text,text,text,text), public.ctfly_recover(text),
 public.ctfly_resend(text), public.ctfly_verify(text,text,text), public.ctfly_new_password(text),
 public.ctfly_refresh_token(text), public.ctfly_logout(), public.ctfly_session_profile(), public.ctfly_open_session(uuid)
 from public, anon, authenticated;
grant execute on function public.ctfly_uid(), public.ctfly_login(text,text), public.ctfly_signup(text,text,text,text,text),
 public.ctfly_recover(text), public.ctfly_resend(text), public.ctfly_verify(text,text,text), public.ctfly_new_password(text),
 public.ctfly_refresh_token(text), public.ctfly_logout(), public.ctfly_session_profile()
 to anon, authenticated;
grant execute on function public.ctfly_open_session(uuid) to service_role;

drop trigger if exists sync_profile_role_tables on public.profiles;
create trigger sync_profile_role_tables
 after insert or update of role on public.profiles
 for each row execute function public.sync_profile_role_tables();

do $$
declare fn record; definition text; updated text;
begin
 for fn in
  select p.oid from pg_proc p
  join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f' and n.nspname in ('public','private','ctfly_private')
 loop
  definition:=pg_get_functiondef(fn.oid);
  updated:=replace(definition,'auth.uid()','public.ctfly_uid()');
  if updated is distinct from definition then
   execute updated;
  end if;
 end loop;
end $$;

drop policy if exists "Customers can read their own role row" on public."User";
drop policy if exists "Staff can read their own role row" on public.staff;
create policy "Customers can read their own role row" on public."User"
 for select to anon, authenticated using ((select public.ctfly_uid()) = user_id);
create policy "Staff can read their own role row" on public.staff
 for select to anon, authenticated using ((select public.ctfly_uid()) = staff_id);
grant select on public."User", public.staff to anon, authenticated;
revoke select (password_hash, recovery_code_hash) on public."User" from public, anon, authenticated;
revoke select (password_hash, recovery_code_hash) on public.staff from public, anon, authenticated;

grant execute on function public.ctfly_snapshot(), public.ctfly_action(text,jsonb,uuid) to anon;
grant execute on function public.ctfly_quote(text,uuid) to anon;
grant execute on function public.ctfly_staff_history(text,date,text,text,timestamptz,text) to anon;
grant execute on function public.ctfly_staff_transactions(date,text,text,text,timestamptz,text) to anon;
grant execute on function public.ctfly_staff_devices(text,jsonb) to anon;

notify pgrst, 'reload schema';
commit;
