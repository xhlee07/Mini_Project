import assert from 'node:assert/strict';
import { sqlSection } from './sql-section.mjs';

export async function verifyTransactionFilters({ db, customer, staff }) {
    await db.exec('reset role');
    const sql = sqlSection('transaction-filters');
    await db.exec(sql);
    await db.exec(sql);
    const as = async id => {
        await db.exec('reset role');
        await db.query("select set_config('request.jwt.claim.sub',$1,false)", [id]);
        await db.exec('set role authenticated');
    };
    const query = async (kind = 'All', status = 'All', day = '1999-01-02', search = '', before = null, key = null) => {
        await as(staff);
        return (await db.query('select public.ctfly_staff_transactions($1,$2,$3,$4,$5,$6) value', [day,status,kind,search,before,key])).rows[0].value;
    };
    await as(customer);
    await assert.rejects(() => db.query('select public.ctfly_staff_transactions()'), /Staff access/);
    await db.exec('reset role;set role anon');
    await assert.rejects(() => db.query('select public.ctfly_staff_transactions()'), /permission denied/);
    await assert.rejects(() => query('Reward'), /supported transaction type/);
    await assert.rejects(() => query('All','Deleted'), /supported payment status/);
    await assert.rejects(() => query('All','All',null,'x'.repeat(101)), /100 characters/);
    await assert.rejects(() => query('All','All',null,'',new Date().toISOString()), /cursor/);
    await db.exec('reset role;begin');
    try {
        for (const [kind,count,hour] of [['Booking',45,16],['Order',90,18],['Event',2,20],['Extension',3,21]]) {
            await db.query(`insert into public.bills(user_id,kind,source_id,subtotal,discount,total,points,created_at)
                select $1,$2,gen_random_uuid(),600,0,600,0,('1999-01-01T'||$3||':00Z')::timestamptz+make_interval(secs=>n)
                from generate_series(1,$4::int) n`,[customer,kind,hour.toString(),count]);
        }
        await db.query(`insert into public.bills(user_id,kind,source_id,subtotal,discount,total,points,status,created_at)
            values($1,'Booking',gen_random_uuid(),600,0,600,0,'Void','1999-01-01T22:00Z')`,[customer]);
        const first = await query('Booking','Paid');
        assert.equal(first.items.length,40);
        assert.equal(first.has_more,true);
        assert.ok(first.items.every(r=>r.kind==='Booking' && r.status==='Paid'));
        const second = await query('Booking','Paid','1999-01-02','',first.next_before,first.next_key);
        assert.equal(second.items.length,5,'matching older bookings are not hidden behind newer orders');
        assert.equal(second.has_more,false);
        assert.equal(new Set([...first.items,...second.items].map(r=>r.history_key)).size,45);
        assert.equal((await query('Booking','Void')).items.length,1);
        assert.equal((await query('Event')).items.length,2);
        assert.equal((await query('Extension')).items.length,3);
        assert.ok((await query('Order')).items.every(r=>r.kind==='Order'));
        assert.equal((await query('Booking','Paid','1999-01-03')).items.length,0);
        assert.equal((await query('Booking','Paid','1999-01-02','%')).items.length,0);
        assert.equal((await query('Booking','Paid','1999-01-02',first.items[0].reference)).items.length,1);
        await db.exec('reset role');
        await db.query(`insert into public.test_payments(user_id,kind,source_id,method,subtotal,discount,total,points,items,status,expires_at,created_at)
            values($1,'Booking',gen_random_uuid(),'TNG',600,0,600,0,'[]','Succeeded',now()+interval '1 hour','1999-01-02T01:00Z')`,[customer]);
        const simulated = await query('Booking','Succeeded');
        assert.equal(simulated.items.length,1);
        assert.equal(simulated.items[0].environment,'test');
        assert.equal(simulated.items[0].payment.kind,'Booking');
        await db.exec('reset role');
        await db.query('update profiles set active=false where id=$1',[staff]);
        await as(staff);
        await assert.rejects(()=>db.query('select public.ctfly_staff_transactions()'), /Staff access/);
    } finally { await db.exec('rollback;reset role'); }
    console.log('PASS: transaction type + status + venue date + search before pagination; older matches, cursor reset inputs, all four types and staff-only access.');
}
