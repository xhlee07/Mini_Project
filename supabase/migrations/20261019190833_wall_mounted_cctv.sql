begin;

-- Only mechanical directions are shared with customers, never visitor identities or positions.
create table ctfly_tour.cameras (
 camera_id integer primary key check (camera_id between 0 and 4),
 wall_yaw real not null,
 yaw real not null,
 pitch real not null,
 expires_at timestamptz not null default '-infinity'
);
alter table ctfly_tour.cameras enable row level security;
revoke all on ctfly_tour.cameras from public, anon, authenticated;
insert into ctfly_tour.cameras(camera_id,wall_yaw,yaw,pitch) values
 (0,1.5707963,.95,-.20),(1,-1.5707963,-.9,-.24),(2,1.5707963,.65,-.20),
 (3,0,0,-.20),(4,1.5707963,.50,-.10);

create function ctfly_tour.camera_directions() returns jsonb
language plpgsql security definer set search_path='' as $$
declare stamp timestamptz := clock_timestamp(); result jsonb;
begin
 if auth.uid() is null or not exists(select 1 from public.profiles where id=auth.uid() and active) then
  raise exception 'Account access required.';
 end if;
 select coalesce(jsonb_agg(jsonb_build_object('camera_id',camera_id,'yaw',yaw,'pitch',pitch,
  'remaining_ms',greatest(0,floor(extract(epoch from(expires_at-stamp))*1000))) order by camera_id),'[]'::jsonb)
 into result from ctfly_tour.cameras where expires_at > stamp;
 return jsonb_build_object('cameras',result,'server_ms',floor(extract(epoch from stamp)*1000));
end $$;

create function ctfly_tour.turn_camera(p_camera integer,p_yaw real,p_pitch real,p_hold_ms integer) returns jsonb
language plpgsql security definer set search_path='' as $$
declare wall real;
begin
 if auth.uid() is null or not exists(select 1 from public.profiles where id=auth.uid() and active and role in ('staff','admin')) then
  raise exception 'Staff access required.';
 end if;
 select wall_yaw into wall from ctfly_tour.cameras where camera_id=p_camera;
 if wall is null or p_yaw is null or not(abs(p_yaw-wall)<=1.35001)
  or p_pitch is null or not(p_pitch between -1.05 and .12)
  or p_hold_ms is null or p_hold_ms not between 500 and 15000 then
  raise exception 'Invalid camera direction.';
 end if;
 update ctfly_tour.cameras set yaw=p_yaw,pitch=p_pitch,
  expires_at=clock_timestamp()+p_hold_ms*interval '1 millisecond' where camera_id=p_camera;
 return jsonb_build_object('ok',true);
end $$;

revoke all on function ctfly_tour.camera_directions(),ctfly_tour.turn_camera(integer,real,real,integer) from public,anon;
grant execute on function ctfly_tour.camera_directions(),ctfly_tour.turn_camera(integer,real,real,integer) to authenticated;
create function public.ctfly_tour_cameras() returns jsonb
language sql security invoker set search_path='' as $$select ctfly_tour.camera_directions()$$;
create function public.ctfly_tour_turn_camera(p_camera integer,p_yaw real,p_pitch real,p_hold_ms integer default 15000) returns jsonb
language sql security invoker set search_path='' as $$select ctfly_tour.turn_camera(p_camera,p_yaw,p_pitch,p_hold_ms)$$;
revoke all on function public.ctfly_tour_cameras(),public.ctfly_tour_turn_camera(integer,real,real,integer) from public,anon;
grant execute on function public.ctfly_tour_cameras(),public.ctfly_tour_turn_camera(integer,real,real,integer) to authenticated;
commit;
