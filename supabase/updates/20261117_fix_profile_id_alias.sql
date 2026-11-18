-- After verification the app loads the account with ctfly_snapshot.
-- Profiles are keyed by profile_id. Older function text still says p.id, which
-- Postgres reports as "column p.id does not exist".
-- Promotion rows keep p.id; only profile predicates are rewritten.
begin;

do $$
declare fn record; definition text; original_definition text;
begin
 for fn in
  select p.oid
  from pg_proc p
  join pg_namespace n on n.oid=p.pronamespace
  where p.prokind='f'
   and n.nspname in ('public','private','ctfly_private')
   and p.prosrc like '%profiles%'
   and p.prosrc like '%p.id%'
 loop
  definition:=pg_get_functiondef(fn.oid);
  original_definition:=definition;
  definition:=regexp_replace(definition,
   $pattern$(\m(on|where|and)[[:space:]]+)p\.id\M$pattern$,
   $replacement$\1p.profile_id$replacement$,'gi');
  definition:=regexp_replace(definition,
   $pattern$((from|join)[[:space:]]+(public\.)?profiles[[:space:]]+p[[:space:]]+(where|on)[[:space:]]+)p\.id\M$pattern$,
   $replacement$\1p.profile_id$replacement$,'gi');
  if definition is distinct from original_definition then
   execute definition;
   raise notice 'Updated %', fn.oid::regprocedure;
  end if;
 end loop;
end $$;

notify pgrst, 'reload schema';
commit;
