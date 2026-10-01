package com.lexia.api.modules.expedientes.minutas;

import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Captura BIESS (monto, tasa, plazo, cuota, apoderado) en {@code extracted_data}, grupo {@value #GRUPO},
 * ligada al expediente. Sobrevive a F5 y al cambio de etapa.
 */
@Component
public class DatosBiessStore {

  public static final String GRUPO = "biess";

  private static final String MONTO = "biess.monto";
  private static final String TASA = "biess.tasa";
  private static final String PLAZO = "biess.plazo";
  private static final String CUOTA = "biess.cuota";
  private static final String APODERADO = "biess.apoderado";

  private final ExtractedDataRepository extractedData;

  public DatosBiessStore(ExtractedDataRepository extractedData) {
    this.extractedData = extractedData;
  }

  @Transactional
  public void guardar(UUID tenantId, UUID caseId, DatosBiessMinuta datos) {
    extractedData.deleteByCaseIdAndTenantIdAndFieldGroupIn(caseId, tenantId, Set.of(GRUPO));
    DatosBiessMinuta d = datos == null ? DatosBiessMinuta.empty() : datos;
    save(tenantId, caseId, MONTO, d.monto());
    save(tenantId, caseId, TASA, d.tasa());
    save(tenantId, caseId, PLAZO, d.plazo());
    save(tenantId, caseId, CUOTA, d.cuota());
    save(tenantId, caseId, APODERADO, d.apoderado());
  }

  /** {@code null} si el expediente nunca persistió captura BIESS. */
  @Transactional(readOnly = true)
  public DatosBiessMinuta cargar(UUID caseId, UUID tenantId) {
    Map<String, String> values = new LinkedHashMap<>();
    for (ExtractedData row :
        extractedData.findByCaseIdAndTenantIdAndFieldGroupOrderByFieldLabelAsc(
            caseId, tenantId, GRUPO)) {
      if (row.getFieldLabel() != null) {
        values.put(row.getFieldLabel(), row.getFieldValue() == null ? "" : row.getFieldValue());
      }
    }
    if (values.isEmpty()) {
      return null;
    }
    return new DatosBiessMinuta(
        values.get(MONTO),
        values.get(TASA),
        values.get(PLAZO),
        values.get(CUOTA),
        values.get(APODERADO));
  }

  private void save(UUID tenantId, UUID caseId, String label, String valor) {
    extractedData.save(ExtractedData.create(tenantId, caseId, label, valor == null ? "" : valor, GRUPO));
  }
}
