package com.lexia.api.modules.expedientes.documentos;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

public interface DocumentoTextoOcrRepository extends JpaRepository<DocumentoTextoOcr, UUID> {

  Optional<DocumentoTextoOcr> findByIdExpedienteAndIdDocumento(
      String idExpediente, String idDocumento);

  List<DocumentoTextoOcr> findByIdExpedienteOrderByCreatedAtAsc(String idExpediente);

  /** Dos POST paralelos al mismo documento no chocan con la unique (id_expediente, id_documento). */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Transactional
  @Query(
      value =
          """
          INSERT INTO app.documento_texto_ocr
            (id, tenant_id, id_expediente, id_documento, tipo_documento, texto_ocr,
             analisis_json, estado_doc, motivo, confianza)
          VALUES
            (:id, CAST(:tenantId AS uuid), :idExpediente, :idDocumento, :tipo, :texto,
             :analisis, :estado, :motivo, CAST(:confianza AS integer))
          ON CONFLICT (id_expediente, id_documento) DO UPDATE SET
            tenant_id = COALESCE(EXCLUDED.tenant_id, app.documento_texto_ocr.tenant_id),
            tipo_documento = EXCLUDED.tipo_documento,
            texto_ocr = CASE
              WHEN EXCLUDED.id_documento = 'consolidado'
                   AND char_length(COALESCE(app.documento_texto_ocr.texto_ocr, ''))
                     > char_length(COALESCE(EXCLUDED.texto_ocr, ''))
              THEN app.documento_texto_ocr.texto_ocr
              ELSE COALESCE(EXCLUDED.texto_ocr, app.documento_texto_ocr.texto_ocr)
            END,
            analisis_json = COALESCE(EXCLUDED.analisis_json, app.documento_texto_ocr.analisis_json),
            estado_doc = EXCLUDED.estado_doc,
            motivo = EXCLUDED.motivo,
            confianza = EXCLUDED.confianza,
            updated_at = now()
          """,
      nativeQuery = true)
  void upsert(
      UUID id,
      UUID tenantId,
      String idExpediente,
      String idDocumento,
      String tipo,
      String texto,
      String analisis,
      String estado,
      String motivo,
      Integer confianza);
}
