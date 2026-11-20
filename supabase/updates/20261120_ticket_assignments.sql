-- Move the ticket assignee out of tickets.assigned_staff.
-- assignment_id is the primary key. ticket_id and staff_id reference their tables. assigned_at is a normal column.
-- Run this in the CTFLY project shfatnjwpdyxdbehvjil. Do not run it on any other project.
begin;

create table if not exists public.ticket_assignments (
 assignment_id uuid primary key default gen_random_uuid(),
 ticket_id uuid not null unique references public.tickets(ticket_id) on delete cascade,
 staff_id uuid not null references public.staff(staff_id),
 assigned_at timestamptz not null default now()
);

do $$
begin
 if exists(
  select 1 from information_schema.columns
  where table_schema='public' and table_name='tickets' and column_name='assigned_staff'
 ) then
  execute $copy$
   insert into public.ticket_assignments(ticket_id,staff_id,assigned_at)
   select t.ticket_id,t.assigned_staff,t.created_at
   from public.tickets t
   where t.assigned_staff is not null
   and not exists(select 1 from public.ticket_assignments a where a.ticket_id=t.ticket_id)
  $copy$;
 end if;
end $$;

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
   $old$'tickets',coalesce((select jsonb_agg(t order by created_at desc) from tickets t),'[]'::jsonb)$old$,
   $new$'tickets',coalesce((select jsonb_agg((to_jsonb(t) || jsonb_build_object('assigned_staff', a.staff_id)) order by t.created_at desc) from tickets t left join ticket_assignments a on a.ticket_id=t.ticket_id),'[]'::jsonb),'ticket_assignments',coalesce((select jsonb_agg(a order by a.assigned_at desc) from ticket_assignments a),'[]'::jsonb)$new$);

  if position('ticket_assignments'',coalesce((select jsonb_agg(a order by a.assigned_at desc) from ticket_assignments a)' in definition)=0 then
   definition:=replace(definition,
    $mid$'tickets',coalesce((select jsonb_agg((to_jsonb(t) || jsonb_build_object('assigned_staff', a.staff_id)) order by t.created_at desc) from tickets t left join ticket_assignments a on a.ticket_id=t.ticket_id),'[]'::jsonb)$mid$,
    $full$'tickets',coalesce((select jsonb_agg((to_jsonb(t) || jsonb_build_object('assigned_staff', a.staff_id)) order by t.created_at desc) from tickets t left join ticket_assignments a on a.ticket_id=t.ticket_id),'[]'::jsonb),'ticket_assignments',coalesce((select jsonb_agg(a order by a.assigned_at desc) from ticket_assignments a),'[]'::jsonb)$full$);
  end if;

  definition:=replace(definition,
   'update tickets set assigned_staff=me.profile_id,status=''In progress'' where ticket_id=tk.ticket_id;',
   'insert into ticket_assignments(ticket_id,staff_id) values(tk.ticket_id,me.profile_id); update tickets set status=''In progress'' where ticket_id=tk.ticket_id;');

  definition:=replace(definition,
   'tk.assigned_staff=me.profile_id',
   'exists(select 1 from ticket_assignments a where a.ticket_id=tk.ticket_id and a.staff_id=me.profile_id)');

  definition:=replace(definition,
   'update tickets set status=''Open'',assigned_staff=null,resolution='''' where ticket_id=tk.ticket_id;',
   'delete from ticket_assignments where ticket_id=tk.ticket_id; update tickets set status=''Open'',resolution='''' where ticket_id=tk.ticket_id;');

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
 if position('left join ticket_assignments a on a.ticket_id=t.ticket_id' in snapshot)=0
  or position('ticket_assignments'',coalesce((select jsonb_agg(a order by a.assigned_at desc) from ticket_assignments a)' in snapshot)=0 then
  raise exception 'Snapshot still does not read ticket_assignments near: %', substring(snapshot from greatest(position('tickets' in snapshot),1) for 700);
 end if;
 if position('insert into ticket_assignments(ticket_id,staff_id)' in action)=0
  or position('delete from ticket_assignments where ticket_id=tk.ticket_id' in action)=0
  or position('tk.assigned_staff' in action)>0
  or position('assigned_staff=' in action)>0 then
  raise exception 'Claim, resolve, or reopen still uses tickets.assigned_staff.';
 end if;
end $$;

alter table public.tickets drop column if exists assigned_staff;

alter table public.ticket_assignments enable row level security;
revoke all on public.ticket_assignments from public, anon, authenticated;

commit;
