begin;

-- Retire the public RPCs before removing their private implementation.
drop function if exists public.ctfly_tour_presence(uuid,bigint,text,real,real,real,text,boolean,boolean);
drop function if exists public.ctfly_tour_watch();
drop function if exists public.ctfly_tour_cameras();
drop function if exists public.ctfly_tour_turn_camera(integer,real,real,integer);

drop function if exists ctfly_tour.publish(uuid,bigint,text,real,real,real,text,boolean,boolean);
drop function if exists ctfly_tour.watch();
drop function if exists ctfly_tour.camera_directions();
drop function if exists ctfly_tour.turn_camera(integer,real,real,integer);

-- These tables contain only virtual visit positions and virtual camera directions.
drop table if exists ctfly_tour.visitors;
drop table if exists ctfly_tour.cameras;
drop schema if exists ctfly_tour;

commit;
