-- Mirror the public Supabase Auth User attributes into customer/staff role tables.
-- Both tables keep the same Auth user ID as public.profiles; no credential fields are copied.
begin;

create table if not exists public."User" (
 user_id uuid primary key references public.profiles(profile_id) on delete cascade,
 aud text,
 role text,
 email text,
 email_confirmed_at timestamptz,
 phone text,
 phone_confirmed_at timestamptz,
 confirmed_at timestamptz,
 last_sign_in_at timestamptz,
 app_metadata jsonb not null default '{}'::jsonb,
 user_metadata jsonb not null default '{}'::jsonb,
 identities jsonb not null default '[]'::jsonb,
 created_at timestamptz not null,
 updated_at timestamptz,
 is_anonymous boolean not null default false
);
create table if not exists public.staff (
 staff_id uuid primary key references public.profiles(profile_id) on delete cascade,
 aud text,
 role text,
 email text,
 email_confirmed_at timestamptz,
 phone text,
 phone_confirmed_at timestamptz,
 confirmed_at timestamptz,
 last_sign_in_at timestamptz,
 app_metadata jsonb not null default '{}'::jsonb,
 user_metadata jsonb not null default '{}'::jsonb,
 identities jsonb not null default '[]'::jsonb,
 created_at timestamptz not null,
 updated_at timestamptz,
 is_anonymous boolean not null default false
);

-- Upgrade the profile-key-only tables from the previous draft without dropping rows.
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
 if exists(select 1 from information_schema.columns where table_schema='public' and table_name='User' and column_name='raw_app_meta_data')
    and not exists(select 1 from information_schema.columns where table_schema='public' and table_name='User' and column_name='app_metadata') then
  alter table public."User" rename column raw_app_meta_data to app_metadata;
 end if;
 if exists(select 1 from information_schema.columns where table_schema='public' and table_name='User' and column_name='raw_user_meta_data')
    and not exists(select 1 from information_schema.columns where table_schema='public' and table_name='User' and column_name='user_metadata') then
  alter table public."User" rename column raw_user_meta_data to user_metadata;
 end if;
end $$;

alter table public."User" add column if not exists aud text;
alter table public."User" add column if not exists role text;
alter table public."User" add column if not exists email text;
alter table public."User" add column if not exists email_confirmed_at timestamptz;
alter table public."User" add column if not exists phone text;
alter table public."User" add column if not exists phone_confirmed_at timestamptz;
alter table public."User" add column if not exists confirmed_at timestamptz;
alter table public."User" add column if not exists last_sign_in_at timestamptz;
alter table public."User" add column if not exists app_metadata jsonb not null default '{}'::jsonb;
alter table public."User" add column if not exists user_metadata jsonb not null default '{}'::jsonb;
alter table public."User" add column if not exists identities jsonb not null default '[]'::jsonb;
alter table public."User" add column if not exists created_at timestamptz not null default now();
alter table public."User" add column if not exists updated_at timestamptz;
alter table public."User" add column if not exists is_anonymous boolean not null default false;

alter table public.staff add column if not exists aud text;
alter table public.staff add column if not exists role text;
alter table public.staff add column if not exists email text;
alter table public.staff add column if not exists email_confirmed_at timestamptz;
alter table public.staff add column if not exists phone text;
alter table public.staff add column if not exists phone_confirmed_at timestamptz;
alter table public.staff add column if not exists confirmed_at timestamptz;
alter table public.staff add column if not exists last_sign_in_at timestamptz;
alter table public.staff add column if not exists app_metadata jsonb not null default '{}'::jsonb;
alter table public.staff add column if not exists user_metadata jsonb not null default '{}'::jsonb;
alter table public.staff add column if not exists identities jsonb not null default '[]'::jsonb;
alter table public.staff add column if not exists created_at timestamptz not null default now();
alter table public.staff add column if not exists updated_at timestamptz;
alter table public.staff add column if not exists is_anonymous boolean not null default false;

do $$
begin
 if not exists(select 1 from pg_constraint where conrelid='public."User"'::regclass and contype='f' and confrelid='public.profiles'::regclass) then
  alter table public."User" add constraint user_profile_id_fkey foreign key(user_id) references public.profiles(profile_id) on delete cascade;
 end if;
 if not exists(select 1 from pg_constraint where conrelid='public.staff'::regclass and contype='f' and confrelid='public.profiles'::regclass) then
  alter table public.staff add constraint staff_profile_id_fkey foreign key(staff_id) references public.profiles(profile_id) on delete cascade;
 end if;
end $$;

comment on table public."User" is 'Customer Auth user attributes linked to the shared profiles row.';
comment on table public.staff is 'Staff/admin Auth user attributes linked to the shared profiles row.';
comment on column public."User".user_id is 'Customer ID shared with public.profiles.profile_id and Supabase Auth.';
comment on column public.staff.staff_id is 'Staff ID shared with public.profiles.profile_id and Supabase Auth.';

alter table public."User" enable row level security;
alter table public.staff enable row level security;
revoke all on public."User", public.staff from public, anon, authenticated;
grant select on public."User", public.staff to authenticated;
drop policy if exists "Users can read their own auth record" on public."User";
drop policy if exists "Customers can read their own role row" on public."User";
create policy "Customers can read their own role row" on public."User"
 for select to authenticated using ((select auth.uid()) = user_id);
drop policy if exists "Staff can read their own role row" on public.staff;
create policy "Staff can read their own role row" on public.staff
 for select to authenticated using ((select auth.uid()) = staff_id);

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


do $$
declare profile_row record;
begin
 for profile_row in select profile_id,role from public.profiles loop
  perform public.sync_auth_role_record(profile_row.profile_id,profile_row.role);
 end loop;
end $$;

notify pgrst, 'reload schema';
commit;
