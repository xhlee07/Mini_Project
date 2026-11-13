begin;

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
        select 1 from public.profiles
        where id = auth.uid() and active and role in ('staff', 'admin')
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
            'Test:' || t.id as key,
            t.user_id, t.kind, t.status, t.total, t.method, t.reference,
            'test'::text as environment,
            to_jsonb(t) as payment,
            (select to_jsonb(b) from public.bills b where b.id = t.bill_id) as receipt
        from public.test_payments t
        where (p_kind = 'All' or t.kind = p_kind)
            and (p_status = 'All' or t.status = p_status)
            and (p_day is null or t.created_at >= day_start and t.created_at < day_end)

        union all

        select
            b.created_at, 'Bill:' || b.id,
            b.user_id, b.kind, b.status, b.total, b.method,
            coalesce(b.payment_reference, b.id::text),
            'counter'::text, null::jsonb, to_jsonb(b)
        from public.bills b
        where b.environment <> 'test'
            and (p_kind = 'All' or b.kind = p_kind)
            and (p_status = 'All' or b.status = p_status)
            and (p_day is null or b.created_at >= day_start and b.created_at < day_end)
    ), page as (
        select t.*, p.name as customer_name
        from payments t
        join public.profiles p on p.id = t.user_id
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
    to authenticated;

commit;
