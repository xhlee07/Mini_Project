begin;

-- Virtual 3D coordinates only; no device location, camera footage or movement history.
create schema if not exists ctfly_tour;
revoke all on schema ctfly_tour from public,anon,authenticated;
create table ctfly_tour.visitors (
 session_id uuid primary key,
 user_id uuid not null references public.profiles(id) on delete cascade,
 sequence bigint not null check (sequence>0),
 area text not null check (area in ('GAMING','BILLIARDS','LOUNGE','OUTDOOR')),
 x real not null, z real not null, yaw real not null,
 mode text not null check (mode in ('walk','kart')),
 moving boolean not null default false,
 active boolean not null default true,
 updated_at timestamptz not null default clock_timestamp()
);
create index tour_visitors_user_idx on ctfly_tour.visitors(user_id);
create index tour_visitors_recent_idx on ctfly_tour.visitors(updated_at desc);
alter table ctfly_tour.visitors enable row level security;
revoke all on ctfly_tour.visitors from public,anon,authenticated;

create function ctfly_tour.publish(p_session uuid,p_sequence bigint,p_area text,p_x real,p_z real,p_yaw real,
 p_mode text,p_moving boolean,p_leaving boolean) returns jsonb
language plpgsql security definer set search_path='' as $$
declare owner uuid:=auth.uid(); width real; depth real; stamp timestamptz:=clock_timestamp();
begin
 if owner is null or not exists(select 1 from public.profiles where id=owner and active and role='customer') then
  raise exception 'Customer access required.';
 end if;
 if p_session is null or p_sequence is null or p_sequence<1 or p_sequence>9007199254740991 then
  raise exception 'Invalid virtual visit.';
 end if;
 width:=case p_area when 'GAMING' then 14 when 'BILLIARDS' then 14 when 'LOUNGE' then 18 when 'OUTDOOR' then 85 end;
 depth:=case p_area when 'GAMING' then 34 when 'BILLIARDS' then 26 when 'LOUNGE' then 17 when 'OUTDOOR' then 115 end;
 if width is null or p_x is null or p_z is null or p_yaw is null or not(abs(p_x)<width and abs(p_z)<depth and abs(p_yaw)<=3.141593)
  or p_mode is null or p_mode not in ('walk','kart') or (p_mode='kart' and p_area<>'OUTDOOR')
  or p_moving is null or p_leaving is null then raise exception 'Invalid virtual position.';end if;
 -- Serialize a customer's sessions and never allow another account to overwrite one.
 perform 1 from public.profiles where id=owner for update;
 if exists(select 1 from ctfly_tour.visitors where session_id=p_session and user_id<>owner) then
  raise exception 'This virtual visit belongs to another account.';
 end if;
 delete from ctfly_tour.visitors where updated_at<stamp-interval '1 day';
 if not exists(select 1 from ctfly_tour.visitors where session_id=p_session) and not p_leaving
  and (select count(*) from ctfly_tour.visitors where user_id=owner and active and updated_at>stamp-interval '12 seconds')>=3 then
  raise exception 'This account already has three active virtual visits.';
 end if;
 -- A late in-flight position cannot resurrect a visit after its higher-sequence exit.
 insert into ctfly_tour.visitors(session_id,user_id,sequence,area,x,z,yaw,mode,moving,active,updated_at)
 values(p_session,owner,p_sequence,p_area,p_x,p_z,p_yaw,p_mode,p_moving,not p_leaving,stamp)
 on conflict(session_id) do update set sequence=excluded.sequence,area=excluded.area,x=excluded.x,z=excluded.z,
 yaw=excluded.yaw,mode=excluded.mode,moving=excluded.moving,active=excluded.active,updated_at=excluded.updated_at
 where visitors.user_id=owner and visitors.sequence<excluded.sequence;
 return jsonb_build_object('ok',true);
end $$;

create function ctfly_tour.watch() returns jsonb
language plpgsql security definer set search_path='' as $$
declare stamp timestamptz:=clock_timestamp(); result jsonb;
begin
 if auth.uid() is null or not exists(select 1 from public.profiles where id=auth.uid() and active and role in ('staff','admin')) then
  raise exception 'Staff access required.';
 end if;
 select coalesce(jsonb_agg(jsonb_build_object('id',v.session_id,'area',v.area,'x',v.x,'z',v.z,'yaw',v.yaw,
  'mode',v.mode,'moving',v.moving,'age_ms',greatest(0,floor(extract(epoch from(stamp-v.updated_at))*1000))) order by v.session_id),'[]'::jsonb)
 into result from (select s.* from ctfly_tour.visitors s join public.profiles p on p.id=s.user_id
  where s.active and s.updated_at>stamp-interval '12 seconds' and p.active and p.role='customer'
  order by s.updated_at desc limit 100) v;
 return jsonb_build_object('visitors',result,'ttl_ms',12000,'server_time',stamp);
end $$;

revoke all on function ctfly_tour.publish(uuid,bigint,text,real,real,real,text,boolean,boolean) from public,anon;
revoke all on function ctfly_tour.watch() from public,anon;
grant usage on schema ctfly_tour to authenticated;
grant execute on function ctfly_tour.publish(uuid,bigint,text,real,real,real,text,boolean,boolean), ctfly_tour.watch() to authenticated;

create function public.ctfly_tour_presence(p_session uuid,p_sequence bigint,p_area text,p_x real,p_z real,p_yaw real,
 p_mode text default 'walk',p_moving boolean default false,p_leaving boolean default false) returns jsonb
language sql security invoker set search_path='' as $$
 select ctfly_tour.publish(p_session,p_sequence,p_area,p_x,p_z,p_yaw,p_mode,p_moving,p_leaving)
$$;
create function public.ctfly_tour_watch() returns jsonb
language sql security invoker set search_path='' as $$select ctfly_tour.watch()$$;
revoke all on function public.ctfly_tour_presence(uuid,bigint,text,real,real,real,text,boolean,boolean),public.ctfly_tour_watch() from public,anon;
grant execute on function public.ctfly_tour_presence(uuid,bigint,text,real,real,real,text,boolean,boolean),public.ctfly_tour_watch() to authenticated;
commit;
