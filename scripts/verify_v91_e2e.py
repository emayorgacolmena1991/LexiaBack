import pathlib, uuid, psycopg, sys

env = dict(l.strip().split('=',1) for l in pathlib.Path('backend/.env').read_text().splitlines() if '=' in l and not l.startswith('#'))

def connect(user, pwd):
    return psycopg.connect(host=env['LEXIA_DB_HOST'], port=int(env['LEXIA_DB_PORT']),
                           dbname=env['LEXIA_DB_NAME'], user=user, password=pwd, autocommit=False)

MIG = connect(env['SPRING_FLYWAY_USER'], env['SPRING_FLYWAY_PASSWORD'])
APP = connect(env['SPRING_DATASOURCE_USERNAME'], env['SPRING_DATASOURCE_PASSWORD'])

# 0) NOT NULL columns sin default
with MIG.cursor() as c:
    for sch, tbl in [('control','notary_remision'),('control','notary_remision_document'),
                     ('app','document'),('app','document_version')]:
        c.execute("""SELECT column_name FROM information_schema.columns
                     WHERE table_schema=%s AND table_name=%s
                       AND is_nullable='NO' AND column_default IS NULL AND column_name<>'id'
                     ORDER BY ordinal_position""", (sch, tbl))
        print(f'{tbl}_REQ:', [r[0] for r in c.fetchall()])
MIG.rollback()

# 1) partnership ACTIVE
with MIG.cursor() as c:
    c.execute("SELECT * FROM control.tenant_notary_partnership WHERE status='ACTIVE' LIMIT 1")
    cols = [d.name for d in c.description]
    row = c.fetchone()
    print('PARTNERSHIP_COLS:', cols)
    p = dict(zip(cols, row))
    print('PARTNERSHIP_ROW:', p)
MIG.rollback()

FIRM   = p.get('tenant_id') or p.get('firm_tenant_id')
NOTARY = p.get('partner_tenant_id') or p.get('notary_tenant_id')
if not FIRM or not NOTARY:
    print('PARTNERSHIP_SHAPE_UNKNOWN'); sys.exit(1)

DOC_ID, VER_ID, REM_ID, REM_DOC_ID = (str(uuid.uuid4()) for _ in range(4))

# 2) seed
try:
    with MIG.cursor() as c:
        c.execute("INSERT INTO app.document (id, tenant_id, name) VALUES (%s,%s,'v91-test')", (DOC_ID, FIRM))
        c.execute("""INSERT INTO app.document_version
                     (id, tenant_id, document_id, version_no, origin, content_hash, storage_key)
                     VALUES (%s,%s,%s,1,'UPLOAD','x','x')""", (VER_ID, FIRM, DOC_ID))
        c.execute("INSERT INTO control.notary_remision (id, firm_tenant_id, notary_tenant_id) VALUES (%s,%s,%s)", (REM_ID, FIRM, NOTARY))
        c.execute("""INSERT INTO control.notary_remision_document
                     (id, remision_id, firm_tenant_id, firm_document_version_id)
                     VALUES (%s,%s,%s,%s)""", (REM_DOC_ID, REM_ID, FIRM, VER_ID))
    MIG.commit()
    print('SEED_OK')
except Exception as e:
    MIG.rollback(); print('SEED_FAIL:', e); MIG.close(); APP.close(); sys.exit(1)

# 3) verify bajo RLS real de lexia_app
for guc, label in [(NOTARY,'NOTARY'), (FIRM,'FIRM'), (str(uuid.uuid4()),'RANDOM')]:
    with APP.cursor() as c:
        c.execute("SELECT set_config('app.current_tenant_id', %s, true)", (str(guc),))
        c.execute("SELECT count(*) FROM app.document_version WHERE id=%s", (VER_ID,))
        print(f'VISIBLE_TO_{label}:', c.fetchone()[0])
    APP.rollback()

# 4) cleanup
with MIG.cursor() as c:
    c.execute("DELETE FROM control.notary_remision_document WHERE id=%s", (REM_DOC_ID,))
    c.execute("DELETE FROM control.notary_remision WHERE id=%s", (REM_ID,))
    c.execute("DELETE FROM app.document_version WHERE id=%s", (VER_ID,))
    c.execute("DELETE FROM app.document WHERE id=%s", (DOC_ID,))
MIG.commit()
print('CLEANUP_OK')
MIG.close(); APP.close()
