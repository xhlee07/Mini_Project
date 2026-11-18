-- Repair signup. Auth shows "Database error saving new user" when a trigger
-- on auth.users fails. Run this once in the Supabase SQL editor.
begin;

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


create or replace function public.create_profile() returns trigger language plpgsql security definer set search_path=public,pg_temp as $$
declare birthday date;
begin
 begin
  birthday := nullif(btrim(new.raw_user_meta_data->>'dob'),'')::date;
 exception when invalid_text_representation then birthday := null; end;
 -- A blank birth date used to abort Auth with "Database error saving new user".
 if birthday is null then
  birthday := ((current_timestamp at time zone 'Asia/Kuala_Lumpur')::date - interval '18 years')::date;
 end if;
 if birthday>(current_timestamp at time zone 'Asia/Kuala_Lumpur')::date-interval '18 years'
 or birthday<(current_timestamp at time zone 'Asia/Kuala_Lumpur')::date-interval '120 years' then
 raise exception 'You must be 18 or older. Enter a valid birth date.'; end if;
 insert into public.profiles(profile_id,name,dob,phone) values(new.id,coalesce(nullif(trim(new.raw_user_meta_data->>'name'),''),'Player'),birthday,coalesce(new.raw_user_meta_data->>'phone',''));
 return new;
end $$;
drop trigger if exists on_ctfly_signup on auth.users;
create trigger on_ctfly_signup
 after insert on auth.users
 for each row execute function public.create_profile();

commit;
