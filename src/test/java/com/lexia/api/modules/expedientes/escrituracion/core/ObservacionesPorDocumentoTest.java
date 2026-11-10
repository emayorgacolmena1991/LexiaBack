package com.lexia.api.modules.expedientes.escrituracion.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentVersion;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcr;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionCotejoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.ObservacionesDocumentoItem;
import com.lexia.api.modules.expedientes.escrituracion.core.IaAnalysisDtos.AnalysisRequestDTO;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Observaciones ligadas al documento que las origina: validación, deduplicación y agrupación. */
class ObservacionesPorDocumentoTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final UUID STUDY = UUID.randomUUID();

  private final List<TitleObservation> filas = new ArrayList<>();
  private final TitleObservationRepository repo = mock(TitleObservationRepository.class);
  private final LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
  private final DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
  private final TitleStudyRepository studies = mock(TitleStudyRepository.class);
  private LegalDocument cedula;
  private LegalDocument avaluo;
  private ObservacionesPorDocumento porDocumento;

  @BeforeEach
  void setUp() {
    when(repo.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(any(), eq(TENANT)))
        .thenAnswer(llamada -> new ArrayList<>(filas));
    when(repo.save(any()))
        .thenAnswer(
            llamada -> {
              filas.add(llamada.getArgument(0));
              return llamada.getArgument(0);
            });
    doAnswer(llamada -> filas.remove(llamada.<TitleObservation>getArgument(0)))
        .when(repo)
        .delete(any());
    cedula = LegalDocument.create(TENANT, CASO, "cedula.pdf", "CEDULA");
    avaluo = LegalDocument.create(TENANT, CASO, "avaluo.pdf", "AVALUO");
    when(documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(CASO, TENANT))
        .thenReturn(List.of(cedula, avaluo));
    when(versions.findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(cedula.getId(), TENANT))
        .thenReturn(
            Optional.of(DocumentVersion.upload(TENANT, cedula.getId(), 2, "application/pdf", "s", "v2")));
    porDocumento = new ObservacionesPorDocumento(documents, versions, studies, repo);
  }

  @AfterEach
  void limpiar() {
    AuthContext.clear();
  }

  private Set<UUID> documentosDelCaso() {
    return porDocumento.documentosDelExpediente(CASO, TENANT);
  }

  private void reemplazar(ObservacionesCotejo.Entrada... entradas) {
    ObservacionesCotejo.reemplazar(repo, TENANT, STUDY, List.of(entradas), documentosDelCaso());
  }

  private static ObservacionesCotejo.Entrada obs(String code, String mensaje, String documentoId) {
    return new ObservacionesCotejo.Entrada(code, "HIGH", mensaje, documentoId);
  }

  @Test
  void documentoIdValidoSeGuarda() {
    reemplazar(obs("CED_VENCIDA", "Cédula vencida", cedula.getId().toString()));

    assertThat(filas).singleElement().satisfies(f -> assertEquals(cedula.getId(), f.getLegalDocumentId()));
  }

  @Test
  void documentoIdInvalidoConsolidadoOBorradorSeGuardaComoGeneral() {
    reemplazar(
        obs("A", "ajeno", UUID.randomUUID().toString()),
        obs("B", "consolidado", "consolidado"),
        obs("C", "del borrador", "DOC-AB12CD34"),
        obs("D", "inventado", "cedula del comprador"),
        obs("E", "general", null));

    assertEquals(5, filas.size());
    assertThat(filas).allSatisfy(f -> assertNull(f.getLegalDocumentId()));
  }

  @Test
  void mismaObservacionEnDosDocumentosSonDosFilas() {
    reemplazar(
        obs("FIRMA", "Falta firma", cedula.getId().toString()),
        obs("FIRMA", "Falta firma", avaluo.getId().toString()),
        obs("FIRMA", "falta firma ", cedula.getId().toString()));

    assertThat(filas)
        .extracting(TitleObservation::getLegalDocumentId)
        .containsExactly(cedula.getId(), avaluo.getId());
  }

  @Test
  void resueltaNoSeReinsertaPeroEnOtroDocumentoSi() {
    TitleObservation resuelta =
        TitleObservation.create(TENANT, STUDY, "[HIGH] FIRMA: Falta firma", cedula.getId());
    resuelta.resolve();
    filas.add(resuelta);

    reemplazar(
        obs("FIRMA", "Falta firma", cedula.getId().toString()),
        obs("FIRMA", "Falta firma", avaluo.getId().toString()));

    assertThat(filas).hasSize(2);
    assertThat(filas)
        .filteredOn(f -> "OPEN".equals(f.getStatus()))
        .singleElement()
        .satisfies(f -> assertEquals(avaluo.getId(), f.getLegalDocumentId()));
  }

  private void abierta(String detalle, LegalDocument documento) {
    filas.add(TitleObservation.create(TENANT, STUDY, detalle, documento.getId()));
  }

  @Test
  void repetidaConDocumentoInvalidoConservaElDocumentoDeLaAbierta() {
    abierta("[HIGH] FIRMA: Falta firma", cedula);

    reemplazar(obs("FIRMA", "Falta firma", "consolidado"));

    assertThat(filas)
        .singleElement()
        .satisfies(f -> assertEquals(cedula.getId(), f.getLegalDocumentId()));
  }

  @Test
  void repetidaSinDocumentoTambienConservaElDocumentoDeLaAbierta() {
    abierta("[HIGH] FIRMA: Falta firma", cedula);

    reemplazar(obs("FIRMA", "falta firma", null));

    assertThat(filas)
        .singleElement()
        .satisfies(f -> assertEquals(cedula.getId(), f.getLegalDocumentId()));
  }

  @Test
  void repetidaConDocumentoInvalidoConservaTodosLosDocumentosDondeEstabaAbierta() {
    abierta("[HIGH] FIRMA: Falta firma", cedula);
    abierta("[HIGH] FIRMA: Falta firma", avaluo);

    reemplazar(obs("FIRMA", "Falta firma", UUID.randomUUID().toString()));

    assertThat(filas)
        .extracting(TitleObservation::getLegalDocumentId)
        .containsExactly(cedula.getId(), avaluo.getId());
  }

  @Test
  void repetidaInvalidaYValidaNoDuplican() {
    abierta("[HIGH] FIRMA: Falta firma", cedula);

    reemplazar(
        obs("FIRMA", "Falta firma", cedula.getId().toString()),
        obs("FIRMA", "Falta firma", "DOC-AB12CD34"));

    assertThat(filas)
        .singleElement()
        .satisfies(f -> assertEquals(cedula.getId(), f.getLegalDocumentId()));
  }

  @Test
  void sinAbiertaPreviaElDocumentoInvalidoSigueSiendoGeneral() {
    abierta("[HIGH] OTRA: Otra observación", cedula);

    reemplazar(obs("FIRMA", "Falta firma", "consolidado"));

    assertThat(filas).singleElement().satisfies(f -> assertNull(f.getLegalDocumentId()));
  }

  @Test
  void observacionesDeEstudioTituloEnTextoQuedanGenerales() {
    ObservacionesCotejo.reemplazar(
        repo,
        TENANT,
        STUDY,
        ObservacionesCotejo.desdeTextos(List.of("[HIGH] GRAVAMEN: Hipoteca vigente", "Revisar linderos")));

    assertEquals(2, filas.size());
    assertThat(filas).allSatisfy(f -> assertNull(f.getLegalDocumentId()));
  }

  @Test
  void agruparSeparaPorDocumentoConVersionYGenerales() {
    reemplazar(
        obs("CED_VENCIDA", "Cédula vencida", cedula.getId().toString()),
        obs("GRAVAMEN", "Hipoteca vigente", null));
    List<ObservacionCotejoItem> abiertas = ObservacionesCotejo.pendientes(repo, TENANT, STUDY);

    ObservacionesPorDocumento.Agrupadas agrupadas = porDocumento.agrupar(CASO, TENANT, abiertas);

    assertEquals(2, agrupadas.abiertas().size());
    assertThat(agrupadas.porDocumento()).singleElement().satisfies(this::esCedulaV2);
    assertThat(agrupadas.generales())
        .singleElement()
        .satisfies(g -> assertEquals("GRAVAMEN", g.code()));
  }

  private void esCedulaV2(ObservacionesDocumentoItem item) {
    assertEquals(cedula.getId(), item.documentId());
    assertEquals("cedula.pdf", item.nombre());
    assertEquals("CEDULA", item.tipoDocumento());
    assertEquals(2, item.versionNo());
    assertThat(item.observaciones()).singleElement().satisfies(o -> assertEquals("CED_VENCIDA", o.code()));
  }

  @Test
  void observacionDeDocumentoEliminadoPasaAGenerales() {
    UUID eliminado = UUID.randomUUID();
    ObservacionCotejoItem item =
        new ObservacionCotejoItem(UUID.randomUUID(), "X", "HIGH", "m", "PENDIENTE", null, eliminado);

    ObservacionesPorDocumento.Agrupadas agrupadas = porDocumento.agrupar(CASO, TENANT, List.of(item));

    assertThat(agrupadas.porDocumento()).isEmpty();
    assertThat(agrupadas.generales()).containsExactly(item);
  }

  @Test
  void elJsonDeLaIaTraeDocumentoIdOOmitido() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    Observacion con =
        mapper.readValue(
            "{\"codigo\":\"X\",\"severidad\":\"HIGH\",\"mensaje\":\"m\",\"documentoId\":\"abc\"}",
            Observacion.class);
    Observacion sin =
        mapper.readValue("{\"codigo\":\"X\",\"severidad\":\"HIGH\",\"mensaje\":\"m\"}", Observacion.class);

    assertEquals("abc", con.documentoId());
    assertNull(sin.documentoId());
  }

  /** Recorrido completo: la IA devuelve el documentoId y persistStudy lo valida contra el caso. */
  @Test
  void analizarIaPersisteElDocumentoValidoYDescartaElInvalido() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
    LegalCase legalCase = mock(LegalCase.class);
    when(legalCase.getProductCode()).thenReturn("VIV_HIPOTECADA_BIESS");
    LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(legalCase));
    WritingFile ficha = WritingFile.create(TENANT, CASO);
    WritingFileRepository fichas = mock(WritingFileRepository.class);
    when(fichas.findByCaseIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(ficha));
    TitleStudy study = TitleStudy.create(TENANT, ficha.getId());
    when(studies.findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(ficha.getId(), TENANT))
        .thenReturn(Optional.of(study));
    DocumentoTextoOcr fila = new DocumentoTextoOcr();
    fila.setIdExpediente(CASO.toString());
    fila.setIdDocumento(cedula.getId().toString());
    fila.setTipoDocumento("CEDULA");
    fila.setTextoOcr("CEDULA 0912345678");
    DocumentoTextoOcrRepository ocr = mock(DocumentoTextoOcrRepository.class);
    when(ocr.findByIdExpedienteOrderByCreatedAtAsc(CASO.toString())).thenReturn(List.of(fila));
    ExpedienteCompletoLlmService llm = mock(ExpedienteCompletoLlmService.class);
    when(llm.ejecutar(anyString(), anyString(), anyString()))
        .thenReturn(
            new Ejecucion(
                "biess.v1",
                ExtraccionExpedienteCompleto.ok(
                    new ProcesarExpedienteCompletoPayload(
                        List.of(),
                        new DatosExtraidos(null, null, null),
                        new Dictamen(
                            "WITH_OBSERVATIONS",
                            "Observado",
                            List.of(
                                new Observacion("CED_VENCIDA", "HIGH", "Cédula vencida", cedula.getId().toString()),
                                new Observacion("GRAVAMEN", "HIGH", "Hipoteca vigente", "consolidado")))))));
    IaAnalysisService ia =
        new IaAnalysisService(
            legalCases,
            mock(ExpedienteBorradorStore.class),
            fichas,
            studies,
            repo,
            mock(ExtractedDataRepository.class),
            ocr,
            new OcrSessionCacheService(2),
            llm,
            mock(DocumentosExtraidosStore.class),
            mock(AuthorizationService.class),
            new ObjectMapper(),
            porDocumento);

    ia.analizarExpedienteConPromptProducto(CASO, new AnalysisRequestDTO(true, null));

    assertThat(filas)
        .extracting(TitleObservation::getLegalDocumentId)
        .containsExactly(cedula.getId(), null);
  }
}
