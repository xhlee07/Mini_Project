import { readFile } from 'node:fs/promises';

const setup = await readFile(new URL('../supabase/ctfly.sql', import.meta.url), 'utf8');
const sections = new Map(
    setup.split(/^-- Section: /m).slice(1).map(section => {
        const headerEnd = section.indexOf('\n');
        const name = section.slice(0, headerEnd).trim();
        const body = section.slice(headerEnd + 1).split(/^-- End of database setup/m)[0];
        return [name, body];
    }),
);

export function sqlSection(name) {
    const sql = sections.get(name);
    if (!sql) throw new Error(`Missing database setup section: ${name}`);
    return sql;
}
