-- CTFLY Android database setup for a new Supabase project.
-- Online checkout records simulations in the database. It has no financial provider.
begin;

-- Section: base-schema
create extension if not exists btree_gist;
create extension if not exists pgcrypto;
create table public.profiles (
 profile_id uuid primary key default gen_random_uuid(),
 name text not null check(length(name) between 1 and 80), phone text not null default '',
 dob date not null, role text not null default 'customer' check(role in ('customer','staff','admin')),
 points int not null default 0 check(points>=0), spent int not null default 0 check(spent>=0),
 active boolean not null default true, created_at timestamptz not null default now());
-- Customer and staff role records hold the account email and password hash.
-- Profile ID is the shared application key. Supabase auth.users is not used.
create table public."User" (
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
 is_anonymous boolean not null default false,
 password_hash text,
 recovery_code_hash text,
 recovery_expires timestamptz);
create table public.staff (
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
 is_anonymous boolean not null default false,
 password_hash text,
 recovery_code_hash text,
 recovery_expires timestamptz);
comment on table public."User" is 'Customer account. The password hash stays on this table.';
comment on table public.staff is 'Staff and admin account. The password hash stays on this table.';
comment on column public."User".user_id is 'Customer ID, shared with public.profiles.profile_id.';
comment on column public.staff.staff_id is 'Staff ID, shared with public.profiles.profile_id.';
comment on column public."User".password_hash is 'Bcrypt hash. The plain password is never stored.';
comment on column public.staff.password_hash is 'Bcrypt hash. The plain password is never stored.';
create unique index user_email_key on public."User"(lower(email)) where email is not null;
create unique index staff_email_key on public.staff(lower(email)) where email is not null;
create table public.ctfly_sessions (
 session_id uuid primary key default gen_random_uuid(),
 profile_id uuid not null references public.profiles(profile_id) on delete cascade,
 access_hash text not null unique,
 refresh_hash text not null unique,
 expires_at timestamptz not null,
 created_at timestamptz not null default now());
alter table public."User" enable row level security;
alter table public.staff enable row level security;
alter table public.ctfly_sessions enable row level security;
revoke all on public."User", public.staff, public.ctfly_sessions from public, anon, authenticated;
grant select on public."User", public.staff to anon, authenticated;
revoke select (password_hash, recovery_code_hash) on public."User" from public, anon, authenticated;
revoke select (password_hash, recovery_code_hash) on public.staff from public, anon, authenticated;

create function public.ctfly_uid() returns uuid
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
revoke all on function public.ctfly_uid() from public, anon, authenticated;
grant execute on function public.ctfly_uid() to anon, authenticated;

create policy "Customers can read their own role row" on public."User"
 for select to anon, authenticated using ((select public.ctfly_uid()) = user_id);
create policy "Staff can read their own role row" on public.staff
 for select to anon, authenticated using ((select public.ctfly_uid()) = staff_id);

create table public.resources (
 resource_id bigint generated always as identity primary key, name text not null unique,
 kind text not null check(kind in ('PC','Snooker','Pool','Console','Racing')), zone text not null default 'Standard',
 description text not null, capacity int not null default 1 check(capacity between 1 and 20),
 rate int not null check(rate>0), status text not null default 'Available' check(status in ('Available','Maintenance','Retired')));
create table public.bookings (
 booking_id uuid primary key default gen_random_uuid(), user_id uuid not null references public."User"(user_id),
 resource_id bigint not null references public.resources(resource_id), starts_at timestamptz not null, ends_at timestamptz not null,
 guests int not null check(guests>0), total int not null check(total>=0),
 status text not null default 'Reserved' check(status in ('Reserved','Confirmed','Active','Completed','Cancelled','Expired')),
 paid boolean not null default false, token uuid not null unique default gen_random_uuid(), checked_in timestamptz,
 created_at timestamptz not null default now(), check(ends_at>starts_at),
 exclude using gist(resource_id with =, tstzrange(starts_at,ends_at,'[)') with &&)
 where(status in ('Reserved','Confirmed','Active')));
create table public.categories(category_id bigint generated always as identity primary key, name text not null unique);
create table public.products (
 product_id bigint generated always as identity primary key, category_id bigint not null references public.categories(category_id),
 name text not null, price int not null check(price>=0), stock int not null check(stock>=0),
 threshold int not null default 5 check(threshold>=0), image text not null default 'iced_coffee', active boolean not null default true);
create table public.stock_records(stock_id bigint generated always as identity primary key, product_id bigint not null references public.products(product_id),
 quantity int not null, reason text not null, created_at timestamptz not null default now());
create table public.orders(order_id uuid primary key default gen_random_uuid(), user_id uuid not null references public."User"(user_id),
 booking_id uuid references public.bookings(booking_id), total int not null check(total>=0), paid boolean not null default false,
 status text not null default 'Pending' check(status in ('Pending','Preparing','Ready','Delivered','Cancelled')), created_at timestamptz not null default now());
create table public.order_items(order_item_id bigint generated always as identity primary key, order_id uuid not null references public.orders(order_id),
 product_id bigint not null references public.products(product_id), name text not null, qty int not null check(qty>0), price int not null check(price>=0));
create table public.events(event_id bigint generated always as identity primary key, name text not null, game text not null,
 starts_at timestamptz not null, ends_at timestamptz not null, fee int not null check(fee>=0), capacity int not null check(capacity between 2 and 64),
 status text not null default 'Open' check(status in ('Open','Running','Completed','Cancelled')), check(ends_at>starts_at),
 exclude using gist(tstzrange(starts_at,ends_at,'[)') with &&) where(status in ('Open','Running')));
create table public.registrations(registration_id uuid primary key default gen_random_uuid(), event_id bigint not null references public.events(event_id),
 user_id uuid not null references public."User"(user_id), team text not null check(length(team) between 1 and 80), fee int not null,
 paid boolean not null default false, status text not null default 'Registered' check(status in ('Registered','Withdrawn')),
 unique(event_id,user_id));
create table public.matches(match_id bigint generated always as identity primary key, event_id bigint not null references public.events(event_id),
 round int not null, slot int not null, team_a text, team_b text, winner text, done boolean not null default false, unique(event_id,round,slot),
 check(team_a is null or length(team_a) between 1 and 80), check(team_b is null or length(team_b) between 1 and 80), check(winner is null or length(winner) between 1 and 80),
 check((not done and winner is null) or (done and winner is not null and (winner=team_a or (team_b is not null and winner=team_b)))));
create table public.bills(bill_id uuid primary key default gen_random_uuid(), user_id uuid not null references public."User"(user_id),
 kind text not null check(kind in ('Booking','Order','Event','Extension')), source_id uuid not null, subtotal int not null, discount int not null,
 total int not null check(total>=0), points int not null, method text not null default 'Cash at counter', created_at timestamptz not null default now(),
 status text not null default 'Paid' check(status in ('Paid','Void')), void_reason text not null default '');
create unique index one_paid_bill on public.bills(kind,source_id) where status='Paid';
create table public.booking_extensions(booking_extension_id uuid primary key default gen_random_uuid(), booking_id uuid not null references public.bookings(booking_id),
 minutes int not null check(minutes in (30,60)), amount int not null check(amount>=0), created_at timestamptz not null default now());
create table public.rewards(reward_id bigint generated always as identity primary key, name text not null, points int not null check(points>0), stock int not null check(stock>=0), active boolean not null default true);
create table public.redemptions(redeem_id uuid primary key default gen_random_uuid(), user_id uuid not null references public."User"(user_id),
 reward_id bigint not null references public.rewards(reward_id), name text not null, points int not null, status text not null default 'Pending', created_at timestamptz not null default now());
create table public.points_transactions(point_id bigint generated always as identity primary key, user_id uuid not null references public."User"(user_id),
 points int not null, reason text not null, created_at timestamptz not null default now());
create table public.favorites(user_id uuid not null references public."User"(user_id), product_id bigint not null references public.products(product_id), primary key(user_id,product_id));
create table public.shifts(shift_id uuid primary key default gen_random_uuid(), staff_id uuid not null references public.staff(staff_id),
 starts_at timestamptz not null, ends_at timestamptz not null, status text not null default 'Scheduled' check(status in ('Scheduled','Cancelled')),
 check(ends_at>starts_at), exclude using gist(staff_id with =,tstzrange(starts_at,ends_at,'[)') with &&) where(status='Scheduled'));
create table public.attendance(attendance_id uuid primary key default gen_random_uuid(), shift_id uuid not null unique references public.shifts(shift_id),
 clock_in timestamptz not null default now(), clock_out timestamptz, late_minutes int not null default 0, early_minutes int not null default 0, minutes int not null default 0);
create table public.leave_requests(leave_request_id uuid primary key default gen_random_uuid(), staff_id uuid not null references public.staff(staff_id),
 day date not null, reason text not null, status text not null default 'Pending' check(status in ('Pending','Approved','Declined')), created_at timestamptz not null default now());
create table public.tasks(task_id uuid primary key default gen_random_uuid(), staff_id uuid not null references public.staff(staff_id),
 title text not null, day date not null, done boolean not null default false);
create table public.checklist(
 checklist_id uuid primary key default gen_random_uuid(),
 staff_id uuid not null references public.staff(staff_id),
 day date not null,
 item text not null,
 unique(staff_id,day,item)
);
create table public.handovers(handover_id uuid primary key default gen_random_uuid(), staff_id uuid not null references public.staff(staff_id),
 title text not null, note text not null, category text not null, created_at timestamptz not null default now());
create table public.handover_reads(handover_id uuid not null references public.handovers, staff_id uuid not null references public.staff(staff_id), primary key(handover_id,staff_id));
create table public.tickets(ticket_id uuid primary key default gen_random_uuid(), staff_id uuid not null references public.staff(staff_id),
 asset text not null, title text not null, priority text not null check(priority in ('Low','Normal','Urgent')), status text not null default 'Open',
 resolution text not null default '', created_at timestamptz not null default now());
create table public.ticket_assignments(assignment_id uuid primary key default gen_random_uuid(), ticket_id uuid not null unique references public.tickets(ticket_id) on delete cascade,
 staff_id uuid not null references public.staff(staff_id), assigned_at timestamptz not null default now());
-- Idempotent operations: a network retry cannot repeat an order, payment or booking.
-- Shared idempotency keys cover both customer and staff mutations, so they stay on profiles.profile_id.
create table public.requests(request_id uuid primary key, user_id uuid not null references public.profiles(profile_id), result jsonb not null);
create index booking_member on public.bookings(user_id,starts_at);
create index order_member on public.orders(user_id,created_at);
create index shifts_member on public.shifts(staff_id,starts_at);

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

drop trigger if exists sync_profile_role_tables on public.profiles;
create trigger sync_profile_role_tables
 after insert or update of role on public.profiles
 for each row execute function public.sync_profile_role_tables();

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

create function public.expire_bookings() returns void language sql security definer set search_path=public, extensions, pg_temp as $$
 update bookings set status=case when status='Active' then 'Completed' else 'Expired' end
 where (status in ('Active','Confirmed') and ends_at<=now()) or (status='Reserved' and starts_at+interval '15 minutes'<=now());
$$;

create function public.ctfly_catalog() returns jsonb language sql security definer set search_path=public, extensions, pg_temp as $$
 select jsonb_build_object(
 'resources',coalesce((select jsonb_agg(r order by resource_id) from resources r where status<>'Retired'),'[]'::jsonb),
 'products',coalesce((select jsonb_agg(p order by product_id) from products p where active),'[]'::jsonb),
 'categories',coalesce((select jsonb_agg(c order by category_id) from categories c),'[]'::jsonb),
 'events',coalesce((select jsonb_agg(e order by starts_at) from events e where status<>'Cancelled' and ends_at>now()),'[]'::jsonb),
 'rewards',coalesce((select jsonb_agg(r order by reward_id) from rewards r where active),'[]'::jsonb));
$$;

create function public.ctfly_snapshot() returns jsonb language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare me profiles; result jsonb; crew boolean;
begin
 select p.* into me from public.profiles p where p.profile_id=public.ctfly_uid() and p.active
  and ((p.role='customer' and exists(select 1 from public."User" u where u.user_id=p.profile_id))
   or (p.role in ('staff','admin') and exists(select 1 from public.staff s where s.staff_id=p.profile_id)));
 if me.profile_id is null then raise exception 'Please sign in with an active CTFLY account with a matching User or staff record.'; end if;
 crew := me.role in ('staff','admin');
 perform expire_bookings();
 result := ctfly_catalog() || jsonb_build_object('profile',to_jsonb(me)||jsonb_build_object('id',me.profile_id), 'server_time',now());
 result := result || jsonb_build_object(
 'bookings',coalesce((select jsonb_agg(b order by starts_at desc) from (select * from bookings where user_id=me.profile_id or crew order by starts_at desc limit 300) b),'[]'::jsonb),
 'orders',coalesce((select jsonb_agg(o order by created_at desc) from (select * from orders where user_id=me.profile_id or crew order by created_at desc limit 300) o),'[]'::jsonb),
 'order_items',coalesce((select jsonb_agg(i) from order_items i join orders o on o.order_id=i.order_id where o.user_id=me.profile_id or crew),'[]'::jsonb),
 'registrations',coalesce((select jsonb_agg(r) from registrations r where r.user_id=me.profile_id or crew),'[]'::jsonb),
 'matches',coalesce((select jsonb_agg(jsonb_build_object('id',m.match_id,'event_id',m.event_id,'round',m.round,'slot',m.slot,'team_a',m.team_a,'team_b',m.team_b,'winner',m.winner,'team_winner',m.winner,'done',m.done) order by m.round,m.slot)
 from matches m),'[]'::jsonb),
 'bills',coalesce((select jsonb_agg(b order by created_at desc) from bills b where b.user_id=me.profile_id or me.role='admin'),'[]'::jsonb),
 'redemptions',coalesce((select jsonb_agg(r) from redemptions r where r.user_id=me.profile_id or crew),'[]'::jsonb),
 'points_transactions',coalesce((select jsonb_agg(p order by created_at desc) from points_transactions p where p.user_id=me.profile_id),'[]'::jsonb),
 'favorites',coalesce((select jsonb_agg(f) from favorites f where user_id=me.profile_id),'[]'::jsonb));
 if crew then
 result := result || jsonb_build_object(
 'people',coalesce((select jsonb_agg(jsonb_build_object('id',profile_id,'name',name,'role',role,'active',active,'phone',case when me.role='admin' then phone else '' end)) from profiles where me.role='admin' or role in ('staff','admin')),'[]'::jsonb),
 'shifts',coalesce((select jsonb_agg(s order by starts_at) from shifts s where staff_id=me.profile_id or me.role='admin'),'[]'::jsonb),
 'attendance',coalesce((select jsonb_agg(a) from attendance a join shifts s on s.shift_id=a.shift_id where s.staff_id=me.profile_id or me.role='admin'),'[]'::jsonb),
 'leave_requests',coalesce((select jsonb_agg(l) from leave_requests l where staff_id=me.profile_id or me.role='admin'),'[]'::jsonb),
 'tasks',coalesce((select jsonb_agg(t order by day) from tasks t where staff_id=me.profile_id or me.role='admin'),'[]'::jsonb),
 'checklist',coalesce((select jsonb_agg(c) from checklist c where staff_id=me.profile_id and day=(now() at time zone 'Asia/Kuala_Lumpur')::date),'[]'::jsonb),
 'handovers',coalesce((select jsonb_agg(h order by created_at desc) from handovers h),'[]'::jsonb),
 'handover_reads',coalesce((select jsonb_agg(h) from handover_reads h where staff_id=me.profile_id),'[]'::jsonb),
 'tickets',coalesce((select jsonb_agg((to_jsonb(t) || jsonb_build_object('assigned_staff', a.staff_id)) order by t.created_at desc) from tickets t left join ticket_assignments a on a.ticket_id=t.ticket_id),'[]'::jsonb),
 'ticket_assignments',coalesce((select jsonb_agg(a order by a.assigned_at desc) from ticket_assignments a),'[]'::jsonb));
 end if;
 if me.role='admin' then result := result || jsonb_build_object(
 'resources',(select coalesce(jsonb_agg(r order by resource_id),'[]'::jsonb) from resources r),
 'products',(select coalesce(jsonb_agg(p order by product_id),'[]'::jsonb) from products p),
 'events',(select coalesce(jsonb_agg(e order by starts_at),'[]'::jsonb) from events e),
 'rewards',(select coalesce(jsonb_agg(r order by reward_id),'[]'::jsonb) from rewards r),
 'stock_records',(select coalesce(jsonb_agg(s order by created_at desc),'[]'::jsonb) from stock_records s)); end if;
 return result;
end $$;

create function public.ctfly_quote(p_kind text,p_id uuid) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare uid uuid; amount int; spent_total int; discount_total int;
begin
 if not exists(select 1 from public.profiles p where p.profile_id=public.ctfly_uid() and p.active and p.role in ('staff','admin')
  and exists(select 1 from public.staff s where s.staff_id=p.profile_id)) then raise exception 'Staff access required.'; end if;
 if p_kind='Booking' then select user_id,total into uid,amount from bookings where booking_id=p_id and status='Reserved' and not paid;
 elsif p_kind='Order' then select user_id,total into uid,amount from orders where order_id=p_id and status<>'Cancelled' and not paid;
 elsif p_kind='Event' then select user_id,fee into uid,amount from registrations where registration_id=p_id and status='Registered' and not paid;
 elsif p_kind in ('Extension30','Extension60') then
  select b.user_id,(r.rate*(case when p_kind='Extension30' then 30 else 60 end)+30)/60 into uid,amount from bookings b join resources r on r.resource_id=b.resource_id where b.booking_id=p_id and b.paid and b.status in ('Confirmed','Active') and b.ends_at>now();
 end if;
 if uid is null then raise exception 'Already paid or no longer payable.'; end if;
 select spent into spent_total from profiles where profile_id=uid;
 discount_total:=amount*(case when spent_total>=100000 then 10 when spent_total>=30000 then 5 else 0 end)/100;
 return jsonb_build_object('id',p_id,'kind',p_kind,'subtotal',amount,'discount',discount_total,'total',amount-discount_total);
end $$;

create function public.ctfly_action(p_action text, p_data jsonb, p_request uuid) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
declare me profiles; r resources; b bookings; pr products; ev events; rw rewards; sh shifts; tk tickets;
 oid uuid; uid uuid; sid uuid; bid bigint; n int; amount int; disc int; earned int; target profiles;
 st timestamptz; en timestamptz; item jsonb; result jsonb; existing jsonb; m matches; sides text[]; size int; rd int; j int; winner_name text; invoice bills; day_value date;
begin
 -- Serializes mutations per account and keeps retry responses consistent.
 select p.* into me from public.profiles p where p.profile_id=public.ctfly_uid() and p.active
  and ((p.role='customer' and exists(select 1 from public."User" u where u.user_id=p.profile_id))
   or (p.role in ('staff','admin') and exists(select 1 from public.staff s where s.staff_id=p.profile_id))) for update;
 if me.profile_id is null then raise exception 'Please sign in with an active CTFLY account with a matching User or staff record.'; end if;
 if p_request is null then raise exception 'Missing request identifier.'; end if;
 select q.result into existing from requests q where q.user_id=me.profile_id and q.request_id=p_request;
 if existing is not null then return existing; end if;
 perform expire_bookings();
 result := jsonb_build_object('message','Saved');
 if p_action='book' then
  if me.role<>'customer' then raise exception 'Use a customer account to book.'; end if;
  if me.dob>(now() at time zone 'Asia/Kuala_Lumpur')::date-interval '18 years' then raise exception 'Bookings are 18+.'; end if;
  select * into r from resources where resource_id=(p_data->>'resource_id')::bigint for update;
  n:=(p_data->>'minutes')::int; st:=(p_data->>'starts_at')::timestamptz; en:=st+make_interval(mins=>n);
  if r.resource_id is null or r.status<>'Available' or n not in (60,90,120,180) or st<now()-interval '1 minute' or st>now()+interval '90 days'
   or (p_data->>'guests')::int not between 1 and r.capacity then raise exception 'Choose an available space, valid time and group size.'; end if;
  if (select count(*) from bookings where user_id=me.profile_id and status in ('Reserved','Confirmed','Active'))>=3 then raise exception 'You can hold up to 3 upcoming bookings.'; end if;
  insert into bookings(user_id,resource_id,starts_at,ends_at,guests,total) values(me.profile_id,r.resource_id,st,en,(p_data->>'guests')::int,(r.rate*n+30)/60) returning booking_id into oid;
  result:=jsonb_build_object('message','Reserved. Pay at the counter before check-in.','id',oid);
 elsif p_action='cancel_booking' then
  update bookings set status='Cancelled' where booking_id=(p_data->>'id')::uuid and user_id=me.profile_id and status='Reserved' and starts_at>now();
  if not found then raise exception 'Only your unpaid, upcoming reservation can be cancelled.'; end if;
 elsif p_action='extend_booking' then
  if me.role<>'admin' then raise exception 'Manager access required.'; end if;
  select * into b from bookings where booking_id=(p_data->>'id')::uuid for update;
  if b.status<>'Reserved' then raise exception 'Extend unpaid reservations only. For paid sessions create another booking.'; end if;
  select * into r from resources where resource_id=b.resource_id for update;
  n:=(p_data->>'minutes')::int;
  if n not in (30,60) then raise exception 'Choose 30 or 60 minutes.'; end if;
  update bookings set ends_at=ends_at+make_interval(mins=>n),total=total+(r.rate*n+30)/60 where booking_id=b.booking_id;
 elsif p_action='order' then
  if me.role<>'customer' then raise exception 'Use a customer account to order.'; end if;
  if jsonb_typeof(p_data->'items')<>'array' or jsonb_array_length(p_data->'items') not between 1 and 30 then raise exception 'Choose 1 to 30 products.'; end if;
  if exists(select 1 from jsonb_array_elements(p_data->'items') x group by x->>'id' having count(*)>1) then raise exception 'Duplicate product.'; end if;
  sid:=nullif(p_data->>'booking_id','')::uuid;
  if sid is not null and not exists(select 1 from bookings where booking_id=sid and user_id=me.profile_id and status='Active' and ends_at>now()) then raise exception 'Delivery needs your active session.'; end if;
  insert into orders(user_id,booking_id,total) values(me.profile_id,sid,0) returning order_id into oid;
  amount:=0;
  for item in select value from jsonb_array_elements(p_data->'items') order by (value->>'id')::bigint loop
   n:=(item->>'qty')::int;
   select * into pr from products where product_id=(item->>'id')::bigint for update;
   if pr.product_id is null or not pr.active or n not between 1 and 20 or pr.stock<n then raise exception 'A product is unavailable or has insufficient stock.'; end if;
   update products set stock=stock-n where product_id=pr.product_id;
   insert into order_items(order_id,product_id,name,qty,price) values(oid,pr.product_id,pr.name,n,pr.price);
   insert into stock_records(product_id,quantity,reason) values(pr.product_id,-n,'Order '||oid);
   amount:=amount+pr.price*n;
  end loop;
  update orders set total=amount where order_id=oid;
  result:=jsonb_build_object('message','Order sent. Pay at the counter.','id',oid);
 elsif p_action='cancel_order' then
  perform 1 from orders where order_id=(p_data->>'id')::uuid and user_id=me.profile_id and status='Pending' and not paid for update;
  if not found then raise exception 'Only your pending unpaid order can be cancelled.'; end if;
  for item in select to_jsonb(i) from order_items i where order_id=(p_data->>'id')::uuid order by product_id loop
   update products set stock=stock+(item->>'qty')::int where product_id=(item->>'product_id')::bigint;
   insert into stock_records(product_id,quantity,reason) values((item->>'product_id')::bigint,(item->>'qty')::int,'Cancelled order');
  end loop;
  update orders set status='Cancelled' where order_id=(p_data->>'id')::uuid;
 elsif p_action='favorite' then
  delete from favorites where user_id=me.profile_id and product_id=(p_data->>'id')::bigint;
  if not found then insert into favorites values(me.profile_id,(p_data->>'id')::bigint); end if;
 elsif p_action='register_event' then
  if me.role<>'customer' then raise exception 'Use a customer account to enter.'; end if;
  select * into ev from events where event_id=(p_data->>'event_id')::bigint for update;
  if ev.event_id is null or ev.status<>'Open' or ev.starts_at<=now() then raise exception 'Registration is closed.'; end if;
  if (select count(*) from registrations where event_id=ev.event_id and status='Registered')>=ev.capacity then raise exception 'This event is full.'; end if;
  insert into registrations(event_id,user_id,team,fee) values(ev.event_id,me.profile_id,trim(p_data->>'team'),ev.fee)
  on conflict(event_id,user_id) do update set status='Registered',team=excluded.team,fee=excluded.fee where registrations.status='Withdrawn';
  if not found then raise exception 'You are already registered.'; end if;
 elsif p_action='withdraw_event' then
  select e.* into ev from events e join registrations g on g.event_id=e.event_id where g.registration_id=(p_data->>'id')::uuid for update of e;
  if ev.status<>'Open' or ev.starts_at<=now() then raise exception 'Registration is closed.'; end if;
  update registrations set status='Withdrawn' where registration_id=(p_data->>'id')::uuid and user_id=me.profile_id and not paid;
  if not found then raise exception 'Only unpaid entries can be withdrawn.'; end if;
 elsif p_action='redeem' then
  if me.role<>'customer' then raise exception 'Customer membership required.'; end if;
  select * into rw from rewards where reward_id=(p_data->>'id')::bigint for update;
  if rw.reward_id is null or not rw.active or rw.stock<1 or me.points<rw.points then raise exception 'Not enough points or reward stock.'; end if;
  update profiles set points=points-rw.points where profile_id=me.profile_id;
  update rewards set stock=stock-1 where reward_id=rw.reward_id;
  insert into redemptions(user_id,reward_id,name,points) values(me.profile_id,rw.reward_id,rw.name,rw.points);
  insert into points_transactions(user_id,points,reason) values(me.profile_id,-rw.points,'Redeemed '||rw.name);
 elsif p_action='profile' then
  update profiles set name=trim(p_data->>'name'),phone=left(p_data->>'phone',30) where profile_id=me.profile_id;
 elsif p_action in ('pay','extend_paid','check_in','end_session','order_status','collect_reward','clock','leave','task','task_done','checklist','handover','ack','ticket','claim','resolve','reopen') then
  if me.role not in ('staff','admin') then raise exception 'Staff access required.'; end if;
  if p_action='pay' then
   sid:=(p_data->>'id')::uuid;
   if p_data->>'kind'='Booking' then
    select user_id,total into uid,amount from bookings where booking_id=sid and status='Reserved' and not paid for update;
   elsif p_data->>'kind'='Order' then
    select user_id,total into uid,amount from orders where order_id=sid and status<>'Cancelled' and not paid for update;
   elsif p_data->>'kind'='Event' then
    select user_id,fee into uid,amount from registrations where registration_id=sid and status='Registered' and not paid for update;
   else raise exception 'Invalid payment source.'; end if;
   if uid is null then raise exception 'Already paid or no longer payable.'; end if;
   select * into target from profiles where profile_id=uid for update;
   disc:=amount*(case when target.spent>=100000 then 10 when target.spent>=30000 then 5 else 0 end)/100;
   if nullif(p_data->>'expected_total','')::int is distinct from amount-disc then raise exception 'The payment amount changed. Close and reopen payment to get a fresh quote.'; end if;
   earned:=(amount-disc)/100;
   insert into bills(user_id,kind,source_id,subtotal,discount,total,points) values(uid,p_data->>'kind',sid,amount,disc,amount-disc,earned);
   update profiles set points=points+earned,spent=spent+amount-disc where profile_id=uid;
   insert into points_transactions(user_id,points,reason) values(uid,earned,'Counter payment');
   if p_data->>'kind'='Booking' then update bookings set paid=true,status='Confirmed' where booking_id=sid;
   elsif p_data->>'kind'='Order' then update orders set paid=true where order_id=sid;
   else update registrations set paid=true where registration_id=sid; end if;
   result:=jsonb_build_object('message','Cash payment recorded: RM '||to_char((amount-disc)/100.0,'FM9999990.00'));
  elsif p_action='extend_paid' then
   select * into b from bookings where booking_id=(p_data->>'id')::uuid for update;
   n:=(p_data->>'minutes')::int;
   if b.booking_id is null or not b.paid or b.status not in ('Confirmed','Active') or b.ends_at<=now() or n not in (30,60) then raise exception 'Choose a live paid booking and 30 or 60 extra minutes.'; end if;
   select * into r from resources where resource_id=b.resource_id for update;
   if r.status<>'Available' then raise exception 'Space is not available.'; end if;
   select * into target from profiles where profile_id=b.user_id for update;
   amount:=(r.rate*n+30)/60;
   disc:=amount*(case when target.spent>=100000 then 10 when target.spent>=30000 then 5 else 0 end)/100;
   if nullif(p_data->>'expected_total','')::int is distinct from amount-disc then raise exception 'The payment amount changed. Reopen the extension quote.'; end if;
   earned:=(amount-disc)/100;
   update bookings set ends_at=ends_at+make_interval(mins=>n),total=total+amount where booking_id=b.booking_id;
   insert into booking_extensions(booking_id,minutes,amount) values(b.booking_id,n,amount) returning booking_extension_id into oid;
   insert into bills(user_id,kind,source_id,subtotal,discount,total,points) values(b.user_id,'Extension',oid,amount,disc,amount-disc,earned);
   update profiles set spent=spent+amount-disc,points=points+earned where profile_id=b.user_id;
   insert into points_transactions(user_id,points,reason) values(b.user_id,earned,'Session extension payment');
   result:=jsonb_build_object('message','Cash received and session extended by '||n||' minutes.');
  elsif p_action='check_in' then
   select * into b from bookings where token=(replace(p_data->>'token','ctfly:',''))::uuid for update;
   if b.booking_id is null or not b.paid or b.status<>'Confirmed' or now()<b.starts_at or now()>=b.ends_at then raise exception 'QR must be paid, unused and within the reserved time.'; end if;
   if not exists(select 1 from resources where resource_id=b.resource_id and status='Available') then raise exception 'Space is unavailable. Contact a manager.'; end if;
   update bookings set status='Active',checked_in=now() where booking_id=b.booking_id;
   result:=jsonb_build_object('message','Checked in. The original booking end time applies.');
  elsif p_action='end_session' then
   update bookings set status='Completed' where booking_id=(p_data->>'id')::uuid and status='Active';
   if not found then raise exception 'Choose an active session.'; end if;
  elsif p_action='order_status' then
   update orders set status=p_data->>'status' where order_id=(p_data->>'id')::uuid and paid and
   ((status='Pending' and p_data->>'status'='Preparing') or (status='Preparing' and p_data->>'status'='Ready') or (status='Ready' and p_data->>'status'='Delivered'));
   if not found then raise exception 'Pay first, then advance the order one stage.'; end if;
  elsif p_action='collect_reward' then
   update redemptions set status='Collected' where redeem_id=(p_data->>'id')::uuid and status='Pending';
   if not found then raise exception 'Voucher already collected.'; end if;
  elsif p_action='clock' then
   select * into sh from shifts where shift_id=(p_data->>'id')::uuid and staff_id=me.profile_id and status='Scheduled' for update;
   if sh.shift_id is null then raise exception 'Choose your scheduled shift.'; end if;
   if not exists(select 1 from attendance where shift_id=sh.shift_id) then
    if now()<sh.starts_at-interval '30 minutes' or now()>=sh.ends_at then raise exception 'Clock in from 30 minutes before your shift until its end.'; end if;
    insert into attendance(shift_id,late_minutes) values(sh.shift_id,greatest(0,floor(extract(epoch from now()-sh.starts_at)/60)::int));
   else
    update attendance set clock_out=now(),minutes=greatest(0,floor(extract(epoch from now()-clock_in)/60)::int),early_minutes=greatest(0,floor(extract(epoch from sh.ends_at-now())/60)::int) where shift_id=sh.shift_id and clock_out is null;
    if not found then raise exception 'This shift is already clocked out.'; end if;
   end if;
  elsif p_action='leave' then
   if (p_data->>'day')::date<(now() at time zone 'Asia/Kuala_Lumpur')::date or length(trim(p_data->>'reason'))<3 then raise exception 'Choose a future date and reason.'; end if;
   insert into leave_requests(staff_id,day,reason) values(me.profile_id,(p_data->>'day')::date,p_data->>'reason');
  elsif p_action='task' then
   uid:=case when me.role='admin' then coalesce(nullif(p_data->>'staff_id','')::uuid,nullif(p_data->>'user_id','')::uuid,me.profile_id) else me.profile_id end;
   if not exists(select 1 from profiles where profile_id=uid and active and role in ('staff','admin')) or length(trim(p_data->>'title'))<2 then raise exception 'Choose a staff account and task title.'; end if;
   insert into tasks(staff_id,title,day) values(uid,trim(p_data->>'title'),(p_data->>'day')::date);
  elsif p_action='task_done' then
   update tasks set done=not done where task_id=(p_data->>'id')::uuid and (staff_id=me.profile_id or me.role='admin');
  elsif p_action='checklist' then
   if p_data->>'item' not in ('Sanitize desks','Test headsets','Check network','Count cues & balls','Stock drinks','Closing safety check') then raise exception 'Unknown checklist item.'; end if;
   delete from checklist c where c.staff_id=me.profile_id and c.day=(now() at time zone 'Asia/Kuala_Lumpur')::date and c.item=p_data->>'item';
   if not found then insert into checklist(checklist_id,staff_id,day,item)
    values(gen_random_uuid(),me.profile_id,(now() at time zone 'Asia/Kuala_Lumpur')::date,p_data->>'item'); end if;
  elsif p_action='handover' then
   if length(trim(p_data->>'title'))<2 or length(trim(p_data->>'note'))<2 then raise exception 'Enter a title and note.'; end if;
   insert into handovers(staff_id,title,note,category) values(me.profile_id,p_data->>'title',p_data->>'note',p_data->>'category');
  elsif p_action='ack' then
   if not exists(select 1 from handovers where handover_id=(p_data->>'id')::uuid and staff_id<>me.profile_id) then raise exception 'Acknowledge another colleague’s note.'; end if;
   insert into handover_reads values((p_data->>'id')::uuid,me.profile_id) on conflict do nothing;
  elsif p_action='ticket' then
   if length(trim(p_data->>'asset'))<2 or length(trim(p_data->>'title'))<2 then raise exception 'Enter the equipment and issue.'; end if;
   insert into tickets(staff_id,asset,title,priority) values(me.profile_id,p_data->>'asset',p_data->>'title',p_data->>'priority');
  else
   select * into tk from tickets where ticket_id=(p_data->>'id')::uuid for update;
   if tk.ticket_id is null then raise exception 'Issue not found.'; end if;
   if p_action='claim' and tk.status='Open' then
    insert into ticket_assignments(ticket_id,staff_id) values(tk.ticket_id,me.profile_id);
    update tickets set status='In progress' where ticket_id=tk.ticket_id;
   elsif p_action='resolve' and tk.status='In progress' and (exists(select 1 from ticket_assignments a where a.ticket_id=tk.ticket_id and a.staff_id=me.profile_id) or me.role='admin') and length(trim(p_data->>'resolution'))>=3 then
    update tickets set status='Resolved',resolution=p_data->>'resolution' where ticket_id=tk.ticket_id;
   elsif p_action='reopen' and tk.status='Resolved' and (tk.staff_id=me.profile_id or exists(select 1 from ticket_assignments a where a.ticket_id=tk.ticket_id and a.staff_id=me.profile_id) or me.role='admin') then
    delete from ticket_assignments where ticket_id=tk.ticket_id;
    update tickets set status='Open',resolution='' where ticket_id=tk.ticket_id;
   else raise exception 'This issue cannot be changed. Resolution requires a note and the assigned staff member.'; end if;
  end if;
 elsif p_action in ('save_resource','save_product','restock','save_event','save_reward','save_category','schedule','weekly_roster','void_bill','member_profile','cancel_shift','leave_decision','member_status','bracket','winner') then
  if me.role<>'admin' then raise exception 'Manager access required.'; end if;
  bid:=nullif(coalesce(case p_action when 'save_resource' then p_data->>'resource_id' when 'save_category' then p_data->>'category_id' when 'save_product' then p_data->>'product_id' when 'restock' then p_data->>'product_id' when 'save_event' then p_data->>'event_id' when 'save_reward' then p_data->>'reward_id' else null end,p_data->>'id'),'')::bigint;
  if p_action='save_resource' then
   if bid is not null then
    perform 1 from resources where resource_id=bid for update;
    if p_data->>'status'<>'Available' and exists(select 1 from bookings where resource_id=bid and status in ('Reserved','Confirmed','Active')) then raise exception 'Resolve upcoming bookings before taking a space offline.'; end if;
    update resources set name=trim(p_data->>'name'),kind=p_data->>'kind',zone=p_data->>'zone',description=p_data->>'description',rate=(p_data->>'rate')::int,capacity=(p_data->>'capacity')::int,status=p_data->>'status' where resource_id=bid;
   else insert into resources(name,kind,zone,description,rate,capacity) values(trim(p_data->>'name'),p_data->>'kind',p_data->>'zone',p_data->>'description',(p_data->>'rate')::int,(p_data->>'capacity')::int); end if;
  elsif p_action='save_category' then
   if bid is null then insert into categories(name) values(trim(p_data->>'name'));
   else update categories set name=trim(p_data->>'name') where category_id=bid; end if;
  elsif p_action='save_product' then
   if bid is null then insert into products(category_id,name,price,stock,threshold,image) values((p_data->>'category_id')::bigint,p_data->>'name',(p_data->>'price')::int,0,(p_data->>'threshold')::int,p_data->>'image');
   else update products set category_id=(p_data->>'category_id')::bigint,name=p_data->>'name',price=(p_data->>'price')::int,threshold=(p_data->>'threshold')::int,image=p_data->>'image',active=(p_data->>'active')::boolean where product_id=bid; end if;
  elsif p_action='restock' then
   n:=(p_data->>'quantity')::int;
   if n not between 1 and 10000 then raise exception 'Quantity must be 1–10000.'; end if;
   update products set stock=stock+n where product_id=bid;
   insert into stock_records(product_id,quantity,reason) values(bid,n,p_data->>'reason');
  elsif p_action='save_event' then
   if bid is null then insert into events(name,game,starts_at,ends_at,fee,capacity) values(p_data->>'name',p_data->>'game',(p_data->>'starts_at')::timestamptz,(p_data->>'ends_at')::timestamptz,(p_data->>'fee')::int,(p_data->>'capacity')::int);
   else
    perform 1 from events where event_id=bid for update;
    if exists(select 1 from registrations where event_id=bid and status='Registered') then raise exception 'Events with entrants cannot be edited or cancelled.'; end if;
    update events set name=p_data->>'name',game=p_data->>'game',starts_at=(p_data->>'starts_at')::timestamptz,ends_at=(p_data->>'ends_at')::timestamptz,fee=(p_data->>'fee')::int,capacity=(p_data->>'capacity')::int,status=p_data->>'status' where event_id=bid;
   end if;
  elsif p_action='save_reward' then
   if bid is null then insert into rewards(name,points,stock) values(p_data->>'name',(p_data->>'points')::int,(p_data->>'stock')::int);
   else update rewards set name=p_data->>'name',points=(p_data->>'points')::int,stock=(p_data->>'stock')::int,active=(p_data->>'active')::boolean where reward_id=bid; end if;
  elsif p_action='schedule' then
   uid:=coalesce(nullif(p_data->>'staff_id','')::uuid,nullif(p_data->>'user_id','')::uuid); st:=(p_data->>'starts_at')::timestamptz; en:=(p_data->>'ends_at')::timestamptz;
   if not exists(select 1 from profiles where profile_id=uid and active and role in ('staff','admin')) or en>st+interval '16 hours' or st<now() then raise exception 'Choose active staff and a future shift of at most 16 hours.'; end if;
   if exists(select 1 from leave_requests where staff_id=uid and status='Approved' and tstzrange(day::timestamp at time zone 'Asia/Kuala_Lumpur',(day+1)::timestamp at time zone 'Asia/Kuala_Lumpur','[)') && tstzrange(st,en,'[)')) then raise exception 'Employee is on approved leave.'; end if;
   insert into shifts(staff_id,starts_at,ends_at) values(uid,st,en);
  elsif p_action='weekly_roster' then
   uid:=coalesce(nullif(p_data->>'staff_id','')::uuid,nullif(p_data->>'user_id','')::uuid);
   perform 1 from profiles where profile_id=uid and active and role in ('staff','admin') for update;
   if not found then raise exception 'Choose an active staff member.'; end if;
   if p_data->>'pattern' not in ('Weekdays','Every day','Weekend') then raise exception 'Choose a working-days pattern.'; end if;
   for j in 0..6 loop
    day_value:=(p_data->>'day')::date+j;
    if (p_data->>'pattern'='Weekdays' and extract(isodow from day_value)>5) or (p_data->>'pattern'='Weekend' and extract(isodow from day_value)<6) then continue; end if;
    st:=(day_value+(p_data->>'start_time')::time) at time zone 'Asia/Kuala_Lumpur';
    en:=(day_value+(p_data->>'end_time')::time) at time zone 'Asia/Kuala_Lumpur';
    if en<=st then en:=en+interval '1 day'; end if;
    if st<now() or en>st+interval '16 hours' then raise exception 'Choose future shifts of at most 16 hours.'; end if;
    if exists(select 1 from leave_requests where staff_id=uid and status='Approved' and tstzrange(day::timestamp at time zone 'Asia/Kuala_Lumpur',(day+1)::timestamp at time zone 'Asia/Kuala_Lumpur','[)') && tstzrange(st,en,'[)')) then raise exception 'Employee is on leave. No shifts were created.'; end if;
    insert into shifts(staff_id,starts_at,ends_at) values(uid,st,en);
   end loop;
   result:=jsonb_build_object('message','Weekly roster created.');
  elsif p_action='void_bill' then
   select * into invoice from bills where bill_id=(p_data->>'bill_id')::uuid and status='Paid' for update;
   if invoice.bill_id is null or length(trim(p_data->>'reason'))<3 then raise exception 'Choose a paid bill and enter a reason.'; end if;
   select * into target from profiles where profile_id=invoice.user_id for update;
   if target.points<invoice.points then raise exception 'Earned points have already been spent. Cannot void this payment.'; end if;
   if invoice.kind='Booking' then
    if exists(select 1 from booking_extensions where booking_id=invoice.source_id) then raise exception 'An extended booking cannot be voided here.'; end if;
    update bookings set paid=false,status='Reserved' where booking_id=invoice.source_id and status='Confirmed' and starts_at>now();
   elsif invoice.kind='Order' then update orders set paid=false where order_id=invoice.source_id and status='Pending';
   elsif invoice.kind='Event' then
    update registrations set paid=false where registration_id=invoice.source_id and exists(select 1 from events where event_id=registrations.event_id and status='Open');
   else raise exception 'Session extensions cannot be voided after time has been allocated.'; end if;
   if not found then raise exception 'Only unfulfilled payments can be voided. Resolve used services with the manager.'; end if;
   update bills set status='Void',void_reason=p_data->>'reason' where bill_id=invoice.bill_id;
   update profiles set points=points-invoice.points,spent=spent-invoice.total where profile_id=invoice.user_id;
   insert into points_transactions(user_id,points,reason) values(invoice.user_id,-invoice.points,'Voided counter payment');
   result:=jsonb_build_object('message','Payment voided. Return any cash to the customer and reconcile the till.');
  elsif p_action='member_profile' then
   update profiles set name=trim(p_data->>'name'),phone=p_data->>'phone' where profile_id=coalesce(nullif(p_data->>'profile_id','')::uuid,nullif(p_data->>'user_id','')::uuid) and role='customer';
  elsif p_action='cancel_shift' then
   update shifts set status='Cancelled' where shift_id=(p_data->>'shift_id')::uuid and starts_at>now() and not exists(select 1 from attendance where shift_id=(p_data->>'shift_id')::uuid);
   if not found then raise exception 'Only upcoming shifts without attendance can be cancelled.'; end if;
  elsif p_action='leave_decision' then
   if p_data->>'status' not in ('Approved','Declined') then raise exception 'Invalid leave decision.'; end if;
   if p_data->>'status'='Approved' and exists(select 1 from shifts s join leave_requests l on l.staff_id=s.staff_id where l.leave_request_id=(p_data->>'leave_id')::uuid and s.status='Scheduled' and tstzrange(s.starts_at,s.ends_at,'[)') && tstzrange(l.day::timestamp at time zone 'Asia/Kuala_Lumpur',(l.day+1)::timestamp at time zone 'Asia/Kuala_Lumpur','[)')) then raise exception 'Cancel overlapping shifts before approving leave.'; end if;
   update leave_requests set status=p_data->>'status' where leave_request_id=(p_data->>'leave_id')::uuid and status='Pending';
  elsif p_action='member_status' then
   update profiles set active=(p_data->>'active')::boolean where profile_id=coalesce(nullif(p_data->>'profile_id','')::uuid,nullif(p_data->>'user_id','')::uuid) and role='customer';
  elsif p_action='bracket' then
   select * into ev from events where event_id=(p_data->>'event_id')::bigint for update;
   if ev.event_id is null or ev.status<>'Open' then raise exception 'Choose an open event.'; end if;
   if exists(select 1 from registrations where event_id=ev.event_id and status='Registered' and not paid) then raise exception 'Collect all entry fees before generating the bracket.'; end if;
   if exists(select 1 from registrations where event_id=ev.event_id and status='Registered' group by team having count(*)>1) then raise exception 'Each registered team needs a different name before the bracket can be generated.'; end if;
   select array_agg(team order by registration_id) into sides from registrations where event_id=ev.event_id and status='Registered';
   n:=coalesce(array_length(sides,1),0); if n<2 then raise exception 'At least two paid teams are required.'; end if;
   size:=2; while size<n loop size:=size*2; end loop;
   -- Spread byes: each first-round pair has at least one team.
   for j in 1..size/2 loop insert into matches(event_id,round,slot,team_a,team_b) values(ev.event_id,1,j,sides[j],sides[j+size/2]); end loop;
   rd:=2; size:=size/2;
   while size>1 loop for j in 1..size/2 loop insert into matches(event_id,round,slot) values(ev.event_id,rd,j); end loop; size:=size/2; rd:=rd+1; end loop;
   for m in select mm.* from matches mm where mm.event_id=ev.event_id and mm.round=1 and mm.team_b is null loop
    update matches set winner=m.team_a,done=true where match_id=m.match_id;
    if mod(m.slot,2)=1 then update matches set team_a=m.team_a where event_id=ev.event_id and round=2 and slot=(m.slot+1)/2;
    else update matches set team_b=m.team_a where event_id=ev.event_id and round=2 and slot=(m.slot+1)/2; end if;
   end loop;
   update events set status='Running' where event_id=ev.event_id;
  elsif p_action='winner' then
   select * into m from matches where match_id=(p_data->>'match_id')::bigint for update;
   winner_name:=trim(p_data->>'winner');
   if m.match_id is null or m.done or m.team_a is null or m.team_b is null or winner_name is null or winner_name='' or winner_name not in (m.team_a,m.team_b) then raise exception 'Choose a participant in an unfinished match.'; end if;
   update matches set winner=winner_name,done=true where match_id=m.match_id;
   if mod(m.slot,2)=1 then update matches set team_a=winner_name where event_id=m.event_id and round=m.round+1 and slot=(m.slot+1)/2;
   else update matches set team_b=winner_name where event_id=m.event_id and round=m.round+1 and slot=(m.slot+1)/2; end if;
   if not found then update events set status='Completed' where event_id=m.event_id; end if;
  end if;
 else raise exception 'Unknown action.';
 end if;
 insert into requests(user_id,request_id,result) values(me.profile_id,p_request,result);
 return result;
exception when exclusion_violation then raise exception 'That time overlaps an existing booking, event or shift. Choose another time.';
end $$;

-- No table is writable from the APK. Only validated RPCs can change state.
do $$ declare t text; begin
 foreach t in array array['profiles','resources','bookings','booking_extensions','categories','products','stock_records','orders','order_items','events','registrations','matches','bills','rewards','redemptions','points_transactions','favorites','shifts','attendance','leave_requests','tasks','checklist','handovers','handover_reads','tickets','ticket_assignments','requests'] loop
  execute format('alter table public.%I enable row level security',t);
  execute format('revoke all on public.%I from anon, authenticated',t);
 end loop;
end $$;
revoke all on function public.expire_bookings(), public.ctfly_catalog(), public.ctfly_snapshot(), public.ctfly_action(text,jsonb,uuid) from public,anon,authenticated;
revoke all on function public.ctfly_quote(text,uuid) from public,anon,authenticated;
grant execute on function public.ctfly_catalog() to anon,authenticated;
grant execute on function public.ctfly_snapshot(), public.ctfly_action(text,jsonb,uuid) to anon, authenticated;
grant execute on function public.ctfly_quote(text,uuid) to anon, authenticated;

insert into resources(name,kind,zone,description,rate) select 'PC-'||lpad(i::text,2,'0'),'PC',case when i>12 then 'VIP' else 'Standard' end,case when i>12 then 'RTX 4070 · Ryzen 7 · 240 Hz' else 'RTX 3060 · Ryzen 5 · 144 Hz' end,case when i>12 then 1000 else 600 end from generate_series(1,16) i;
insert into resources(name,kind,zone,description,capacity,rate) values
('Snooker Club 01','Snooker','Social','Full-size table · cues included',4,2200),('Pool Social 01','Pool','Social','American pool · bring your crew',4,1800),('Pool Social 02','Pool','Social','American pool · bring your crew',4,1800),('Console Suite 01','Console','Lounge','PS5 · couch co-op · 4 controllers',4,2500),('Console Suite 02','Console','Lounge','PS5 · couch co-op · 4 controllers',4,2500),('Racing Rig 01','Racing','Simulator','Wheel + pedals · solo time trials',1,2000);
insert into categories(name) values('Drinks'),('Food'),('Game cards');
insert into products(category_id,name,price,stock,image) values
(1,'Iced coffee',650,24,'iced_coffee'),(1,'Energy drink',800,18,'energy_drink'),(1,'Mineral water',250,30,'mineral_water'),(1,'Iced lemon tea',550,20,'iced_lemon_tea'),(2,'Cup noodles',550,12,'cup_noodles'),(2,'Potato chips',450,14,'potato_chips'),(2,'Chicken burger',1250,12,'chicken_burger'),(2,'French fries',600,20,'fries'),(3,'Steam RM 20 card',2000,6,'game_card');
insert into stock_records(product_id,quantity,reason) select product_id,stock,'Opening stock' from products;
insert into events(name,game,starts_at,ends_at,fee,capacity) values('Friday Night Valorant','Valorant',date_trunc('day',now())+interval '7 days 10 hours',date_trunc('day',now())+interval '7 days 14 hours',1500,8),('Weekend FC Cup','EA Sports FC',date_trunc('day',now())+interval '9 days 10 hours',date_trunc('day',now())+interval '9 days 14 hours',1000,8);
insert into rewards(name,points,stock) values('1 hour Standard PC voucher',100,20),('Free iced coffee',70,15),('CTFLY mouse pad',250,8);

-- Section: session-clock
-- Expire sessions in the background when Supabase Cron is available.
do $$
begin
 if not exists (select 1 from pg_available_extensions where name='pg_cron') then return; end if;
 create extension if not exists pg_cron;
 perform cron.schedule('ctfly-session-clock','* * * * *','select public.expire_bookings()');
end $$;

-- Section: venue-campus
-- If the Private Room upgrade is already installed, leave the current setup in place.

do $$
declare
 definition text;
 original_guard text:='n not in (60,90,120,180)';
 replacement_guard text:='((r.kind=''GoKart'' and n not in (10,15,30,60)) or (r.kind=''Arcade'' and n not in (30,60,90,120)) or (r.kind not in (''GoKart'',''Arcade'') and n not in (60,90,120,180)))';
 new_name text;
 room record;
 existing_count int;
 serial int;
 candidate text;
begin
 lock table public.resources in access exclusive mode;
 if exists (
  select 1 from pg_constraint
  where conrelid='public.resources'::regclass and conname='resources_vip_room_capacity_check'
 ) or exists (select 1 from public.resources where kind='VIPRoom') then
  raise notice 'Private Room upgrade detected. Campus setup skipped.';
  return;
 end if;

 alter table public.resources drop constraint if exists resources_kind_check;
 alter table public.resources add constraint resources_kind_check
  check(kind in ('PC','Snooker','Pool','Console','Racing','Arcade','GoKart'));

 -- Update only the booking duration guard, preserving authorization and transaction behavior.
 definition:=pg_get_functiondef('public.ctfly_action(text,jsonb,uuid)'::regprocedure);
 if position(replacement_guard in definition)>0 then null;
 elsif position(original_guard in definition)>0 then
  execute replace(definition,original_guard,replacement_guard);
 else raise exception 'Unexpected booking function version. Nothing changed; contact the developer.';
 end if;

 -- Give the original demo tables their new room names, without recreating their IDs.
 update public.resources r set name='Pool Club 01' where r.name='Pool Social 01'
  and not exists(select 1 from public.resources where name='Pool Club 01');
 update public.resources r set name='Pool Club 02' where r.name='Pool Social 02'
  and not exists(select 1 from public.resources where name='Pool Club 02');
 for i in 13..16 loop
  new_name:='VIP Rig '||lpad((i-12)::text,2,'0');
  update public.resources r set name=new_name where r.name='PC-'||lpad(i::text,2,'0') and r.zone='VIP'
   and not exists(select 1 from public.resources where name=new_name);
 end loop;

 update public.resources set zone=case kind
  when 'Pool' then 'L2 Pool Club' when 'Snooker' then 'L2 Snooker Hall'
  when 'Console' then 'G Console Suites' when 'Racing' then 'G Simulator Lab'
  when 'Arcade' then 'G Arcade' when 'GoKart' then 'Outdoor Circuit' else zone end;
 update public.resources set description=case
  when kind='PC' and zone='VIP' then 'L1 · VIP acoustic room · premium rig · 240 Hz'
  when kind='PC' then 'L1 · dedicated esports floor · mechanical keyboard · gaming headset'
  when kind='Pool' then 'L2 · Pool Club · professional blue-cloth table · cues included'
  when kind='Snooker' then 'L2 · Snooker Hall · full-size green-cloth table · cues included'
  when kind='Console' then 'G · private console suite · PS5 · couch co-op · 4 controllers'
  when kind='Racing' then 'G · Simulator Lab · wheel & pedals · racing cockpit'
  else description end;

 -- Top up each physical room to its target. Maintenance units count as existing equipment.
 for room in select * from (values
  ('PC','Standard',24,1,600,'PC-','L1 · Esports arena · gaming rig · keyboard & headset'),
  ('PC','VIP',8,1,1000,'VIP Rig ','L1 · VIP acoustic room · premium rig · 240 Hz'),
  ('Pool','L2 Pool Club',8,4,1800,'Pool Club ','L2 · professional blue-cloth table · cues included'),
  ('Snooker','L2 Snooker Hall',4,4,2200,'Snooker Club ','L2 · full-size table · dedicated cue clearance'),
  ('Console','G Console Suites',4,4,2500,'Console Suite ','G · private suite · PS5 · four controllers'),
  ('Racing','G Simulator Lab',4,1,2000,'Racing Rig ','G · wheel, pedals & cockpit · solo time trials'),
  ('Arcade','G Arcade',6,1,800,'Arcade Station ','G · arcade cabinet · reserved play station'),
  ('GoKart','Outdoor Circuit',6,1,6000,'Kart ','Outdoor · single-driver kart · arrive at the pit lane')
 ) as x(kind,zone,target,capacity,rate,prefix,description) loop
  select count(*) into existing_count from public.resources
   where kind=room.kind and zone=room.zone and status<>'Retired';
  serial:=1;
  while existing_count<room.target loop
   candidate:=room.prefix||lpad(serial::text,2,'0');
   serial:=serial+1;
   if exists(select 1 from public.resources where name=candidate) then continue; end if;
   insert into public.resources(name,kind,zone,description,capacity,rate)
    values(candidate,room.kind,room.zone,room.description,room.capacity,room.rate);
   existing_count:=existing_count+1;
  end loop;
 end loop;
end $$;

-- Fresh original demo inventory becomes 64 total facilities:
-- 24 standard PCs + 8 VIP PCs + 8 pool + 4 snooker + 4 console + 4 racing + 6 arcade + 6 karts.
select kind,zone,count(*) as facilities from public.resources
 where status<>'Retired' group by kind,zone order by kind,zone;

-- Section: private-rooms
-- Keeps existing IDs, bookings, prices already charged, tokens and payments.
-- Proposed whole-room hourly rates: solo RM15, duo RM28, squad RM52.
-- Capacity is installed PCs, not a per-person price. Three guests use a four-PC room.
-- Reapplying this setup leaves existing inventory alone.
do $$
declare
 r record;
 slot int:=0;
 cap int;
 zone_name text;
 room_name text;
 number int;
 room record;
 existing_count int;
 serial int;
 candidate text;
begin
 lock table public.resources in access exclusive mode;
 if exists (
  select 1 from pg_constraint
  where conrelid='public.resources'::regclass and conname='resources_vip_room_capacity_check'
 ) then
  raise notice 'Private Room setup is already installed. Inventory setup skipped.';
  return;
 end if;

 alter table public.resources drop constraint if exists resources_kind_check;
 alter table public.resources add constraint resources_kind_check
  check(kind in ('PC','VIPRoom','Snooker','Pool','Console','Racing','Arcade','GoKart'));
 alter table public.resources add constraint resources_vip_room_capacity_check
  check(kind<>'VIPRoom' or capacity between 1 and 4);

 -- Convert the original solo VIP rigs while keeping their existing resource IDs.
 for r in select * from public.resources where kind='PC' and zone='VIP'
   and capacity=1 and name ~ '^VIP Rig [0-9]{2}$' order by name,resource_id loop
  slot:=slot+1;
  if slot>9 then continue; end if;
  cap:=case when slot<=3 then 1 when slot<=6 then 2 else 4 end;
  zone_name:=case cap when 1 then 'Solo' when 2 then 'Duo' else 'Squad' end;
  number:=(slot-1)%3+1;
  room_name:='VIP Room '||zone_name||' '||lpad(number::text,2,'0');
  if exists(select 1 from public.resources where name=room_name and resource_id<>r.resource_id) then
   raise exception 'Room name % already exists. No changes applied; resolve the name first.',room_name;
  end if;
  update public.resources set kind='VIPRoom',name=room_name,zone=zone_name,capacity=cap,
   rate=case cap when 1 then 1500 when 2 then 2800 else 5200 end,
   description='L1 · Private room · '||cap||' gaming PC(s) · price for the whole room'
   where resource_id=r.resource_id;
 end loop;

 -- Keep the existing racing resource IDs and rates.
 update public.resources set zone='Standard',description='G · Racing simulator · ultrawide monitor, wheel, pedals and aluminium cockpit'
  where kind='Racing' and zone='G Simulator Lab';

 for room in select * from (values
  ('VIPRoom','Solo',3,1,1500,'VIP Room Solo ','L1 · Private room · 1 gaming PC · whole-room hourly price'),
  ('VIPRoom','Duo',3,2,2800,'VIP Room Duo ','L1 · Private room · 2 gaming PCs · whole-room hourly price'),
  ('VIPRoom','Squad',3,4,5200,'VIP Room Squad ','L1 · Private room · 4 gaming PCs · whole-room hourly price'),
  ('Racing','Standard',4,1,2000,'Racing Simulator ','G · Standard simulator · ultrawide display, wheel, pedals and aluminium cockpit'),
  ('Racing','VIP',4,1,3500,'VIP Racing Simulator ','G · Private simulator booth · direct-drive wheel, shifter, pedals and bucket seat')
 ) as x(kind,zone,target,capacity,rate,prefix,description) loop
  select count(*) into existing_count from public.resources where kind=room.kind and zone=room.zone and status<>'Retired';
  serial:=1;
  while existing_count<room.target loop
   candidate:=room.prefix||lpad(serial::text,2,'0'); serial:=serial+1;
   if exists(select 1 from public.resources where name=candidate) then continue; end if;
   insert into public.resources(name,kind,zone,capacity,rate,description)
    values(candidate,room.kind,room.zone,room.capacity,room.rate,room.description);
   existing_count:=existing_count+1;
  end loop;
 end loop;
end $$;
select kind,zone,capacity,rate,count(*) from public.resources
 where kind in ('VIPRoom','Racing') and status<>'Retired' group by kind,zone,capacity,rate order by kind,capacity,zone;

-- Section: settings-feedback
create table if not exists public.app_feedback (
 feedback_id uuid primary key default gen_random_uuid(),
 user_id uuid not null references public."User"(user_id),
 title text not null check(char_length(btrim(title)) between 3 and 120),
 body text not null check(char_length(btrim(body)) between 10 and 2000),
 category text not null check(category in ('Issue','Suggestion','Other')),
 rating int not null check(rating between 1 and 5),
 status text not null default 'New' check(status in ('New','Reviewed','Resolved')),
 created_at timestamptz not null default now(),
 updated_at timestamptz,
 updated_by uuid references public.staff(staff_id)
);
create index if not exists app_feedback_created_idx on public.app_feedback(created_at desc);
create index if not exists app_feedback_user_idx on public.app_feedback(user_id,created_at desc);
alter table public.app_feedback enable row level security;
revoke all on public.app_feedback from public,anon,authenticated;

-- Extend the existing authenticated snapshot; customer sees only their own submissions.
do $$
declare definition text; addition text := $patch$
 -- app_feedback_snapshot_v1
 result := result || jsonb_build_object('feedback',coalesce((select jsonb_agg(f order by f.created_at desc)
 from (select a.*,p.name as customer_name from app_feedback a join profiles p on p.profile_id=a.user_id
 where a.user_id=me.profile_id or crew order by a.created_at desc limit 300) f),'[]'::jsonb));
 return result;$patch$;
begin
 definition:=pg_get_functiondef('public.ctfly_snapshot()'::regprocedure);
 if position('-- app_feedback_snapshot_v1' in definition)=0 then
  if position('return result;' in definition)=0 then raise exception 'Unexpected snapshot version; no changes applied.'; end if;
  execute replace(definition,'return result;',addition);
 end if;
end $$;

-- Reuse the existing per-account transaction lock and idempotent request IDs.
do $$
declare definition text; addition text := $patch$
 -- app_feedback_actions_v1
 if p_action='feedback' then
  if me.role<>'customer' then raise exception 'Use a customer account to send feedback.'; end if;
  if coalesce(char_length(btrim(p_data->>'title')),0) not between 3 and 120
   or coalesce(char_length(btrim(p_data->>'body')),0) not between 10 and 2000
   or coalesce(p_data->>'category','') not in ('Issue','Suggestion','Other')
   or coalesce((p_data->>'rating')::int,0) not between 1 and 5 then
   raise exception 'Choose a category and rating, a title of 3-120 characters, and feedback of 10-2000 characters.';
  end if;
  insert into app_feedback(user_id,title,body,category,rating)
   values(me.profile_id,btrim(p_data->>'title'),btrim(p_data->>'body'),p_data->>'category',(p_data->>'rating')::int)
   returning feedback_id into oid;
  result:=jsonb_build_object('message','Feedback sent. Thank you!','id',oid);
 elsif p_action='feedback_status' then
  if me.role not in ('staff','admin') then raise exception 'Staff access required.'; end if;
  if coalesce(p_data->>'status','') not in ('Reviewed','Resolved') then raise exception 'Choose Reviewed or Resolved.'; end if;
  update app_feedback set status=p_data->>'status',updated_at=now(),updated_by=me.profile_id
   where feedback_id=(p_data->>'id')::uuid;
  if not found then raise exception 'Feedback no longer exists.'; end if;
  result:=jsonb_build_object('message','Feedback status updated.');
 elsif p_action='book' then$patch$;
begin
 definition:=pg_get_functiondef('public.ctfly_action(text,jsonb,uuid)'::regprocedure);
 if position('-- app_feedback_actions_v1' in definition)=0 then
  if position('if p_action=''book'' then' in definition)=0 then raise exception 'Unexpected action version; no changes applied.'; end if;
  execute replace(definition,'if p_action=''book'' then',addition);
 end if;
end $$;

-- Section: online-checkout
-- School-project payment simulator. No financial provider, credentials or real funds.
create schema if not exists private;
revoke all on schema private from public,anon,authenticated;
create function private.project_url() returns text
language sql immutable set search_path = '' as $$
 select ''::text; -- Set to this Supabase project's HTTPS URL to enable worker dispatch and poster uploads.
$$;
alter table public.profiles add column test_points int not null default 0 check(test_points>=0);
alter table public.profiles add column test_spent int not null default 0 check(test_spent>=0);
alter table public.bills add column environment text not null default 'counter' check(environment in ('counter','test'));
alter table public.bills add column payment_reference text;
alter table public.bookings add column hold_expires_at timestamptz;
alter table public.orders add column hold_expires_at timestamptz;
alter table public.registrations add column hold_expires_at timestamptz;
alter table public.bookings alter column hold_expires_at set default now()+interval '10 minutes';
alter table public.orders alter column hold_expires_at set default now()+interval '10 minutes';
alter table public.registrations alter column hold_expires_at set default now()+interval '10 minutes';
create table public.test_payments (
 payment_id uuid primary key default gen_random_uuid(), user_id uuid not null references public."User"(user_id),
 kind text not null check(kind in ('Booking','Order','Event')), source_id uuid not null,
 method text not null check(method in ('TNG','FPX','Card')),
 environment text not null default 'test' check(environment='test'),
 provider text not null default 'simulator' check(provider='simulator'),
 currency text not null default 'MYR' check(currency='MYR'),
 reference text not null unique default 'CTFLY-'||upper(replace(gen_random_uuid()::text,'-','')),
 subtotal int not null check(subtotal>=0), discount int not null check(discount between 0 and subtotal),
 total int not null check(total=subtotal-discount), points int not null check(points>=0),
 items jsonb not null, status text not null default 'Pending'
 check(status in ('Pending','Processing','Succeeded','Failed','Cancelled','Expired','Refunded')),
 expires_at timestamptz not null, created_at timestamptz not null default now(), settled_at timestamptz,
 bill_id uuid unique references public.bills(bill_id));
create unique index one_active_test_checkout on public.test_payments(kind,source_id) where status in ('Pending','Processing');
create index test_payments_user_created on public.test_payments(user_id,created_at desc);
create index test_payments_status_expiry on public.test_payments(status,expires_at);
alter table public.test_payments enable row level security;
revoke all on public.test_payments from public,anon,authenticated;

create function private.restore_test_order(p_id uuid) returns void
language plpgsql set search_path=public, extensions, pg_temp as $$
declare i record;
begin
 for i in select product_id,qty from order_items where order_id=p_id order by product_id loop
  update products set stock=stock+i.qty where product_id=i.product_id;
  insert into stock_records(product_id,quantity,reason) values(i.product_id,i.qty,'Released checkout hold');
 end loop;
end $$;

create function private.expire_test_holds() returns void
language plpgsql set search_path=public, extensions, pg_temp as $$
declare held record;
begin
 update bookings set status='Expired' where not paid and status='Reserved' and hold_expires_at<=now();
 for held in select order_id from orders where not paid and status='Pending' and hold_expires_at<=now() order by order_id for update loop
  update orders set status='Cancelled' where order_id=held.order_id;
  perform private.restore_test_order(held.order_id);
 end loop;
 update registrations set status='Withdrawn' where not paid and status='Registered' and hold_expires_at<=now();
 update test_payments p set status=case when p.expires_at<=now() then 'Expired' else 'Cancelled' end
 where p.status in ('Pending','Processing') and (p.expires_at<=now()
 or (p.kind='Booking' and not exists(select 1 from bookings b where b.booking_id=p.source_id and not b.paid and b.status='Reserved'))
 or (p.kind='Order' and not exists(select 1 from orders o where o.order_id=p.source_id and not o.paid and o.status='Pending'))
 or (p.kind='Event' and not exists(select 1 from registrations r where r.registration_id=p.source_id and not r.paid and r.status='Registered')));
end $$;

-- Caller already owns the account lock; source locks precede payment locks.
create function private.test_quote(p_kind text,p_id uuid,p_uid uuid) returns jsonb
language plpgsql set search_path=public, extensions, pg_temp as $$
declare amount int; deadline timestamptz; lines jsonb; spending int; deduction int;
begin
 if p_kind='Booking' then
  select b.total,coalesce(b.hold_expires_at,now()+interval '10 minutes'),jsonb_build_array(jsonb_build_object('name',r.name,'qty',b.guests,'detail',b.starts_at,'price',b.total))
   into amount,deadline,lines from bookings b join resources r on r.resource_id=b.resource_id
   where b.booking_id=p_id and b.user_id=p_uid and not b.paid and b.status='Reserved' and b.ends_at>now() for update of b;
  update bookings set hold_expires_at=deadline where booking_id=p_id and user_id=p_uid and hold_expires_at is null;
 elsif p_kind='Order' then
  select total,coalesce(hold_expires_at,now()+interval '10 minutes') into amount,deadline from orders
   where order_id=p_id and user_id=p_uid and not paid and status='Pending' for update;
  select jsonb_agg(jsonb_build_object('name',name,'qty',qty,'price',price)) into lines from order_items where order_id=p_id;
  update orders set hold_expires_at=deadline where order_id=p_id and user_id=p_uid and hold_expires_at is null;
 elsif p_kind='Event' then
  select r.fee,least(coalesce(r.hold_expires_at,now()+interval '10 minutes'),e.starts_at),jsonb_build_array(jsonb_build_object('name',e.name,'qty',1,'price',r.fee))
   into amount,deadline,lines from registrations r join events e on e.event_id=r.event_id
   where r.registration_id=p_id and r.user_id=p_uid and not r.paid and r.status='Registered' and e.status='Open' and e.starts_at>now() for update of r,e;
  update registrations set hold_expires_at=deadline where registration_id=p_id and user_id=p_uid and hold_expires_at is null;
 else raise exception 'Choose Booking, Order or Event.'; end if;
 if amount is null or deadline<=now() then raise exception 'Already paid, cancelled or checkout hold expired.'; end if;
 select spent into spending from profiles where profile_id=p_uid;
 deduction:=amount*(case when spending>=100000 then 10 when spending>=30000 then 5 else 0 end)/100;
 return jsonb_build_object('environment','test','provider','simulator','kind',p_kind,'source_id',p_id,'subtotal',amount,'discount',deduction,'total',amount-deduction,'points',(amount-deduction)/100,'items',lines,'expires_at',deadline);
end $$;

create function private.test_payment_action(p_action text,p_data jsonb) returns jsonb
language plpgsql set search_path=public, extensions, pg_temp as $$
declare me profiles; owner profiles; pay test_payments; q jsonb; chosen_kind text; sid uuid; bid uuid; outcome text; allowed boolean;
begin
 select * into me from profiles where profile_id=public.ctfly_uid() and active;
 if me.profile_id is null then raise exception 'An active CTFLY account is required.'; end if;
 if p_action in ('test_quote','test_checkout') then
  if me.role<>'customer' then raise exception 'Use a customer account to pay online.'; end if;
  chosen_kind:=p_data->>'kind'; sid:=(p_data->>'id')::uuid;
  q:=private.test_quote(chosen_kind,sid,me.profile_id);
  if p_action='test_quote' then return q||jsonb_build_object('message','No money will be charged.'); end if;
  if coalesce(p_data->>'method','') not in ('TNG','FPX','Card') then raise exception 'Choose a payment method.'; end if;
  if (p_data->>'expected_total')::int is distinct from (q->>'total')::int then raise exception 'Amount changed. Reload checkout.'; end if;
  select * into pay from test_payments where test_payments.kind=chosen_kind and source_id=sid and user_id=me.profile_id and status in ('Pending','Processing') for update;
  if pay.payment_id is null then
   insert into test_payments(user_id,kind,source_id,method,subtotal,discount,total,points,items,expires_at)
   values(me.profile_id,chosen_kind,sid,p_data->>'method',(q->>'subtotal')::int,(q->>'discount')::int,(q->>'total')::int,(q->>'points')::int,q->'items',(q->>'expires_at')::timestamptz) returning * into pay;
  end if;
 else
  select * into pay from test_payments where payment_id=(p_data->>'id')::uuid;
  if pay.payment_id is null or (pay.user_id<>me.profile_id and not(p_action='test_refund' and me.role in ('staff','admin'))) then raise exception 'Payment not found.'; end if;
  select * into owner from profiles where profile_id=pay.user_id for update;
  -- Lock source first, including terminal rows, to serialize counter collection and refunds.
  if pay.kind='Booking' then perform 1 from bookings where booking_id=pay.source_id for update;
  elsif pay.kind='Order' then perform 1 from orders where order_id=pay.source_id for update;
  else perform 1 from registrations where registration_id=pay.source_id for update;
   perform 1 from events where event_id=(select event_id from registrations where registration_id=pay.source_id) for update; end if;
  select * into pay from test_payments where payment_id=pay.payment_id for update;
  if p_action='test_refund' then
   if pay.status='Refunded' then return jsonb_build_object('payment',to_jsonb(pay),'message','Payment already refunded.'); end if;
   if pay.status<>'Succeeded' then raise exception 'Only completed payments can be refunded.'; end if;
   if pay.kind='Booking' then update bookings set paid=false,status='Cancelled' where booking_id=pay.source_id and paid and status='Confirmed' and starts_at>now() and not exists(select 1 from booking_extensions where booking_id=pay.source_id); allowed:=found;
   elsif pay.kind='Order' then update orders set paid=false,status='Cancelled' where order_id=pay.source_id and paid and status='Pending'; allowed:=found;
    if allowed then perform private.restore_test_order(pay.source_id); end if;
   else update registrations set paid=false,status='Withdrawn' where registration_id=pay.source_id and paid and status='Registered' and exists(select 1 from events where event_id=registrations.event_id and status='Open' and starts_at>now()); allowed:=found;
   end if;
   if not allowed then raise exception 'This service has already started or been fulfilled. A refund is unavailable.'; end if;
   update profiles set test_points=test_points-pay.points,test_spent=test_spent-pay.total where profile_id=pay.user_id;
   update bills set status='Void',void_reason='Payment refunded.' where bill_id=pay.bill_id and environment='test';
   update test_payments set status='Refunded',settled_at=now() where payment_id=pay.payment_id returning * into pay;
  elsif p_action='test_resolve' then
   outcome:=p_data->>'outcome';
   if coalesce(outcome,'') not in ('Success','Failure','Pending','Cancel') then raise exception 'Invalid payment status.'; end if;
   if pay.status in ('Pending','Processing') then
    q:=private.test_quote(pay.kind,pay.source_id,pay.user_id);
    if pay.expires_at<=now() then raise exception 'Checkout hold expired.'; end if;
    if outcome='Success' then
     if (q->>'subtotal')::int<>pay.subtotal then raise exception 'Amount changed. Cancel and restart checkout.'; end if;
     insert into bills(user_id,kind,source_id,subtotal,discount,total,points,method,environment,payment_reference)
      values(pay.user_id,pay.kind,pay.source_id,pay.subtotal,pay.discount,pay.total,pay.points,pay.method,'test',pay.reference) returning bill_id into bid;
     update profiles set test_points=test_points+pay.points,test_spent=test_spent+pay.total where profile_id=pay.user_id;
     if pay.kind='Booking' then update bookings set paid=true,status='Confirmed' where booking_id=pay.source_id;
     elsif pay.kind='Order' then update orders set paid=true where order_id=pay.source_id;
     else update registrations set paid=true where registration_id=pay.source_id; end if;
     update test_payments set status='Succeeded',bill_id=bid,settled_at=now() where payment_id=pay.payment_id returning * into pay;
    else update test_payments set status=case outcome when 'Failure' then 'Failed' when 'Cancel' then 'Cancelled' else 'Processing' end where payment_id=pay.payment_id returning * into pay;
    end if;
   end if;
  else raise exception 'Unknown payment action.'; end if;
 end if;
 return jsonb_build_object('payment',to_jsonb(pay),'message','No money was charged.','receipt',(select to_jsonb(b) from bills b where b.bill_id=pay.bill_id));
end $$;
revoke all on all functions in schema private from public,anon,authenticated;

-- Keep the existing authenticated API and its request deduplication.
do $$ declare definition text; begin
 definition:=pg_get_functiondef('public.expire_bookings()'::regprocedure);
 if position('language sql' in lower(definition))=0 then raise exception 'Unexpected expiry function.'; end if;
 definition:=replace(definition,'AS $function$', 'AS $function$'||chr(10)||' select private.expire_test_holds();');
 execute definition;
 definition:=pg_get_functiondef('public.ctfly_action(text,jsonb,uuid)'::regprocedure);
 if position('if p_action=''feedback'' then' in definition)=0 then raise exception 'Apply feedback migration first.'; end if;
 definition:=replace(definition,'if p_action=''feedback'' then', 'if p_action in (''test_quote'',''test_checkout'',''test_resolve'',''test_refund'') then result:=private.test_payment_action(p_action,p_data); elsif p_action=''feedback'' then');
 definition:=replace(definition,'select * into target from profiles where profile_id=invoice.user_id for update;', 'if invoice.environment=''test'' then raise exception ''Open payment details to request a refund.''; end if; select * into target from profiles where profile_id=invoice.user_id for update;');
 -- Re-registration receives a fresh hold; event result supplies its source ID.
 definition:=replace(definition,'fee=excluded.fee where registrations.status=''Withdrawn''','fee=excluded.fee,hold_expires_at=now()+interval ''10 minutes'' where registrations.status=''Withdrawn''');
 definition:=replace(definition,'if not found then raise exception ''You are already registered.''; end if;', 'if not found then raise exception ''You are already registered.''; end if; select registration_id into oid from registrations where event_id=ev.event_id and user_id=me.profile_id; result:=jsonb_build_object(''id'',oid,''message'',''Registered. Complete checkout.'');');
 execute definition;
 definition:=pg_get_functiondef('public.ctfly_snapshot()'::regprocedure);
 definition:=replace(definition,'return result;', $patch$
 result:=result||jsonb_build_object('payment_mode','simulation','test_payments',coalesce((select jsonb_agg(x order by x.created_at desc) from
 (select t.*,p.name as customer_name,(select to_jsonb(b) from bills b where b.bill_id=t.bill_id) as receipt from test_payments t join profiles p on p.profile_id=t.user_id where t.user_id=me.profile_id or crew order by t.created_at desc limit 300) x),'[]'::jsonb));
 return result;$patch$);
 execute definition;
end $$;

-- Section: booking-events-receipts
-- Native booking UX, staff competitions/codes, immutable receipts and server email outbox.
alter table public.events add column description text not null default '' check(length(description)<=3000);
alter table public.events add column poster_url text not null default '';
alter table public.events add column prize_first text not null default 'To be announced' check(length(prize_first)<=800);
alter table public.events add column prize_second text not null default 'To be announced' check(length(prize_second)<=800);
alter table public.events add column prize_third text not null default 'To be announced' check(length(prize_third)<=800);
alter table public.events add column prize_consolation text not null default 'To be announced' check(length(prize_consolation)<=800);
alter table public.events add column registration_closes_at timestamptz;
alter table public.events add column created_by uuid references public.staff(staff_id);
create index events_creator_idx on public.events(created_by);
alter table public.events drop constraint events_status_check;
alter table public.events add constraint events_status_check check(status in ('Draft','Open','Running','Completed','Cancelled'));

create function private.require_number(d jsonb,k text,label text,lo int,hi int) returns int
language plpgsql set search_path=public, extensions, pg_temp as $$
declare n numeric;
begin
 if coalesce(d->>k,'')!~'^-?[0-9]+$' then raise exception '%: enter a whole number.',label; end if;
 n:=(d->>k)::numeric;
 if n<lo or n>hi then raise exception '%: use a value between % and %.',label,lo,hi; end if;
 return n::int;
end $$;

create function private.validate_club_input(a text,d jsonb) returns void
language plpgsql set search_path=public, extensions, pg_temp as $$
declare f record; label text; st timestamptz; en timestamptz; r resources; day date;
begin
 for f in select * from (values
 ('save_resource','name','Space name',1,80),('save_resource','description','Specifications / description',3,2000),
 ('save_product','name','Product name',1,120),('save_category','name','Category name',1,80),
 ('save_reward','name','Reward name',1,120),('register_event','team','Team name',1,80),
 ('task','title','Mission',3,200),('handover','title','Title',3,200),('handover','note','Handover details',3,2000),
 ('ticket','asset','PC / equipment name',2,120),('ticket','title','What needs attention?',3,2000),
 ('resolve','resolution','What was fixed?',3,2000),('leave','reason','Reason',3,2000),
 ('void_bill','reason','Reason / cash refund note',3,2000),('profile','name','Full name',2,80),('member_profile','name','Member name',2,80)
 ) x(action,key,label,minlen,maxlen) where action=a loop
  if coalesce(length(btrim(d->>f.key)),0) not between f.minlen and f.maxlen then raise exception '%: enter %–% characters.',f.label,f.minlen,f.maxlen; end if;
 end loop;
 if a='book' then
  perform private.require_number(d,'minutes','Duration (minimum 30 minutes)',30,180);
  perform private.require_number(d,'guests','Players / group size',1,20);
  begin st:=(d->>'starts_at')::timestamptz; exception when others then raise exception 'Reservation date / start time is invalid. Choose a valid date, hour and minute.'; end;
  if st is null then raise exception 'Reservation date / start time is required.'; end if;
  day:=(st at time zone 'Asia/Kuala_Lumpur')::date;
  if day<(now() at time zone 'Asia/Kuala_Lumpur')::date then raise exception 'Reservation date is in the past. Choose today or a future date.'; end if;
  if day>(now() at time zone 'Asia/Kuala_Lumpur')::date+90 then raise exception 'Reservation date must be within the next 90 days.'; end if;
  if st<now()-interval '1 minute' then raise exception 'Start time has already passed. Choose the current time or a later time.'; end if;
  select * into r from resources where resource_id=(d->>'resource_id')::bigint for update;
  if r.resource_id is null or r.status<>'Available' then raise exception 'Selected space is unavailable. Refresh and choose another space.'; end if;
  if (d->>'guests')::int>r.capacity then raise exception 'Players / group size exceeds this space capacity of %.',r.capacity; end if;
  if (r.kind='GoKart' and (d->>'minutes')::int not in (30,60)) or (r.kind='Arcade' and (d->>'minutes')::int not in (30,60,90,120)) or (r.kind not in ('GoKart','Arcade') and (d->>'minutes')::int not in (30,60,90,120,180)) then raise exception 'Duration is unavailable for this activity. Choose one of the listed durations (minimum 30 minutes).'; end if;
 elsif a='save_resource' then
  perform private.require_number(d,'capacity','Player capacity',1,20);perform private.require_number(d,'rate','Hourly rate',1,10000000);
 elsif a='save_product' then
  perform private.require_number(d,'price','Product price',0,10000000);perform private.require_number(d,'stock','Stock quantity',0,1000000);perform private.require_number(d,'threshold','Low-stock threshold',0,1000000);
 elsif a='save_reward' then
  perform private.require_number(d,'points','Points required',1,10000000);perform private.require_number(d,'stock','Available quantity',0,1000000);
 elsif a='restock' then
  perform private.require_number(d,'quantity','Restock quantity',1,1000000);
 elsif a in ('schedule','save_event') then
  begin st:=(d->>'starts_at')::timestamptz; exception when others then raise exception 'Start date / time is invalid.'; end;
  begin en:=(d->>'ends_at')::timestamptz; exception when others then raise exception 'End date / time is invalid.'; end;
  if st is null or en is null then raise exception 'Start and end date / time are required.'; end if;
  if st<now() then raise exception 'Start date / time has already passed.'; end if;
  if en<=st then raise exception 'End time must be later than start time.'; end if;
  if a='schedule' and en>st+interval '16 hours' then raise exception 'Shift duration cannot exceed 16 hours.'; end if;
 end if;
end $$;

create function private.save_club_event(d jsonb) returns jsonb
language plpgsql set search_path=public, extensions, pg_temp as $$
declare me profiles; old events; eid bigint; st timestamptz; en timestamptz; close_at timestamptz; fee_value int; cap int; state text; poster text; k text;
begin
 select * into me from profiles where profile_id=public.ctfly_uid() and active and role in ('staff','admin');
 if me.profile_id is null then raise exception 'Staff access required to create competitions.'; end if;
 if coalesce(length(btrim(d->>'name')),0) not between 3 and 120 then raise exception 'Competition name: enter 3–120 characters.'; end if;
 if coalesce(length(btrim(d->>'game')),0) not between 2 and 80 then raise exception 'Game: enter 2–80 characters.'; end if;
 st:=(d->>'starts_at')::timestamptz;en:=(d->>'ends_at')::timestamptz;
 close_at:=coalesce(nullif(d->>'registration_closes_at','')::timestamptz,st);
 if close_at>st or close_at<=now() then raise exception 'Registration closing date / time must be in the future and no later than the competition start.'; end if;
 fee_value:=private.require_number(d,'fee','Entry fee',0,10000000);cap:=private.require_number(d,'capacity','Team slots',2,64);
 state:=coalesce(d->>'status','Open');if state not in ('Draft','Open','Cancelled') then raise exception 'Competition status: choose Draft, Open or Cancelled.'; end if;
 poster:=coalesce(d->>'poster_url','');
 if poster<>'' and (
   private.project_url()='' or
   not starts_with(poster, private.project_url() || '/storage/v1/object/public/event-posters/') or
   substr(poster,length(private.project_url() || '/storage/v1/object/public/event-posters/')+1)
      !~ '^[a-f0-9-]+/[a-f0-9-]+[.]jpg$'
 ) then raise exception 'Competition image is invalid. Use Upload image or the default poster.'; end if;
 if length(coalesce(d->>'description',''))>3000 then raise exception 'Competition description: use at most 3000 characters.'; end if;
 foreach k in array array['prize_first','prize_second','prize_third','prize_consolation'] loop
  if coalesce(length(btrim(d->>k)),16) not between 1 and 800 then raise exception '%: enter a prize or explicitly write No prize (maximum 800 characters).',replace(k,'_',' '); end if;
 end loop;
 if nullif(coalesce(d->>'event_id',d->>'id'),'') is not null then
  select * into old from events where event_id=nullif(coalesce(d->>'event_id',d->>'id'),'')::bigint for update;
  if old.event_id is null then raise exception 'Competition no longer exists. Refresh the list.'; end if;
  if old.status in ('Running','Completed') then raise exception 'Competition has already started or completed. Its booking details cannot be edited.'; end if;
  if exists(select 1 from registrations where event_id=old.event_id and status='Registered' and paid) and (state='Cancelled' or st<>old.starts_at or en<>old.ends_at or fee_value<>old.fee or cap<>old.capacity or d->>'game'<>old.game) then raise exception 'Paid entries exist. Handle refunds before changing the competition date, time, fee, game or capacity.'; end if;
  if cap<(select count(*) from registrations where event_id=old.event_id and status='Registered') then raise exception 'Team slots cannot be fewer than existing registrations.'; end if;
  update events set name=btrim(d->>'name'),game=btrim(d->>'game'),starts_at=st,ends_at=en,registration_closes_at=close_at,fee=fee_value,capacity=cap,status=state,description=coalesce(d->>'description',''),poster_url=poster,
    prize_first=coalesce(d->>'prize_first',old.prize_first),prize_second=coalesce(d->>'prize_second',old.prize_second),prize_third=coalesce(d->>'prize_third',old.prize_third),prize_consolation=coalesce(d->>'prize_consolation',old.prize_consolation) where event_id=old.event_id returning event_id into eid;
 else
  insert into events(name,game,starts_at,ends_at,registration_closes_at,fee,capacity,status,description,poster_url,prize_first,prize_second,prize_third,prize_consolation,created_by)
  values(btrim(d->>'name'),btrim(d->>'game'),st,en,close_at,fee_value,cap,state,coalesce(d->>'description',''),poster,coalesce(d->>'prize_first','To be announced'),coalesce(d->>'prize_second','To be announced'),coalesce(d->>'prize_third','To be announced'),coalesce(d->>'prize_consolation','No consolation prize'),me.profile_id) returning event_id into eid;
 end if;
 return jsonb_build_object('id',eid,'message',case when state='Draft' then 'Competition saved as draft.' else 'Competition updated.' end);
end $$;

create table public.promotions (
 promotion_id uuid primary key default gen_random_uuid(), code text unique not null check(code~'^[A-Z0-9-]{3,24}$'), title text not null check(length(title) between 3 and 120),
 scope text not null check(scope in ('All','Booking','Order','Event')), benefit text not null check(benefit in ('Percentage','Fixed','Free')),
 amount int not null check(amount>=0), resource_kind text not null default 'All', max_minutes int not null default 0 check(max_minutes between 0 and 180),
 starts_at timestamptz not null, ends_at timestamptz not null check(ends_at>starts_at), usage_limit int not null check(usage_limit between 1 and 1000000),
 per_user_limit int not null default 1 check(per_user_limit between 1 and 100), active boolean not null default true,
 target_user uuid references public."User"(user_id), created_by uuid not null references public.staff(staff_id), created_at timestamptz not null default now());
create index promotions_target_idx on public.promotions(target_user);create index promotions_creator_idx on public.promotions(created_by);
alter table public.promotions enable row level security;revoke all on public.promotions from public,anon,authenticated;
alter table public.test_payments add column promotion_id uuid references public.promotions(promotion_id);
alter table public.test_payments add column promo_code text;
create index test_payments_promotion_idx on public.test_payments(promotion_id);
create table public.promo_claims (
 payment_id uuid primary key references public.test_payments(payment_id), promotion_id uuid not null references public.promotions(promotion_id),user_id uuid not null references public."User"(user_id),
 status text not null check(status in ('Held','Redeemed','Released')),expires_at timestamptz not null,created_at timestamptz not null default now());
create index promo_claims_promo_status_idx on public.promo_claims(promotion_id,status);
create index promo_claims_user_idx on public.promo_claims(user_id,promotion_id,status);
alter table public.promo_claims enable row level security;revoke all on public.promo_claims from public,anon,authenticated;

create function private.save_promotion(d jsonb) returns jsonb
language plpgsql set search_path=public, extensions, pg_temp as $$
declare me profiles; promo promotions; result_id uuid; amt int; lim int; peruser int; maxmins int; begin_at timestamptz; finish_at timestamptz; target uuid; benefit_type text; code_value text;
begin
 select * into me from profiles where profile_id=public.ctfly_uid() and active and role in ('staff','admin');if me.profile_id is null then raise exception 'Staff access required to create promo codes.'; end if;
 code_value:=upper(btrim(d->>'code'));if coalesce(code_value,'')!~'^[A-Z0-9-]{3,24}$' then raise exception 'Promo code: use 3–24 letters, numbers or hyphens.'; end if;
 if coalesce(length(btrim(d->>'title')),0) not between 3 and 120 then raise exception 'Promo title: enter 3–120 characters.'; end if;
 if coalesce(d->>'scope','') not in ('All','Booking','Order','Event') then raise exception 'Promo applies to: choose Booking, Order, Event or All.'; end if;
 benefit_type:=d->>'benefit';if coalesce(benefit_type,'') not in ('Percentage','Fixed','Free') then raise exception 'Promo benefit: choose Percentage, Fixed or Free.'; end if;
 amt:=case when benefit_type='Free' then 0 else private.require_number(d,'amount','Discount amount',1,case when benefit_type='Percentage' then 100 else 10000000 end) end;
 lim:=private.require_number(d,'usage_limit','Total uses',1,1000000);peruser:=private.require_number(d||jsonb_build_object('per_user_limit',coalesce(d->>'per_user_limit','1')),'per_user_limit','Uses per customer',1,100);
 maxmins:=private.require_number(d||jsonb_build_object('max_minutes',coalesce(d->>'max_minutes','0')),'max_minutes','Maximum session minutes (0 = any)',0,180);
 if coalesce(d->>'resource_kind','All') not in ('All','PC','VIPRoom','Pool','Snooker','Console','Racing','Arcade','GoKart') then raise exception 'Booking type: choose a listed activity.'; end if;
 begin begin_at:=(d->>'starts_at')::timestamptz;exception when others then raise exception 'Promo start date / time is invalid.';end;
 begin finish_at:=(d->>'ends_at')::timestamptz;exception when others then raise exception 'Promo expiry date / time is invalid.';end;
 if begin_at is null or finish_at is null or finish_at<=begin_at or finish_at<=now() then raise exception 'Promo expiry date / time must be in the future and later than its start.'; end if;
 target:=nullif(d->>'target_user','')::uuid;
 if target is not null and not exists(select 1 from profiles where profile_id=target and active and role='customer') then raise exception 'Selected customer is unavailable. Refresh the customer list.'; end if;
 if nullif(coalesce(d->>'promotion_id',d->>'id'),'') is not null then
  select * into promo from promotions where promotion_id=nullif(coalesce(d->>'promotion_id',d->>'id'),'')::uuid for update;if promo.promotion_id is null then raise exception 'Promo code no longer exists.';end if;
  if exists(select 1 from promo_claims where promotion_id=promo.promotion_id and status in ('Held','Redeemed')) and (code_value<>promo.code or benefit_type<>promo.benefit or amt<>promo.amount or d->>'scope'<>promo.scope or target is distinct from promo.target_user or lim<promo.usage_limit or peruser<promo.per_user_limit or coalesce(d->>'resource_kind','All')<>promo.resource_kind or maxmins<>promo.max_minutes or begin_at<>promo.starts_at or finish_at<>promo.ends_at) then raise exception 'This code has payment claims. Keep its benefit, customer and limits, or create another code.';end if;
  update promotions set title=btrim(d->>'title'),code=code_value,scope=d->>'scope',benefit=benefit_type,amount=amt,resource_kind=coalesce(d->>'resource_kind','All'),max_minutes=maxmins,starts_at=begin_at,ends_at=finish_at,usage_limit=lim,per_user_limit=peruser,target_user=target,active=coalesce((d->>'active')::boolean,true) where promotion_id=promo.promotion_id returning promotion_id into result_id;
 else insert into promotions(code,title,scope,benefit,amount,resource_kind,max_minutes,starts_at,ends_at,usage_limit,per_user_limit,target_user,created_by)
 values(code_value,btrim(d->>'title'),d->>'scope',benefit_type,amt,coalesce(d->>'resource_kind','All'),maxmins,begin_at,finish_at,lim,peruser,target,me.profile_id) returning promotion_id into result_id;end if;
 return jsonb_build_object('id',result_id,'message','Promo code saved.');
end $$;

create function private.apply_promotion(code_value text,q jsonb,uid uuid) returns jsonb
language plpgsql set search_path=public, extensions, pg_temp as $$
declare promo promotions; deduction int; held_count int; customer_count int;
begin
 if coalesce(btrim(code_value),'')='' then return q;end if;
 select * into promo from promotions where code=upper(btrim(code_value)) for update;
 if promo.promotion_id is null then raise exception 'Promo code does not exist. Check the code.';end if;
 if not promo.active then raise exception 'Promo code has been disabled.';end if;
 if promo.starts_at>now() then raise exception 'Promo code is not active yet. Check its starting date / time.';end if;
 if promo.ends_at<=now() then raise exception 'Promo code has expired.';end if;
 if promo.target_user is not null and promo.target_user<>uid then raise exception 'Promo code is assigned to another customer.';end if;
 if promo.scope not in ('All',q->>'kind') then raise exception 'Promo code does not apply to this booking or order type.';end if;
 if q->>'kind'='Booking' and ((promo.resource_kind<>'All' and promo.resource_kind<>q->'items'->0->>'resource_kind') or (promo.max_minutes>0 and (q->'items'->0->>'duration_minutes')::int>promo.max_minutes)) then raise exception 'Promo code does not apply to this activity or session duration.';end if;
 select count(*),count(*) filter(where c.user_id=uid) into held_count,customer_count from promo_claims c join test_payments t on t.payment_id=c.payment_id where c.promotion_id=promo.promotion_id and c.status in ('Held','Redeemed') and (c.status='Redeemed' or c.expires_at>now()) and not(t.source_id=(q->>'source_id')::uuid and t.kind=q->>'kind' and t.status in ('Pending','Processing'));
 if held_count>=promo.usage_limit then raise exception 'Promo code has reached its total usage limit.';end if;
 if customer_count>=promo.per_user_limit then raise exception 'You have already used this promo code as many times as allowed.';end if;
 deduction:=least((q->>'subtotal')::int,case promo.benefit when 'Free' then (q->>'subtotal')::int when 'Fixed' then promo.amount else (q->>'subtotal')::int*promo.amount/100 end);
 if deduction<(q->>'discount')::int then raise exception 'Your member discount is better than this promo code. Remove the code to keep your member price.';end if;
 return q||jsonb_build_object('promotion_id',promo.promotion_id,'promo_code',promo.code,'discount',deduction,'total',(q->>'subtotal')::int-deduction,'points',((q->>'subtotal')::int-deduction)/100,'expires_at',least((q->>'expires_at')::timestamptz,promo.ends_at));
end $$;

create function private.sync_promo_claim() returns trigger
language plpgsql set search_path=public, extensions, pg_temp as $$
begin
 if new.promotion_id is not null and new.status is distinct from old.status then
  perform 1 from promotions where promotion_id=new.promotion_id for update;
  if new.status='Succeeded' then
   update promo_claims set status='Redeemed' where payment_id=new.payment_id and status='Held' and expires_at>now();
   if not found then raise exception 'Promo reservation expired. Restart checkout.';end if;
  elsif new.status in ('Failed','Cancelled','Expired','Refunded') then update promo_claims set status='Released' where payment_id=new.payment_id;end if;
 end if;
 return new;
end $$;
create trigger sync_promo_claim after update on public.test_payments for each row execute function private.sync_promo_claim();

-- Immutable purchase details; later resource edits or extensions cannot rewrite a receipt.
alter table public.bills add column details jsonb not null default '{}'::jsonb;
create function private.bill_details(b public.bills) returns jsonb
language plpgsql set search_path=public, extensions, pg_temp as $$
declare d jsonb; session public.bookings; ext public.booking_extensions; ending timestamptz;
begin
 d:=jsonb_build_object('customer_name',(select name from profiles where profile_id=b.user_id),'currency','MYR');
 if b.kind in ('Booking','Extension') then
  if b.kind='Extension' then select * into ext from booking_extensions where booking_extension_id=b.source_id;select * into session from bookings where booking_id=ext.booking_id;
  else select * into session from bookings where booking_id=b.source_id;end if;
  ending:=session.ends_at;
  if b.kind='Booking' then ending:=ending-make_interval(mins=>coalesce((select sum(minutes)::int from booking_extensions where booking_id=session.booking_id),0));end if;
  d:=d||coalesce((select jsonb_build_object('activity',r.kind,'resource',r.name,'zone',r.zone,'starts_at',case when b.kind='Extension' then ending-make_interval(mins=>ext.minutes) else session.starts_at end,'ends_at',ending,'duration_minutes',case when b.kind='Extension' then ext.minutes else extract(epoch from ending-session.starts_at)::int/60 end,'players',session.guests,'hourly_rate',r.rate,'booking_id',session.booking_id,'items',jsonb_build_array(jsonb_build_object('name',r.name,'qty',1,'price',b.subtotal))) from resources r where r.resource_id=session.resource_id),'{}'::jsonb);
 elsif b.kind='Order' then
  d:=d||jsonb_build_object('activity','Café order','items',coalesce((select jsonb_agg(jsonb_build_object('name',name,'qty',qty,'price',price,'line_total',qty*price) order by order_item_id) from order_items where order_id=b.source_id),'[]'::jsonb));
 elsif b.kind='Event' then
  d:=d||coalesce((select jsonb_build_object('activity','Competition','resource',e.name,'game',e.game,'team',g.team,'starts_at',e.starts_at,'ends_at',e.ends_at,'duration_minutes',extract(epoch from e.ends_at-e.starts_at)::int/60,'items',jsonb_build_array(jsonb_build_object('name',e.name,'qty',1,'price',b.subtotal))) from registrations g join events e on e.event_id=g.event_id where g.registration_id=b.source_id),'{}'::jsonb);
 end if;
 if b.payment_reference is not null then d:=d||jsonb_build_object('promo_code',(select promo_code from test_payments where reference=b.payment_reference));end if;
 return d;
end $$;
update public.bills b set details=private.bill_details(b);
create function private.freeze_receipt() returns trigger language plpgsql set search_path=public, extensions, pg_temp as $$
begin new.details:=private.bill_details(new);return new;end $$;
create trigger freeze_receipt before insert on public.bills for each row execute function private.freeze_receipt();

create table public.receipt_email_jobs (
 bill_id uuid primary key references public.bills(bill_id),user_id uuid not null references public."User"(user_id),
 status text not null default 'Queued' check(status in ('Queued','Sending','Sent','NotConfigured','Failed','Unknown')),
 attempts int not null default 0,last_attempt timestamptz,last_error text not null default '',provider_id text,sent_at timestamptz,created_at timestamptz not null default now());
create index receipt_mail_user_idx on public.receipt_email_jobs(user_id,created_at desc);
create index receipt_mail_status_idx on public.receipt_email_jobs(status,created_at);
alter table public.receipt_email_jobs enable row level security;revoke all on public.receipt_email_jobs from public,anon,authenticated;
create table private.mail_runtime(id boolean primary key default true check(id),worker_secret text not null,configured boolean not null default false);
alter table private.mail_runtime enable row level security;revoke all on private.mail_runtime from public,anon,authenticated;
insert into private.mail_runtime(worker_secret) values(replace(gen_random_uuid()::text||gen_random_uuid()::text,'-',''));
create function private.queue_receipt() returns trigger language plpgsql set search_path=public, extensions, pg_temp as $$
begin insert into receipt_email_jobs(bill_id,user_id) values(new.bill_id,new.user_id) on conflict do nothing;return new;end $$;
create trigger queue_receipt after insert on public.bills for each row execute function private.queue_receipt();
create function private.receipt_action(a text,d jsonb) returns jsonb language plpgsql set search_path=public, extensions, pg_temp as $$
declare b bills;begin
 select * into b from bills where bill_id=(d->>'id')::uuid and exists(select 1 from profiles where profile_id=public.ctfly_uid() and active and (profile_id=bills.user_id or role in ('staff','admin')));
 if b.bill_id is null then raise exception 'Receipt not found for this account.';end if;
 if a='receipt_email' then
  insert into receipt_email_jobs(bill_id,user_id) values(b.bill_id,b.user_id) on conflict(bill_id) do update set status='Queued',last_error='' where receipt_email_jobs.status in ('NotConfigured','Failed');
 end if;
 return jsonb_build_object('receipt',to_jsonb(b),'email',(select to_jsonb(j)-'user_id' from receipt_email_jobs j where bill_id=b.bill_id),'message',case when a='receipt_email' then 'Receipt queued for your registered email. Delivery needs a configured mail service.' else 'Receipt ready.' end);
end $$;
-- Only the server's service role can invoke the worker. The additional private secret authenticates cron dispatch.
create or replace function private.receipt_worker(secret_value text,operation text,d jsonb) returns jsonb
language plpgsql security definer set search_path=public, extensions, pg_temp as $$
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
  select coalesce(jsonb_agg(jsonb_build_object('bill',to_jsonb(b),'email',u.email)),'[]'::jsonb) into jobs from claimed j join bills b on b.bill_id=j.bill_id join public."User" u on u.user_id=j.user_id;
  return jobs;
 elsif operation='result' then
  state:=d->>'status';if state not in ('Sent','Failed','Unknown') then raise exception 'Invalid worker result.';end if;
  update receipt_email_jobs set status=state,last_error=left(coalesce(d->>'error',''),400),provider_id=d->>'provider_id',sent_at=case when state='Sent' then now() else null end where bill_id=(d->>'id')::uuid and status='Sending';
  return jsonb_build_object('updated',found);
 end if;
 raise exception 'Invalid worker operation.';
end $$;
create function public.ctfly_receipt_worker(p_secret text,p_operation text,p_data jsonb default '{}'::jsonb) returns jsonb
language sql security invoker set search_path=public, extensions, pg_temp as $$select private.receipt_worker(p_secret,p_operation,p_data)$$;
revoke all on function public.ctfly_receipt_worker(text,text,jsonb) from public,anon,authenticated;

-- Upgrade existing authenticated APIs without replacing their ownership or request deduplication checks.
do $$ declare def text;begin
 def:=pg_get_functiondef('public.ctfly_action(text,jsonb,uuid)'::regprocedure);
 def:=replace(def,'perform expire_bookings();','perform private.validate_club_input(p_action,p_data); perform expire_bookings();');
 def:=replace(def,'if p_action in (''test_quote''', 'if p_action=''save_event'' then result:=private.save_club_event(p_data); elsif p_action=''save_promotion'' then result:=private.save_promotion(p_data); elsif p_action in (''receipt_detail'',''receipt_email'') then result:=private.receipt_action(p_action,p_data); elsif p_action in (''test_quote''');
 def:=regexp_replace(def,'if r.resource_id is null or r.status<>''Available''.*?Choose an available space, valid time and group size[.]''; end if;', 'if n<30 then raise exception ''Duration: minimum 30 minutes.'';end if;','s');
 def:=replace(def,'(r.rate*n+30)/60','((r.rate::bigint*n+30)/60)::int');
 def:=replace(def,'select * into ev from events where event_id=(p_data->>''event_id'')::bigint for update;', 'select * into ev from events where event_id=(p_data->>''event_id'')::bigint for update; if p_action=''register_event'' and coalesce(ev.registration_closes_at,ev.starts_at)<=now() then raise exception ''Registration closing date / time has passed.'';end if;');
 def:=replace(def,'exception when exclusion_violation', $patch$exception when unique_violation then raise exception '%: this name or code is already in use. Choose another.',case p_action when 'save_promotion' then 'Promo code' when 'save_resource' then 'Space name' when 'save_category' then 'Category name' else 'Selected entry' end; when invalid_text_representation then raise exception 'Selection: an item or number is invalid. Refresh and choose a listed option.'; when datetime_field_overflow or invalid_datetime_format then raise exception 'Date / time: choose a valid date, hour and minute.'; when foreign_key_violation then raise exception 'Selection: this item is no longer available. Refresh and select it again.'; when check_violation then raise exception 'Input range: one of the supplied values is outside its allowed range. Check the field limits.'; when exclusion_violation$patch$);
 def:=replace(def,'raise exception ''That time overlaps an existing booking, event or shift. Choose another time.'';', 'raise exception ''%: this time overlaps an existing %. Choose another start time.'',case p_action when ''book'' then ''Start time'' when ''save_event'' then ''Competition time'' else ''Shift time'' end,case p_action when ''book'' then ''reservation for this space'' when ''save_event'' then ''competition'' else ''shift for this staff member'' end;');
 execute def;
 def:=pg_get_functiondef('public.ctfly_catalog()'::regprocedure);def:=replace(def,'status<>''Cancelled''','status not in (''Cancelled'',''Draft'')');execute def;
 def:=pg_get_functiondef('public.ctfly_snapshot()'::regprocedure);
 def:=replace(def,'return result;', $patch$
 result:=result||jsonb_build_object('receipt_emails',coalesce((select jsonb_agg(to_jsonb(j)-'user_id' order by created_at desc) from receipt_email_jobs j where j.user_id=me.profile_id or crew),'[]'::jsonb));
 if crew then result:=result||jsonb_build_object('events',coalesce((select jsonb_agg(e order by starts_at desc) from events e),'[]'::jsonb),'promotions',coalesce((select jsonb_agg(to_jsonb(p)||jsonb_build_object('used',(select count(*) from promo_claims c where c.promotion_id=p.promotion_id and c.status='Redeemed'))) from promotions p),'[]'::jsonb));end if;
 return result;$patch$);execute def;
 def:=pg_get_functiondef('private.test_quote(text,uuid,uuid)'::regprocedure);
 def:=replace(def,'''detail'',b.starts_at,''price'',b.total','''detail'',b.starts_at,''resource_kind'',r.kind,''zone'',r.zone,''starts_at'',b.starts_at,''ends_at'',b.ends_at,''duration_minutes'',extract(epoch from b.ends_at-b.starts_at)::int/60,''price'',b.total');execute def;
 def:=pg_get_functiondef('private.test_payment_action(text,jsonb)'::regprocedure);
 def:=replace(def,'q:=private.test_quote(chosen_kind,sid,me.profile_id);', $patch$
 if p_action='test_checkout' then select * into pay from test_payments where kind=chosen_kind and source_id=sid and user_id=me.profile_id and status in ('Pending','Processing');
 if pay.payment_id is not null then return jsonb_build_object('payment',to_jsonb(pay),'message','Existing checkout resumed.');end if;end if;
 q:=private.apply_promotion(p_data->>'promo_code',private.test_quote(chosen_kind,sid,me.profile_id),me.profile_id);$patch$);
 def:=replace(def, '(q->>''expires_at'')::timestamptz) returning * into pay;', $patch$(q->>'expires_at')::timestamptz) returning * into pay;
  if q->>'promotion_id' is not null then
   update test_payments set promotion_id=(q->>'promotion_id')::uuid,promo_code=q->>'promo_code' where payment_id=pay.payment_id returning * into pay;
   insert into promo_claims(payment_id,promotion_id,user_id,status,expires_at) values(pay.payment_id,pay.promotion_id,me.profile_id,'Held',pay.expires_at);
  end if;$patch$);
 def:=replace(def,'select * into pay from test_payments where payment_id=pay.payment_id for update;','if pay.promotion_id is not null then perform 1 from promotions where promotion_id=pay.promotion_id for update;end if; select * into pay from test_payments where payment_id=pay.payment_id for update;');execute def;
end $$;
revoke all on all functions in schema private from public,anon,authenticated;
do $$ begin if exists(select 1 from pg_roles where rolname='service_role') then
 grant usage on schema private to service_role;
 grant execute on function private.receipt_worker(text,text,jsonb),public.ctfly_receipt_worker(text,text,jsonb) to service_role;
end if;end $$;
-- Public read-only posters; uploads are validated and performed by the staff-authenticated Edge Function.
do $$ begin if to_regclass('storage.buckets') is not null then
 insert into storage.buckets(id,name,public,file_size_limit,allowed_mime_types) values('event-posters','event-posters',true,4194304,array['image/jpeg']) on conflict(id) do nothing;
end if;end $$;
-- Local database tests omit unavailable extensions; the hosted project has pg_net and pg_cron.
do $$ begin if exists(select 1 from pg_available_extensions where name='pg_net') then create extension if not exists pg_net with schema extensions;end if;end $$;
create function private.kick_receipt_worker() returns void language plpgsql security definer set search_path=public, extensions, pg_temp as $$
begin
 if private.project_url()<>'' and to_regprocedure('net.http_post(text,jsonb,jsonb,jsonb,integer)') is not null then
  perform net.http_post(url:=private.project_url()||'/functions/v1/club-services',headers:=jsonb_build_object('Content-Type','application/json','X-Receipt-Worker',(select worker_secret from private.mail_runtime)),body:='{"operation":"dispatch"}'::jsonb,timeout_milliseconds:=60000);
 end if;
end $$;
revoke all on function private.kick_receipt_worker() from public,anon,authenticated;
do $$ begin if to_regclass('cron.job') is not null then perform cron.schedule('ctfly-receipt-mail','* * * * *','select private.kick_receipt_worker()');end if;end $$;

-- Section: receipt-worker-configuration
do $$
declare
 def text;
 old_update constant text := 'update private.mail_runtime set configured=coalesce((d->>''configured'')::boolean,false);';
 new_update constant text := 'update private.mail_runtime set configured=coalesce((d->>''configured'')::boolean,false) where id=true;';
begin
 def:=pg_get_functiondef('private.receipt_worker(text,text,jsonb)'::regprocedure);
 if position(new_update in def)>0 then
  return;
 end if;
 if position(old_update in def)=0 then
  raise exception 'Unexpected receipt worker definition';
 end if;
 execute replace(def,old_update,new_update);
end $$;

-- Section: receipt-dispatch
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

-- Section: cafe-pickup
alter table public.orders add column if not exists pickup_token uuid;
alter table public.orders add column if not exists ready_at timestamptz;
alter table public.orders add column if not exists received_at timestamptz;
alter table public.orders add column if not exists received_by uuid references public.staff(staff_id);
alter table public.orders drop constraint if exists orders_status_check;
update public.orders set status='Received' where status='Delivered';
alter table public.orders add constraint orders_status_check check(status in ('Pending','Preparing','Ready','Received','Cancelled'));
create unique index if not exists orders_pickup_token_idx on public.orders(pickup_token) where pickup_token is not null;
create index if not exists orders_received_by_idx on public.orders(received_by) where received_by is not null;
update public.orders set pickup_token=gen_random_uuid(),ready_at=coalesce(ready_at,now()) where status='Ready' and paid and pickup_token is null;

create table if not exists public.order_notifications (
 notice_id uuid primary key default gen_random_uuid(), user_id uuid not null references public."User"(user_id),
 order_id uuid not null unique references public.orders(order_id), created_at timestamptz not null default now(),read_at timestamptz
);
create index if not exists order_notifications_user_idx on public.order_notifications(user_id,created_at desc);
alter table public.order_notifications enable row level security;
revoke all on public.order_notifications from public,anon,authenticated;

create table if not exists private.user_push_devices (
 installation_id uuid primary key, user_id uuid not null references public."User"(user_id),
 token text not null unique check(length(token) between 20 and 4096), language text not null check(language in ('en','zh','ms')),
 updated_at timestamptz not null default now()
);
create index if not exists user_push_devices_user_idx on private.user_push_devices(user_id);
alter table private.user_push_devices enable row level security;
revoke all on private.user_push_devices from public,anon,authenticated;

create table if not exists private.order_push_jobs (
 order_push_job_id uuid primary key default gen_random_uuid(), notice_id uuid not null references public.order_notifications(notice_id),
 installation_id uuid not null references private.user_push_devices on delete cascade,
 user_id uuid not null references public."User"(user_id), status text not null default 'Queued' check(status in ('Queued','Sending','Sent','Failed','Skipped')),
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
 select * into me from public.profiles where profile_id=public.ctfly_uid() and active;
 if me.profile_id is null then raise exception 'Please sign in with an active CTFLY account.';end if;
 if a in ('pickup_preview','order_pickup') then
  if me.role not in ('staff','admin') then raise exception 'Staff access required.';end if;
  if coalesce(d->>'token','') !~ '^ctfly-pickup:[0-9a-fA-F-]{36}$' then raise exception 'This is not a café pickup QR.';end if;
  begin token_value:=substring(d->>'token' from 14)::uuid;exception when invalid_text_representation then raise exception 'This is not a café pickup QR.';end;
  select * into o from public.orders where pickup_token=token_value for update;
  if o.order_id is null then raise exception 'Pickup QR not found. Ask the customer to open their ready order.';end if;
  if o.status='Received' then raise exception 'This order has already been collected.';end if;
  if not o.paid or o.status<>'Ready' then raise exception 'Only paid orders marked Ready can be collected.';end if;
  if a='order_pickup' then
   update public.orders set status='Received',received_at=now(),received_by=me.profile_id where order_id=o.order_id returning * into o;
   update public.order_notifications set read_at=coalesce(read_at,now()) where order_id=o.order_id;
   update private.order_push_jobs set status='Skipped' where notice_id in (select notice_id from public.order_notifications where order_id=o.order_id) and status in ('Queued','Failed');
  end if;
  return jsonb_build_object('order',to_jsonb(o),'customer_name',(select name from public.profiles where profile_id=o.user_id),'items',(select coalesce(jsonb_agg(jsonb_build_object('name',name,'qty',qty)),'[]'::jsonb) from public.order_items where order_id=o.order_id),'message',case when a='order_pickup' then 'Order collected. Enjoy your food!' else 'Pickup verified. Check the items before handover.' end);
 elsif a='register_push_device' then
  if me.role<>'customer' then raise exception 'Customer account required.';end if;
  installation:=(d->>'installation_id')::uuid;
  if installation is null or length(coalesce(d->>'token','')) not between 20 and 4096 or coalesce(d->>'language','') not in ('en','zh','ms') then raise exception 'Invalid notification device.';end if;
  delete from private.user_push_devices where token=d->>'token' and installation_id<>installation;
  insert into private.user_push_devices(installation_id,user_id,token,language) values(installation,me.profile_id,d->>'token',d->>'language')
  on conflict(installation_id) do update set user_id=excluded.user_id,token=excluded.token,language=excluded.language,updated_at=now();
  -- Bound inactive device registrations and avoid accumulating shared-device owners.
  delete from private.user_push_devices where user_id=me.profile_id and installation_id in (select installation_id from private.user_push_devices where user_id=me.profile_id order by updated_at desc offset 5);
  return jsonb_build_object('message','Pickup notifications enabled.');
 elsif a='unregister_push_device' then
  delete from private.user_push_devices where installation_id=(d->>'installation_id')::uuid and user_id=me.profile_id;
  return jsonb_build_object('message','Notification device removed.');
 elsif a='read_order_notice' then
  update public.order_notifications set read_at=coalesce(read_at,now()) where order_id=(d->>'order_id')::uuid and user_id=me.profile_id;
  return jsonb_build_object('message','Notification read.');
 end if;
 raise exception 'Unsupported café action.';
end $$;
revoke all on function private.cafe_pickup_action(text,jsonb) from public,anon,authenticated;

create or replace function private.kick_order_push() returns void
language plpgsql security definer set search_path = '' as $$
begin
 if private.project_url()<>'' and to_regprocedure('net.http_post(text,jsonb,jsonb,jsonb,integer)') is not null then
  perform net.http_post(url:=private.project_url()||'/functions/v1/order-notifications',headers:=jsonb_build_object('Content-Type','application/json','X-Order-Worker',(select worker_secret from private.mail_runtime where id=true)),body:='{}'::jsonb,timeout_milliseconds:=60000);
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
 insert into public.order_notifications(user_id,order_id) values(new.user_id,new.order_id) on conflict(order_id) do nothing returning notice_id into notice;
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
  def:=replace(def,'return result;','result:=result||jsonb_build_object(''order_notifications'',coalesce((select jsonb_agg(to_jsonb(n) order by n.created_at desc) from public.order_notifications n where n.user_id=public.ctfly_uid() and n.created_at>now()-interval ''30 days''),''[]''::jsonb)); return result;');
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
  update private.order_push_jobs j set status='Skipped' where status in ('Queued','Failed') and (attempts>=3 or not exists(select 1 from private.user_push_devices v join public.order_notifications n on n.notice_id=j.notice_id join public.orders o on o.order_id=n.order_id join public.profiles p on p.profile_id=j.user_id where v.installation_id=j.installation_id and v.user_id=j.user_id and p.active and o.status='Ready' and o.paid and n.created_at>now()-interval '1 day'));
  with chosen as(select order_push_job_id from private.order_push_jobs where status in ('Queued','Failed') and attempts<3 and next_attempt<=now() order by next_attempt for update skip locked limit 10), claimed as(update private.order_push_jobs j set status='Sending',attempts=attempts+1,last_attempt=now() from chosen where j.order_push_job_id=chosen.order_push_job_id returning j.*)
  select coalesce(jsonb_agg(jsonb_build_object('id',j.order_push_job_id,'notice_id',n.notice_id,'order_id',n.order_id,'user_id',j.user_id,'token',v.token,'language',v.language)),'[]'::jsonb) into result from claimed j join private.user_push_devices v on v.installation_id=j.installation_id and v.user_id=j.user_id join public.order_notifications n on n.notice_id=j.notice_id;
  return result;
 elsif operation='result' then
  if d->>'status' not in ('Sent','Failed','Skipped') then raise exception 'Invalid push result.';end if;
  update private.order_push_jobs set status=d->>'status',next_attempt=now()+interval '1 minute' where order_push_job_id=(d->>'id')::uuid and status='Sending';
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

-- Section: staff-history
create index if not exists bookings_history_date_idx on public.bookings(starts_at desc,booking_id);
create index if not exists orders_history_date_idx on public.orders(created_at desc,order_id);
create index if not exists order_items_history_order_idx on public.order_items(order_id);
create index if not exists test_payments_history_date_idx on public.test_payments(created_at desc,payment_id);
create index if not exists redemptions_history_date_idx on public.redemptions(created_at desc,redeem_id);
create index if not exists bills_history_date_idx on public.bills(created_at desc,bill_id);
create index if not exists feedback_history_date_idx on public.app_feedback(created_at desc,feedback_id);

-- Read-only staff history. Explicit role checks are required because table RLS is
-- bypassed by this function. No profile contacts, entry tokens or device tokens leave it.
create or replace function public.ctfly_staff_history(
 p_module text,
 p_day date default null,
 p_category text default 'All',
 p_search text default '',
 p_before timestamptz default null,
 p_before_key text default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare result jsonb; day_start timestamptz; day_end timestamptz;
begin
 if not exists(select 1 from public.profiles p where p.profile_id=public.ctfly_uid() and p.active and p.role in ('staff','admin')
  and exists(select 1 from public.staff s where s.staff_id=p.profile_id)) then
  raise exception 'Staff access required.';
 end if;
 if p_module not in ('Activity','Transactions','Feedback','Checklist','Report') or p_module is null then
  raise exception 'Choose a supported history module.';
 end if;
 if p_category is null or (p_module='Activity' and p_category not in ('All','Bookings','Orders','Competitions','Rewards'))
 or (p_module='Feedback' and p_category not in ('All','New','Reviewed','Resolved'))
 or (p_module='Transactions' and p_category not in ('All','Succeeded','Paid','Pending','Processing','Failed','Cancelled','Expired','Refunded','Void')) then
  raise exception 'Choose a supported history category.';
 end if;
 if p_search is null or char_length(p_search)>100 then raise exception 'Search must be 100 characters or fewer.';end if;
 if (p_before is null)<>(p_before_key is null) then raise exception 'Invalid history cursor.';end if;
 day_start:=p_day::timestamp at time zone 'Asia/Kuala_Lumpur';
 day_end:=(p_day+1)::timestamp at time zone 'Asia/Kuala_Lumpur';

 if p_module='Checklist' then
  return jsonb_build_object('items',coalesce((select jsonb_agg(jsonb_build_object('item',c.item,'history_key',c.item))
   from public.checklist c where c.staff_id=public.ctfly_uid() and c.day=coalesce(p_day,(now() at time zone 'Asia/Kuala_Lumpur')::date)),'[]'::jsonb),'has_more',false);
 elsif p_module='Report' then
  if not exists(select 1 from public.profiles where profile_id=public.ctfly_uid() and role='admin') then raise exception 'Manager access required.';end if;
  return jsonb_build_object('items',jsonb_build_array(jsonb_build_object('history_key','summary',
   'completed_sessions',(select count(*) from public.bookings b where b.status='Completed' and (p_day is null or b.starts_at>=day_start and b.starts_at<day_end)),
   'worked_minutes',(select coalesce(sum(a.minutes),0) from public.attendance a where p_day is null or a.clock_in>=day_start and a.clock_in<day_end),
   'late_minutes',(select coalesce(sum(a.late_minutes),0) from public.attendance a where p_day is null or a.clock_in>=day_start and a.clock_in<day_end)
  )),'has_more',false);
 end if;

 with records as (
  select b.starts_at as sort_at,'Booking:'||b.booking_id as key,'Bookings' as category,p.name as customer,
   r.name as title,b.booking_id::text as reference,
   jsonb_build_object('id',b.booking_id,'category','Bookings','customer_name',p.name,'title',r.name,
    'status',b.status,'paid',b.paid,'total',b.total,'starts_at',b.starts_at,'ends_at',b.ends_at,
    'created_at',b.created_at,'guests',b.guests,'minutes',extract(epoch from (b.ends_at-b.starts_at))::int/60,
    'detail',r.kind||' · '||r.zone) as payload
  from public.bookings b join public.profiles p on p.profile_id=b.user_id join public.resources r on r.resource_id=b.resource_id
  where p_module='Activity' and p_category in ('All','Bookings')
   and (p_day is null or b.starts_at>=day_start and b.starts_at<day_end)
  union all
  select o.created_at,'Order:'||o.order_id,'Orders',p.name,'Café order',o.order_id::text,
   jsonb_build_object('id',o.order_id,'category','Orders','customer_name',p.name,'title','Café order',
    'status',o.status,'paid',o.paid,'total',o.total,'created_at',o.created_at,'received_at',o.received_at,
    'detail',coalesce((select string_agg(i.qty||' × '||i.name,', ' order by i.order_item_id) from public.order_items i where i.order_id=o.order_id),''))
  from public.orders o join public.profiles p on p.profile_id=o.user_id
  where p_module='Activity' and p_category in ('All','Orders')
   and (p_day is null or o.created_at>=day_start and o.created_at<day_end)
  union all
  select e.starts_at,'Event:'||r.registration_id,'Competitions',p.name,e.name,r.registration_id::text,
   jsonb_build_object('id',r.registration_id,'category','Competitions','customer_name',p.name,'title',e.name,
    'status',case when r.status='Withdrawn' then r.status when e.status in ('Completed','Cancelled') then e.status else r.status end,
    'paid',r.paid,'total',r.fee,'starts_at',e.starts_at,'ends_at',e.ends_at,'detail',r.team||' · '||e.game)
  from public.registrations r join public.events e on e.event_id=r.event_id join public.profiles p on p.profile_id=r.user_id
  where p_module='Activity' and p_category in ('All','Competitions')
   and (p_day is null or e.starts_at>=day_start and e.starts_at<day_end)
  union all
  select r.created_at,'Reward:'||r.redeem_id,'Rewards',p.name,r.name,r.redeem_id::text,
   jsonb_build_object('id',r.redeem_id,'category','Rewards','customer_name',p.name,'title',r.name,
    'status',r.status,'points',r.points,'created_at',r.created_at,'detail','Points redemption')
  from public.redemptions r join public.profiles p on p.profile_id=r.user_id
  where p_module='Activity' and p_category in ('All','Rewards')
   and (p_day is null or r.created_at>=day_start and r.created_at<day_end)
  union all
  select t.created_at,'Test:'||t.payment_id,t.status,p.name,t.kind,t.reference,
   jsonb_build_object('id',t.payment_id,'category','Transactions','customer_name',p.name,'title',t.kind,
    'status',t.status,'total',t.total,'created_at',t.created_at,'method',t.method,'reference',t.reference,
    'environment','test','payment',to_jsonb(t),'receipt',(select to_jsonb(b) from public.bills b where b.bill_id=t.bill_id))
  from public.test_payments t join public.profiles p on p.profile_id=t.user_id
  where p_module='Transactions' and (p_category='All' or t.status=p_category)
   and (p_day is null or t.created_at>=day_start and t.created_at<day_end)
  union all
  select b.created_at,'Bill:'||b.bill_id,b.status,p.name,b.kind,coalesce(b.payment_reference,b.bill_id::text),
   jsonb_build_object('id',b.bill_id,'category','Transactions','customer_name',p.name,'title',b.kind,
    'status',b.status,'total',b.total,'created_at',b.created_at,'method',b.method,
    'reference',coalesce(b.payment_reference,b.bill_id::text),'environment','counter','receipt',to_jsonb(b))
  from public.bills b join public.profiles p on p.profile_id=b.user_id
  where p_module='Transactions' and b.environment<>'test' and (p_category='All' or b.status=p_category)
   and (p_day is null or b.created_at>=day_start and b.created_at<day_end)
  union all
  select f.created_at,'Feedback:'||f.feedback_id,f.status,p.name,f.title,f.feedback_id::text,
   to_jsonb(f)||jsonb_build_object('category','Feedback','feedback_category',f.category,'customer_name',p.name)
  from public.app_feedback f join public.profiles p on p.profile_id=f.user_id
  where p_module='Feedback' and (p_category='All' or f.status=p_category)
   and (p_day is null or f.created_at>=day_start and f.created_at<day_end)
 ), page as (
  select sort_at,key,payload from records
  where (btrim(p_search)='' or strpos(lower(customer||' '||title||' '||reference||' '||coalesce(payload->>'detail','')),lower(btrim(p_search)))>0)
   and (p_before is null or (sort_at,key)<(p_before,p_before_key))
  order by sort_at desc,key desc limit 41
 ), visible as (
  select * from page order by sort_at desc,key desc limit 40
 )
 select jsonb_build_object('items',coalesce((select jsonb_agg(payload||jsonb_build_object('history_key',key,'sort_at',sort_at) order by sort_at desc,key desc) from visible),'[]'::jsonb),
  'has_more',(select count(*)>40 from page),
  'next_before',(select sort_at from visible order by sort_at,key limit 1),
  'next_key',(select key from visible order by sort_at,key limit 1)) into result;
 return result;
end $$;
revoke all on function public.ctfly_staff_history(text,date,text,text,timestamptz,text) from public,anon,authenticated;
grant execute on function public.ctfly_staff_history(text,date,text,text,timestamptz,text) to anon, authenticated;

-- Section: transaction-filters
-- A dedicated endpoint keeps older app versions compatible with staff history.
-- Type, status, venue day and search are applied before cursor pagination.
create or replace function public.ctfly_staff_transactions(
    p_day date default null,
    p_status text default 'All',
    p_kind text default 'All',
    p_search text default '',
    p_before timestamptz default null,
    p_before_key text default null
) returns jsonb
language plpgsql security definer set search_path = '' as $$
declare
    result jsonb;
    day_start timestamptz;
    day_end timestamptz;
begin
    if not exists (
        select 1 from public.profiles p
        where p.profile_id = public.ctfly_uid() and p.active and p.role in ('staff', 'admin')
          and exists(select 1 from public.staff s where s.staff_id=p.profile_id)
    ) then
        raise exception 'Staff access required.';
    end if;
    if p_kind is null or p_kind not in ('All', 'Booking', 'Order', 'Event', 'Extension') then
        raise exception 'Choose a supported transaction type.';
    end if;
    if p_status is null or p_status not in (
        'All', 'Succeeded', 'Paid', 'Pending', 'Processing',
        'Failed', 'Cancelled', 'Expired', 'Refunded', 'Void'
    ) then
        raise exception 'Choose a supported payment status.';
    end if;
    if p_search is null or char_length(p_search) > 100 then
        raise exception 'Search must be 100 characters or fewer.';
    end if;
    if (p_before is null) <> (p_before_key is null) then
        raise exception 'Invalid history cursor.';
    end if;
    day_start := p_day::timestamp at time zone 'Asia/Kuala_Lumpur';
    day_end := (p_day + 1)::timestamp at time zone 'Asia/Kuala_Lumpur';

    with payments as (
        select
            t.created_at as sort_at,
            'Test:' || t.payment_id as key,
            t.user_id, t.kind, t.status, t.total, t.method, t.reference,
            'test'::text as environment,
            to_jsonb(t) as payment,
            (select to_jsonb(b) from public.bills b where b.bill_id = t.bill_id) as receipt
        from public.test_payments t
        where (p_kind = 'All' or t.kind = p_kind)
            and (p_status = 'All' or t.status = p_status)
            and (p_day is null or t.created_at >= day_start and t.created_at < day_end)

        union all

        select
            b.created_at, 'Bill:' || b.bill_id,
            b.user_id, b.kind, b.status, b.total, b.method,
            coalesce(b.payment_reference, b.bill_id::text),
            'counter'::text, null::jsonb, to_jsonb(b)
        from public.bills b
        where b.environment <> 'test'
            and (p_kind = 'All' or b.kind = p_kind)
            and (p_status = 'All' or b.status = p_status)
            and (p_day is null or b.created_at >= day_start and b.created_at < day_end)
    ), page as (
        select t.*, p.name as customer_name
        from payments t
        join public.profiles p on p.profile_id = t.user_id
        where (
            btrim(p_search) = '' or
            strpos(lower(p.name || ' ' || t.kind || ' ' || t.reference), lower(btrim(p_search))) > 0
        ) and (p_before is null or (t.sort_at, t.key) < (p_before, p_before_key))
        order by t.sort_at desc, t.key desc
        limit 41
    ), visible as (
        select * from page order by sort_at desc, key desc limit 40
    )
    select jsonb_build_object(
        'items', coalesce((
            select jsonb_agg(jsonb_build_object(
                'id', split_part(key, ':', 2),
                'history_key', key, 'sort_at', sort_at,
                'category', 'Transactions', 'title', kind, 'kind', kind,
                'customer_name', customer_name, 'status', status,
                'total', total, 'created_at', sort_at, 'method', method,
                'reference', reference, 'environment', environment,
                'payment', payment, 'receipt', receipt
            ) order by sort_at desc, key desc)
            from visible
        ), '[]'::jsonb),
        'has_more', (select count(*) > 40 from page),
        'next_before', (select sort_at from visible order by sort_at, key limit 1),
        'next_key', (select key from visible order by sort_at, key limit 1)
    ) into result;
    return result;
end $$;

revoke all on function public.ctfly_staff_transactions(date,text,text,text,timestamptz,text)
    from public, anon, authenticated;
grant execute on function public.ctfly_staff_transactions(date,text,text,text,timestamptz,text)
    to anon, authenticated;

-- Section: staff-fingerprint-devices
-- Existing device registrations and pending challenges are retained on reruns.
-- Device public keys only. Fingerprints and private keys never leave the phone.
create schema if not exists ctfly_private;
revoke all on schema ctfly_private from public, anon, authenticated;
create table if not exists ctfly_private.staff_devices (
 staff_device_id uuid primary key,
 staff_id uuid not null references public.staff(staff_id) on delete cascade,
 staff_name text not null check (char_length(staff_name) between 2 and 60),
 device_name text not null check (char_length(device_name) between 1 and 100),
 public_key text not null check (char_length(public_key) between 80 and 300),
 created_at timestamptz not null default now(),
 last_used_at timestamptz
);
create index if not exists staff_devices_staff_idx on ctfly_private.staff_devices(staff_id);
alter table ctfly_private.staff_devices enable row level security;
create table if not exists ctfly_private.staff_device_challenges (
 staff_device_challenge_id uuid primary key default gen_random_uuid(),
 device_id uuid not null references ctfly_private.staff_devices(staff_device_id) on delete cascade,
 nonce text not null,
 expires_at timestamptz not null default now() + interval '2 minutes'
);
create index if not exists staff_device_challenges_device_idx on ctfly_private.staff_device_challenges(device_id);
alter table ctfly_private.staff_device_challenges enable row level security;

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
  if exists(select 1 from ctfly_private.staff_devices where staff_device_id=device and staff_id<>p_owner) then
   raise exception 'This device is already registered to another account.';
  end if;
  if not exists(select 1 from ctfly_private.staff_devices where staff_device_id=device and staff_id=p_owner)
   and (select count(*) from ctfly_private.staff_devices where staff_id=p_owner)>=5 then
   raise exception 'Five devices are already registered. Remove a device before adding another.';
  end if;
  insert into ctfly_private.staff_devices(staff_device_id,staff_id,staff_name,device_name,public_key)
   values(device,p_owner,trim(p_data->>'staff_name'),p_data->>'device_name',p_data->>'public_key')
   on conflict(staff_device_id) do update set staff_name=excluded.staff_name, device_name=excluded.device_name,
    public_key=excluded.public_key where staff_devices.staff_id=p_owner;
  if not found then raise exception 'This device is already registered to another account.'; end if;
  delete from ctfly_private.staff_device_challenges where device_id=device;
 elsif p_operation='remove' then
  delete from ctfly_private.staff_devices where staff_device_id=(p_data->>'device_id')::uuid and staff_id=p_owner;
 elsif p_operation<>'list' or p_operation is null then
  raise exception 'Unsupported device operation.';
 end if;
 select coalesce(jsonb_agg(jsonb_build_object('id',staff_device_id,'staff_name',staff_name,'device_name',device_name,
  'created_at',created_at,'last_used_at',last_used_at) order by created_at,staff_device_id),'[]'::jsonb)
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
 return ctfly_private.manage_staff_devices(public.ctfly_uid(),p_operation,p_data);
end
$$;
revoke all on function public.ctfly_staff_devices(text,jsonb) from public,anon;
grant execute on function public.ctfly_staff_devices(text,jsonb) to anon, authenticated;

create or replace function public.ctfly_register_staff_device(p_owner uuid,p_data jsonb)
returns jsonb language sql security definer set search_path = '' as $$
 select ctfly_private.manage_staff_devices(p_owner,'register',p_data)
$$;
revoke all on function public.ctfly_register_staff_device(uuid,jsonb) from public,anon,authenticated;
grant execute on function public.ctfly_register_staff_device(uuid,jsonb) to service_role;

drop function if exists ctfly_private.manage_staff_devices(text,jsonb);
revoke usage on schema ctfly_private from authenticated;

-- Only the signature-verifying Edge Function may issue/consume login challenges.
create or replace function public.ctfly_staff_device_auth(p_operation text,p_device uuid,p_challenge uuid default null)
returns jsonb language plpgsql security definer set search_path = '' as $$
declare d ctfly_private.staff_devices; c ctfly_private.staff_device_challenges; email text;
begin
 select * into d from ctfly_private.staff_devices where staff_device_id=p_device for update;
 if d.staff_device_id is null or not exists(select 1 from public.profiles p where p.profile_id=d.staff_id and p.active and p.role in ('staff','admin')
  and exists(select 1 from public.staff s where s.staff_id=p.profile_id)) then
  raise exception 'Device registration is unavailable. Sign in with email and password to add this phone again.';
 end if;
 if p_operation='challenge' then
  delete from ctfly_private.staff_device_challenges where device_id=p_device and expires_at<now();
  if (select count(*) from ctfly_private.staff_device_challenges where device_id=p_device)>=10 then
   raise exception 'Too many sign-in attempts. Please wait two minutes.';
  end if;
  insert into ctfly_private.staff_device_challenges(device_id,nonce)
   values(p_device,gen_random_uuid()::text||gen_random_uuid()::text) returning * into c;
  return jsonb_build_object('challenge_id',c.staff_device_challenge_id,'nonce',c.nonce);
 elsif p_operation='consume' then
  delete from ctfly_private.staff_device_challenges where staff_device_challenge_id=p_challenge and device_id=p_device and expires_at>now() returning * into c;
  if c.staff_device_challenge_id is null then raise exception 'Fingerprint request expired. Please try again.';end if;
  select s.email into email from public.staff s where s.staff_id=d.staff_id;
  return jsonb_build_object('owner',d.staff_id,'email',email,'public_key',d.public_key,'nonce',c.nonce);
 elsif p_operation='check' then
  update ctfly_private.staff_devices set last_used_at=now() where staff_device_id=p_device;
  return jsonb_build_object('owner',d.staff_id);
 end if;
 raise exception 'Unsupported device operation.';
end $$;
revoke all on function public.ctfly_staff_device_auth(text,uuid,uuid) from public,anon,authenticated;
grant execute on function public.ctfly_staff_device_auth(text,uuid,uuid) to service_role;

-- End of database setup
commit;
