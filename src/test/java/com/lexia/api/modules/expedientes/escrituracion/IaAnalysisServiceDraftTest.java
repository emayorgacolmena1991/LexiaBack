package com.lexia.api.modules.expedientes.escrituracion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcr;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorrador;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisDtos.AnalysisRequestDTO;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService.Ejecucion;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Observacion;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore;
import com.lexia.api.modules.ia.ocr.OcrSessionCacheService;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

@ExtendWith(MockitoExtension.class)
class IaAnalysisServiceDraftTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID USER = UUID.fromString("c1000000-0000-7000-8000-000000000001");
  private static final UUID DRAFT = UUID.fromString("3c68d00a-019a-456f-abfa-d1b117f45181");

  @Mock private LegalCaseRepository legalCases;
  @Mock private ExpedienteBorradorStore borradores;
  @Mock private WritingFileRepository writingFiles;
  @Mock private TitleStudyRepository titleStudies;
  @Mock private TitleObservationRepository titleObservations;
  @Mock private ExtractedDataRepository extractedData;
  @Mock private DocumentoTextoOcrRepository documentoTextoOcr;
  @Mock private OcrSessionCacheService ocrCache;
  @Mock private ExpedienteCompletoLlmService llm;
  @Mock private DocumentosExtraidosStore documentos;
  @Mock private AuthorizationService authorization;

  private IaAnalysisService service;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(USER, UUID.randomUUID(), TENANT, UUID.randomUUID()));
    service =
        new IaAnalysisService(
            legalCases,
            borradores,
            writingFiles,
            titleStudies,
            titleObservations,
            extractedData,
            documentoTextoOcr,
            ocrCache,
            llm,
            documentos,
            authorization,
            new ObjectMapper());
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void analizarIaConBorradorDevuelveDictamenSin404() {
    when(borradores.find(DRAFT, TENANT))
        .thenReturn(
            Optional.of(
                ExpedienteBorrador.loaded(
                    DRAFT, TENANT, null, "VIV_HIPOTECADA_BIESS", "DAULE", "DIGITAL_SEPARADO")));
    when(ocrCache.listResults(anyString())).thenReturn(List.of());
    DocumentoTextoOcr ocr = new DocumentoTextoOcr();
    ocr.setIdDocumento("doc-1");
    ocr.setTipoDocumento("CEDULA");
    ocr.setTextoOcr("Cedula 0912345678");
    when(documentoTextoOcr.findByIdExpedienteOrderByCreatedAtAsc(anyString())).thenReturn(List.of(ocr));
    when(llm.ejecutar(anyString(), eq("VIV_HIPOTECADA_BIESS"), eq("DAULE")))
        .thenReturn(
            new Ejecucion(
                "biess.v1",
                ExtraccionExpedienteCompleto.ok(
                    new ProcesarExpedienteCompletoPayload(
                        List.of(),
                        new DatosExtraidos(null, null, null),
                        new Dictamen(
                            "WITH_OBSERVATIONS",
                            "Gravamen vigente",
                            List.of(new Observacion("GRAVAMEN", "HIGH", "Hipoteca anterior")))))));

    ProcesarExpedienteCompletoResult result =
        service.analizarExpedienteConPromptProducto(
            DRAFT, new AnalysisRequestDTO(false, DRAFT.toString()));

    assertEquals("Gravamen vigente", result.dictamen().resumen());
    assertEquals(1, result.dictamen().observaciones().size());
    assertEquals("Hipoteca anterior", result.dictamen().observaciones().get(0).mensaje());
    verify(documentos, never()).guardar(any(), any(), any(), any());
    verify(extractedData, never()).deleteByCaseIdAndTenantIdAndFieldGroupIn(any(), any(), any());
    verify(borradores).guardarEstudio(eq(DRAFT), eq(TENANT), anyString());
    verify(legalCases, never()).findByIdAndTenantIdAndDeletedAtIsNull(any(), any());
  }

  @Test
  void analizarIaSinExpedienteNiBorradorResponde404() {
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(DRAFT, TENANT)).thenReturn(Optional.empty());
    when(borradores.find(DRAFT, TENANT)).thenReturn(Optional.empty());

    ApiException error =
        assertThrows(
            ApiException.class,
            () ->
                service.analizarExpedienteConPromptProducto(
                    DRAFT, new AnalysisRequestDTO(false, DRAFT.toString())));

    assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    verify(llm, never()).ejecutar(anyString(), anyString(), anyString());
  }
}
