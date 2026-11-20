-- Store match sides and the winner as team names, not registration ids.
-- Run this in the CTFLY project shfatnjwpdyxdbehvjil. Do not run it on any other project.
begin;

do $$
declare
 coltype text;
 constraint_name text;
begin
 select data_type into coltype
 from information_schema.columns
 where table_schema='public' and table_name='matches' and column_name='team_a';
 if coltype is null then raise exception 'public.matches.team_a does not exist.'; end if;

 if coltype='uuid' then
  alter table public.matches add column team_a_name text;
  alter table public.matches add column team_b_name text;
  alter table public.matches add column winner_name text;

  update public.matches m set team_a_name=a.team from public.registrations a where a.registration_id=m.team_a;
  update public.matches m set team_b_name=b.team from public.registrations b where b.registration_id=m.team_b;
  update public.matches m set winner_name=w.team from public.registrations w where w.registration_id=m.winner;

  if exists(select 1 from public.matches where team_a is not null and team_a_name is null)
   or exists(select 1 from public.matches where team_b is not null and team_b_name is null)
   or exists(select 1 from public.matches where winner is not null and winner_name is null) then
   raise exception 'A match points at a registration that no longer exists. Restore that registration before removing the link.';
  end if;

  for constraint_name in
   select c.conname
   from pg_constraint c
   where c.conrelid='public.matches'::regclass and c.contype='f' and c.confrelid='public.registrations'::regclass
  loop
   execute format('alter table public.matches drop constraint %I', constraint_name);
  end loop;

  alter table public.matches drop column team_a;
  alter table public.matches drop column team_b;
  alter table public.matches drop column winner;
  alter table public.matches rename column team_a_name to team_a;
  alter table public.matches rename column team_b_name to team_b;
  alter table public.matches rename column winner_name to winner;
 elsif coltype<>'text' then
  raise exception 'public.matches.team_a is %, expected uuid or text.', coltype;
 end if;
end $$;

do $$
begin
 if exists(select 1 from public.matches where done and winner is null) then
  raise exception 'A finished match has no winner name.';
 end if;
 if exists(select 1 from public.matches where not done and winner is not null) then
  raise exception 'An unfinished match already has a winner.';
 end if;
 if exists(select 1 from public.matches where winner is not null and winner is distinct from team_a and (team_b is null or winner is distinct from team_b)) then
  raise exception 'A match winner is not one of its two teams.';
 end if;
end $$;

alter table public.matches drop constraint if exists matches_team_a_length;
alter table public.matches drop constraint if exists matches_team_b_length;
alter table public.matches drop constraint if exists matches_winner_length;
alter table public.matches drop constraint if exists matches_winner_is_side;
alter table public.matches add constraint matches_team_a_length check (team_a is null or length(team_a) between 1 and 80);
alter table public.matches add constraint matches_team_b_length check (team_b is null or length(team_b) between 1 and 80);
alter table public.matches add constraint matches_winner_length check (winner is null or length(winner) between 1 and 80);
alter table public.matches add constraint matches_winner_is_side check (
 (not done and winner is null)
 or (done and winner is not null and (winner=team_a or (team_b is not null and winner=team_b)))
);

do $$
declare
 fn record;
 definition text;
 original_definition text;
begin
 for fn in
  select p.oid
  from pg_proc p
  join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f' and n.nspname in ('public','private')
 loop
  definition:=pg_get_functiondef(fn.oid);
  original_definition:=definition;

  definition:=replace(definition,
   $old$'team_a_id',m.team_a,'team_b_id',m.team_b,'a',m.team_a,'b',m.team_b,'winner',m.winner,'done',m.done,'team_a',a.team,'team_b',b.team,'team_winner',w.team) order by m.round,m.slot)
 from matches m left join registrations a on a.registration_id=m.team_a left join registrations b on b.registration_id=m.team_b left join registrations w on w.registration_id=m.winner$old$,
   $new$'team_a',m.team_a,'team_b',m.team_b,'winner',m.winner,'team_winner',m.winner,'done',m.done) order by m.round,m.slot)
 from matches m$new$);

  definition:=replace(definition,
   $old$'a',m.team_a,'b',m.team_b,'winner',m.winner,'done',m.done,'team_a',a.team,'team_b',b.team,'team_winner',w.team) order by m.round,m.slot)
 from matches m left join registrations a on a.registration_id=m.team_a left join registrations b on b.registration_id=m.team_b left join registrations w on w.registration_id=m.winner$old$,
   $new$'team_a',m.team_a,'team_b',m.team_b,'winner',m.winner,'team_winner',m.winner,'done',m.done) order by m.round,m.slot)
 from matches m$new$);

  definition:=regexp_replace(definition, '''team_a''\s*,\s*a\.team', '''team_a'',m.team_a', 'g');
  definition:=regexp_replace(definition, '''team_b''\s*,\s*b\.team', '''team_b'',m.team_b', 'g');
  definition:=regexp_replace(definition, '''team_winner''\s*,\s*w\.team', '''team_winner'',m.winner', 'g');
  definition:=regexp_replace(definition,
   '\s+left\s+join\s+(?:public\.)?registrations\s+a\s+on\s+a\.registration_id\s*=\s*m\.team_a\s+left\s+join\s+(?:public\.)?registrations\s+b\s+on\s+b\.registration_id\s*=\s*m\.team_b\s+left\s+join\s+(?:public\.)?registrations\s+w\s+on\s+w\.registration_id\s*=\s*m\.winner',
   '',
   'gi');

  definition:=replace(definition,
   'm matches; ids uuid[]; size int; rd int; j int; winner_id uuid;',
   'm matches; sides text[]; size int; rd int; j int; winner_name text;');

  definition:=replace(definition,
   $old$select array_agg(registration_id order by registration_id) into ids from registrations where event_id=ev.event_id and status='Registered';
   n:=coalesce(array_length(ids,1),0); if n<2 then raise exception 'At least two paid teams are required.'; end if;
   size:=2; while size<n loop size:=size*2; end loop;
   -- Spread byes: each first-round pair has at least one team.
   for j in 1..size/2 loop insert into matches(event_id,round,slot,team_a,team_b) values(ev.event_id,1,j,ids[j],ids[j+size/2]); end loop;$old$,
   $new$if exists(select 1 from registrations where event_id=ev.event_id and status='Registered' group by team having count(*)>1) then raise exception 'Each registered team needs a different name before the bracket can be generated.'; end if;
   select array_agg(team order by registration_id) into sides from registrations where event_id=ev.event_id and status='Registered';
   n:=coalesce(array_length(sides,1),0); if n<2 then raise exception 'At least two paid teams are required.'; end if;
   size:=2; while size<n loop size:=size*2; end loop;
   -- Spread byes: each first-round pair has at least one team.
   for j in 1..size/2 loop insert into matches(event_id,round,slot,team_a,team_b) values(ev.event_id,1,j,sides[j],sides[j+size/2]); end loop;$new$);

  definition:=replace(definition,
   $old$winner_id:=(p_data->>'winner')::uuid;
   if m.match_id is null or m.done or m.team_a is null or m.team_b is null or winner_id is null or winner_id not in (m.team_a,m.team_b) then raise exception 'Choose a participant in an unfinished match.'; end if;
   update matches set winner=winner_id,done=true where match_id=m.match_id;
   if mod(m.slot,2)=1 then update matches set team_a=winner_id where event_id=m.event_id and round=m.round+1 and slot=(m.slot+1)/2;
   else update matches set team_b=winner_id where event_id=m.event_id and round=m.round+1 and slot=(m.slot+1)/2; end if;$old$,
   $new$winner_name:=trim(p_data->>'winner');
   if m.match_id is null or m.done or m.team_a is null or m.team_b is null or winner_name is null or winner_name='' or winner_name not in (m.team_a,m.team_b) then raise exception 'Choose a participant in an unfinished match.'; end if;
   update matches set winner=winner_name,done=true where match_id=m.match_id;
   if mod(m.slot,2)=1 then update matches set team_a=winner_name where event_id=m.event_id and round=m.round+1 and slot=(m.slot+1)/2;
   else update matches set team_b=winner_name where event_id=m.event_id and round=m.round+1 and slot=(m.slot+1)/2; end if;$new$);

  if definition is distinct from original_definition then
   execute definition;
  end if;
 end loop;
end $$;

do $$
declare
 snapshot text;
 action text;
begin
 snapshot:=pg_get_functiondef('public.ctfly_snapshot()'::regprocedure);
 action:=pg_get_functiondef('public.ctfly_action(text,jsonb,uuid)'::regprocedure);
 if position('a.registration_id=m.team_a' in snapshot)>0 or position(',a.team' in snapshot)>0 then
  raise exception 'Snapshot still reads registration names near: %', substring(snapshot from greatest(position('from matches' in snapshot)-180,1) for 700);
 end if;
 if position('team_winner'',m.winner' in snapshot)=0 then
  raise exception 'Snapshot does not return the winner name stored on the match.';
 end if;
 if position('array_agg(registration_id' in action)>0 or position('winner'')::uuid' in action)>0 or position('winner_id' in action)>0 then
  raise exception 'Bracket or winner action still uses a registration id.';
 end if;
 if position('array_agg(team order by registration_id) into sides' in action)=0 or position('winner_name not in (m.team_a,m.team_b)' in action)=0 then
  raise exception 'Bracket or winner action was not updated.';
 end if;
end $$;

commit;
