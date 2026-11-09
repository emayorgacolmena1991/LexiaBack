-- Una sola remisión abierta por expediente del bufete. Las terminales no ocupan el índice.
DO $$
DECLARE
    duplicados text;
BEGIN
    SELECT string_agg(id::text, ', ' ORDER BY emitido_en, id::text)
      INTO duplicados
      FROM control.notary_remision
     WHERE estado NOT IN ('DEVUELTO_AL_BUFETE', 'NOTARIA_COMPLETADA')
       AND (firm_tenant_id, firm_legal_case_id) IN (
            SELECT firm_tenant_id, firm_legal_case_id
              FROM control.notary_remision
             WHERE estado NOT IN ('DEVUELTO_AL_BUFETE', 'NOTARIA_COMPLETADA')
             GROUP BY firm_tenant_id, firm_legal_case_id
            HAVING count(*) > 1
       );
    IF duplicados IS NOT NULL THEN
        RAISE EXCEPTION 'V97: remisiones abiertas duplicadas: %', duplicados;
    END IF;
END
$$;

CREATE UNIQUE INDEX ux_notary_remision_abierta
    ON control.notary_remision (firm_tenant_id, firm_legal_case_id)
    WHERE estado NOT IN ('DEVUELTO_AL_BUFETE', 'NOTARIA_COMPLETADA');
