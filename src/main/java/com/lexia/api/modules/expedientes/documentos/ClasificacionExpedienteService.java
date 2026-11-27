package com.lexia.api.modules.expedientes.documentos;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoCargadoDTO;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.ReclasificacionDocumentoItem;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.ReclasificacionExpedienteResponse;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService.Modo;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService.Resultado;
import com.lexia.api.modules.expedientes.ejd.ExpedienteValidacionRefreshService;
import com.lexia.api.modules.ia.ocr.LayoutExtractResult;
import com.lexia.api.modules.ia.ocr.OcrAzureBatchService;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class ClasificacionExpedienteService {

  private final CargaDocumentoService carga;
  private final CaseDocumentoStore caseDocs;
  private final ClasificacionDocumentoService clasificador;
  private final DocumentoTextoOcrRepository ocrRows;
  private final OcrAzureBatchService ocr;
  private final ExpedienteValidacionRefreshService validacionRefresh;

  public ClasificacionExpedienteService(
      CargaDocumentoService carga,
      CaseDocumentoStore caseDocs,
      ClasificacionDocumentoService clasificador,
      DocumentoTextoOcrRepository ocrRows,
      OcrAzureBatchService ocr,
      ExpedienteValidacionRefreshService validacionRefresh) {
    this.carga = carga;
    this.caseDocs = caseDocs;
    this.clasificador = clasificador;
    this.ocrRows = ocrRows;
    this.ocr = ocr;
    this.validacionRefresh = validacionRefresh;
  }

  @Transactional
  public ReclasificacionExpedienteResponse reclasificarExpediente(String idExpediente) {
    List<DocumentoCargadoDTO> documentos = carga.listarDocumentos(idExpediente);
    List<ReclasificacionDocumentoItem> items = new ArrayList<>();
    int omitidos = 0;
    for (DocumentoCargadoDTO doc : documentos) {
      if (LegalDocument.esSoloSoporte(doc.codigoTipoDocumento())) {
        omitidos++;
        items.add(omitido(doc, "SOPORTE"));
        continue;
      }
      ReclasificacionDocumentoItem item = reclasificarDocumentoInterno(idExpediente, doc);
      items.add(item);
      if (item.motivoOmision() != null) {
        omitidos++;
      }
    }
    int procesados = documentos.size() - omitidos;
    boolean validaciones = refrescarValidaciones(idExpediente);
    return new ReclasificacionExpedienteResponse(
        idExpediente, procesados, omitidos, validaciones, items);
  }

  @Transactional
  public ReclasificacionDocumentoItem reclasificarDocumento(String idExpediente, String idDocumento) {
    DocumentoCargadoDTO doc =
        carga.listarDocumentos(idExpediente).stream()
            .filter(d -> idDocumento.equals(d.idDocumento()))
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("Documento no encontrado: " + idDocumento));
    ReclasificacionDocumentoItem item = reclasificarDocumentoInterno(idExpediente, doc);
    refrescarValidaciones(idExpediente);
    return item;
  }

  private boolean refrescarValidaciones(String idExpediente) {
    CaseDocumentoStore.CaseFileContext ctx = caseDocs.context(idExpediente);
    if (ctx == null) {
      return false;
    }
    return validacionRefresh.refreshForExpediente(idExpediente, ctx.tenantId());
  }

  private ReclasificacionDocumentoItem reclasificarDocumentoInterno(
      String idExpediente, DocumentoCargadoDTO doc) {
    String tipoAnterior = doc.codigoTipoDocumento();
    if (LegalDocument.ORIGEN_MANUAL.equals(doc.clasificacionOrigen())) {
      return new ReclasificacionDocumentoItem(
          doc.idDocumento(),
          doc.nombreOriginal(),
          tipoAnterior,
          tipoAnterior,
          null,
          null,
          doc.clasificacionEstado(),
          "MANUAL");
    }
    CaseDocumentoStore.CaseFileContext ctx = caseDocs.context(idExpediente);
    if (ctx == null) {
      return new ReclasificacionDocumentoItem(
          doc.idDocumento(),
          doc.nombreOriginal(),
          tipoAnterior,
          tipoAnterior,
          null,
          null,
          doc.clasificacionEstado(),
          "BORRADOR");
    }
    if (ctx.ingestionMode().isFisicoEscaneado()) {
      return new ReclasificacionDocumentoItem(
          doc.idDocumento(),
          doc.nombreOriginal(),
          tipoAnterior,
          tipoAnterior,
          null,
          null,
          doc.clasificacionEstado(),
          "FISICO_ESCANEADO");
    }
    caseDocs.prepararReclasificacion(ctx, doc.idDocumento());
    String texto = textoOcr(idExpediente, doc.idDocumento());
    if (!StringUtils.hasText(texto)) {
      return new ReclasificacionDocumentoItem(
          doc.idDocumento(),
          doc.nombreOriginal(),
          tipoAnterior,
          tipoAnterior,
          null,
          null,
          doc.clasificacionEstado(),
          "SIN_TEXTO_OCR");
    }
    Resultado resultado = clasificador.clasificar(idExpediente, doc.idDocumento(), texto, Modo.ASIGNAR);
    if (!resultado.ejecutado()) {
      return new ReclasificacionDocumentoItem(
          doc.idDocumento(),
          doc.nombreOriginal(),
          tipoAnterior,
          resultado.tipoVigente() != null ? resultado.tipoVigente() : tipoAnterior,
          resultado.tipoDetectado(),
          resultado.confianza(),
          resultado.estado(),
          "CLASIFICADOR_OMITIDO");
    }
    String vigente =
        StringUtils.hasText(resultado.tipoVigente()) ? resultado.tipoVigente() : tipoAnterior;
    if (!vigente.equals(tipoAnterior)) {
      carga.guardarResultadoOcr(
          idExpediente,
          new CargaDocumentoDtos.DocumentoOcrResultadoDTO(
              doc.idDocumento(),
              doc.nombreOriginal(),
              vigente,
              texto,
              null,
              "LEGIBLE",
              null,
              null));
    }
    return new ReclasificacionDocumentoItem(
        doc.idDocumento(),
        doc.nombreOriginal(),
        tipoAnterior,
        vigente,
        resultado.tipoDetectado(),
        resultado.confianza(),
        resultado.estado(),
        null);
  }

  private String textoOcr(String idExpediente, String idDocumento) {
    String guardado =
        ocrRows
            .findByIdExpedienteAndIdDocumento(idExpediente, idDocumento)
            .map(DocumentoTextoOcr::getTextoOcr)
            .orElse("");
    if (StringUtils.hasText(guardado)) {
      return guardado;
    }
    try {
      CargaDocumentoService.StoredDoc file = carga.requireStoredDoc(idExpediente, idDocumento);
      LayoutExtractResult layout = ocr.evaluarYExtraer(file.bytes(), file.mimeType());
      return layout.texto() == null ? "" : layout.texto();
    } catch (RuntimeException ex) {
      return "";
    }
  }

  private static ReclasificacionDocumentoItem omitido(DocumentoCargadoDTO doc, String motivo) {
    return new ReclasificacionDocumentoItem(
        doc.idDocumento(),
        doc.nombreOriginal(),
        doc.codigoTipoDocumento(),
        doc.codigoTipoDocumento(),
        null,
        null,
        doc.clasificacionEstado(),
        motivo);
  }
}