import os, uuid, pathlib, psycopg
env = dict(l.strip().split('=',1) for l in pathlib.Path('backend/.env').read_text().splitlines() if '=' in l and not l.startswith('#'))
conn = psycopg.connect(host=env['LEXIA_DB_HOST'], port=int(env['LEXIA_DB_PORT']), dbname=env['LEXIA_DB_NAME'],
                       user=env['SPRING_DATASOURCE_USERNAME'], password=env['SPRING_DATASOURCE_PASSWORD'],
                       autocommit=True)
with conn.cursor() as c:
    c.execute("SELECT policyname, cmd FROM pg_policies WHERE schemaname='app' AND tablename='document_version' ORDER BY policyname")
    print('POLICIES:', c.fetchall())
    c.execute("SELECT notary_tenant_id FROM control.notary_remision GROUP BY 1 LIMIT 1")
    row = c.fetchone()
    if not row: print('NO_REMISIONES'); raise SystemExit(0)
    notary = row[0]
    c.execute("SELECT set_config('app.current_tenant_id', %s, false)", (str(notary),))
    c.execute("""SELECT count(*) FROM app.document_version dv
                 WHERE dv.id IN (SELECT nrd.firm_document_version_id
                                 FROM control.notary_remision_document nrd
                                 JOIN control.notary_remision nr ON nr.id=nrd.remision_id
                                 WHERE nr.notary_tenant_id=%s)""", (str(notary),))
    print('VISIBLE_A_NOTARIA:', c.fetchone()[0])
    c.execute("SELECT set_config('app.current_tenant_id', %s, false)", (str(uuid.uuid4()),))
    c.execute("SELECT count(*) FROM app.document_version")
    print('VISIBLE_A_TERCERO:', c.fetchone()[0])
conn.close()
