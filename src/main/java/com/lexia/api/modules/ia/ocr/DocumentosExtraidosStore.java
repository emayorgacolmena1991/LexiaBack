package com.lexia.api.modules.ia.ocr;

import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DocumentoExtraido;
import com.lexia.api.modules.ia.ocr.OcrExpedienteTexto.DocOcr;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Extracción por documento del single-pass guardada en {@code extracted_data} (grupo
 * {@value #GRUPO}). Etiqueta: {@code NNN::documentoId::clave}; las claves {@code @tipo},
 * {@code @nombre} y {@code @resumen} son metadatos del documento.
 */
@Component
public class DocumentosExtraidosStore {

  public static final String GRUPO = "documento";

  private static final String SEP = "::";
  private static final String META_TIPO = "@tipo";
  private static final String META_NOMBRE = "@nombre";
  private static final String META_RESUMEN = "@resumen";
  private static final int MAX_ID = 60;
  private static final int MAX_CLAVE = 120;

  private final ExtractedDataRepository extractedData;

  public DocumentosExtraidosStore(ExtractedDataRepository extractedData) {
    this.extractedData = extractedData;
  }

  /**
   * Reemplaza la extracción documental del expediente. {@code cabeceras} son los
   * {@code <documento>} enviados al LLM; resuelven tipo/nombre de archivo por id (o por posición
   * si el modelo alteró el id).
   */
  @Transactional
  public List<DocumentoExtraidoDTO> guardar(
      UUID tenantId, UUID caseId, List<DocumentoExtraido> docs, List<DocOcr> cabeceras) {
    extractedData.deleteByCaseIdAndTenantIdAndFieldGroupIn(caseId, tenantId, Set.of(GRUPO));
    List<DocumentoExtraidoDTO> out = new ArrayList<>();
    if (docs == null) {
      return out;
    }
    Map<String, DocOcr> porId = new LinkedHashMap<>();
    for (DocOcr c : cabeceras == null ? List.<DocOcr>of() : cabeceras) {
      if (c != null && StringUtils.hasText(c.id())) {
        porId.putIfAbsent(c.id().trim(), c);
      }
    }
    int idx = 0;
    for (DocumentoExtraido doc : docs) {
      if (doc == null) {
        continue;
      }
      DocOcr cab = cabecera(doc.documentoId(), idx, porId, cabeceras);
      String id =
          StringUtils.hasText(doc.documentoId())
              ? doc.documentoId().trim()
              : cab != null && StringUtils.hasText(cab.id()) ? cab.id().trim() : "doc-" + (idx + 1);
      String tipo =
          firstText(doc.tipoDocumento(), cab == null ? null : cab.tipo(), "DOCUMENTO");
      String nombre = cab == null ? null : cab.nombre();
      Map<String, String> datos = new LinkedHashMap<>();
      OcrCotejoService.aplanarDatos("", doc.datosClave(), datos);

      String prefijo = String.format("%03d", idx) + SEP + truncar(id, MAX_ID) + SEP;
      guardarCampo(tenantId, caseId, prefijo + META_TIPO, tipo);
      guardarCampo(tenantId, caseId, prefijo + META_NOMBRE, nombre);
      guardarCampo(tenantId, caseId, prefijo + META_RESUMEN, doc.resumen());
      for (Map.Entry<String, String> e : datos.entrySet()) {
        guardarCampo(tenantId, caseId, prefijo + truncar(e.getKey(), MAX_CLAVE), e.getValue());
      }
      out.add(new DocumentoExtraidoDTO(id, tipo, nombre, doc.resumen(), datos));
      idx++;
    }
    return out;
  }

  public List<DocumentoExtraidoDTO> cargar(UUID caseId, UUID tenantId) {
    Map<String, Acumulado> porPrefijo = new LinkedHashMap<>();
    for (ExtractedData row :
        extractedData.findByCaseIdAndTenantIdAndFieldGroupOrderByFieldLabelAsc(
            caseId, tenantId, GRUPO)) {
      String[] partes = row.getFieldLabel() == null ? new String[0] : row.getFieldLabel().split(SEP, 3);
      if (partes.length < 3) {
        continue;
      }
      Acumulado acc =
          porPrefijo.computeIfAbsent(partes[0] + SEP + partes[1], k -> new Acumulado(partes[1]));
      String clave = partes[2];
      String valor = row.getFieldValue();
      switch (clave) {
        case META_TIPO -> acc.tipo = valor;
        case META_NOMBRE -> acc.nombre = valor;
        case META_RESUMEN -> acc.resumen = valor;
        default -> {
          if (StringUtils.hasText(valor)) {
            acc.datos.put(clave, valor);
          }
        }
      }
    }
    List<DocumentoExtraidoDTO> out = new ArrayList<>();
    for (Acumulado acc : porPrefijo.values()) {
      out.add(new DocumentoExtraidoDTO(acc.id, acc.tipo, acc.nombre, acc.resumen, acc.datos));
    }
    return out;
  }

  private void guardarCampo(UUID tenantId, UUID caseId, String label, String valor) {
    if (!StringUtils.hasText(valor)) {
      return;
    }
    extractedData.save(ExtractedData.create(tenantId, caseId, label, valor.trim(), GRUPO));
  }

  private static DocOcr cabecera(
      String documentoId, int idx, Map<String, DocOcr> porId, List<DocOcr> cabeceras) {
    if (StringUtils.hasText(documentoId)) {
      DocOcr hit = porId.get(documentoId.trim());
      if (hit != null) {
        return hit;
      }
    }
    return cabeceras != null && idx < cabeceras.size() ? cabeceras.get(idx) : null;
  }

  private static String firstText(String... values) {
    for (String v : values) {
      if (StringUtils.hasText(v)) {
        return v.trim();
      }
    }
    return null;
  }

  private static String truncar(String s, int max) {
    String v = s.trim().replace(SEP, ":");
    return v.length() <= max ? v : v.substring(0, max);
  }

  /** Extracción documental tal como la consumen el cotejo y el FE. */
  public record DocumentoExtraidoDTO(
      String documentoId,
      String tipoDocumento,
      String nombre,
      String resumen,
      Map<String, String> datosClave) {}

  private static final class Acumulado {
    private final String id;
    private String tipo;
    private String nombre;
    private String resumen;
    private final Map<String, String> datos = new LinkedHashMap<>();

    private Acumulado(String id) {
      this.id = id;
    }
  }
}
