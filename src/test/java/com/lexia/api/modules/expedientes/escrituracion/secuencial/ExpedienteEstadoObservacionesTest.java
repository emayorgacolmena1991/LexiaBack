package com.lexia.api.modules.expedientes.escrituracion.secuencial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.CaseStageRepository;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.DocumentVersionRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.documentos.LegalDocumentRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.EstadoEscrituracion;
import com.lexia.api.modules.expedientes.escrituracion.core.MinutaDraftRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.ObservacionesPorDocumento;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleObservation;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleObservationRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleStudy;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleStudyRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.expedientes.minutas.DatosBiessStore;
import com.lexia.api.modules.expedientes.minutas.MinutaGenerationService;
import com.lexia.api.modules.expedientes.proceso.ProcessStageDefRepository;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Reapertura: GET escrituracion/estado trae las observaciones abiertas agrupadas (pantalla E3). */
class ExpedienteEstadoObservacionesTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();

  @AfterEach
  void limpiar() {
    AuthContext.clear();
  }

  @Test
  void estadoTraeAbiertasPorDocumentoYGenerales() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
    LegalCase legalCase = mock(LegalCase.class);
    when(legalCase.getId()).thenReturn(CASO);
    LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(legalCase));
    WritingFile ficha = WritingFile.create(TENANT, CASO);
    WritingFileRepository fichas = mock(WritingFileRepository.class);
    when(fichas.findByCaseIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(ficha));
    TitleStudy study = TitleStudy.create(TENANT, ficha.getId());
    TitleStudyRepository studies = mock(TitleStudyRepository.class);
    when(studies.findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(ficha.getId(), TENANT))
        .thenReturn(Optional.of(study));
    LegalDocument cedula = LegalDocument.create(TENANT, CASO, "cedula.pdf", "CEDULA");
    LegalDocumentRepository documents = mock(LegalDocumentRepository.class);
    when(documents.findByCaseIdAndTenantIdAndDeletedAtIsNullOrderByCreatedAtAsc(CASO, TENANT))
        .thenReturn(List.of(cedula));
    TitleObservation deCedula =
        TitleObservation.create(TENANT, study.getId(), "[HIGH] CED_VENCIDA: Cédula vencida", cedula.getId());
    TitleObservation general =
        TitleObservation.create(TENANT, study.getId(), "[MEDIUM] GRAVAMEN: Hipoteca vigente");
    TitleObservation resuelta =
        TitleObservation.create(TENANT, study.getId(), "[LOW] FIRMA: Falta firma", cedula.getId());
    resuelta.resolve();
    TitleObservationRepository observaciones = mock(TitleObservationRepository.class);
    when(observaciones.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(study.getId(), TENANT))
        .thenReturn(List.of(deCedula, general, resuelta));
    DocumentVersionRepository versions = mock(DocumentVersionRepository.class);
    when(versions.findFirstByDocumentIdAndTenantIdOrderByVersionNoDesc(any(), any()))
        .thenReturn(Optional.empty());

    ExpedienteEstadoService service =
        new ExpedienteEstadoService(
            mock(AuthorizationService.class),
            legalCases,
            fichas,
            mock(MinutaDraftRepository.class),
            studies,
            observaciones,
            mock(ExtractedDataRepository.class),
            mock(DatosBiessStore.class),
            mock(DocumentosExtraidosStore.class),
            mock(MinutaGenerationService.class),
            mock(CaseStageRepository.class),
            mock(ProcessStageDefRepository.class),
            new ObservacionesPorDocumento(documents, versions, studies, observaciones, mock(CargaDocumentoService.class)));

    EstadoEscrituracion estado = service.estadoFlujo(CASO);

    assertThat(estado.observacionesAbiertas())
        .extracting(o -> o.code())
        .containsExactly("CED_VENCIDA", "GRAVAMEN");
    assertThat(estado.porDocumento())
        .singleElement()
        .satisfies(
            d -> {
              assertEquals(cedula.getId(), d.documentId());
              assertEquals("CEDULA", d.tipoDocumento());
              assertThat(d.observaciones()).extracting(o -> o.code()).containsExactly("CED_VENCIDA");
            });
    assertThat(estado.generales()).extracting(o -> o.code()).containsExactly("GRAVAMEN");
  }

  @Test
  void sinFichaLasListasVanVacias() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
    LegalCase legalCase = mock(LegalCase.class);
    when(legalCase.getId()).thenReturn(CASO);
    LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(legalCase));
    TitleStudyRepository studies = mock(TitleStudyRepository.class);
    TitleObservationRepository observaciones = mock(TitleObservationRepository.class);

    ExpedienteEstadoService service =
        new ExpedienteEstadoService(
            mock(AuthorizationService.class),
            legalCases,
            mock(WritingFileRepository.class),
            mock(MinutaDraftRepository.class),
            studies,
            observaciones,
            mock(ExtractedDataRepository.class),
            mock(DatosBiessStore.class),
            mock(DocumentosExtraidosStore.class),
            mock(MinutaGenerationService.class),
            mock(CaseStageRepository.class),
            mock(ProcessStageDefRepository.class),
            new ObservacionesPorDocumento(
                mock(LegalDocumentRepository.class), mock(DocumentVersionRepository.class), studies, observaciones,
                mock(CargaDocumentoService.class)));

    EstadoEscrituracion estado = service.estadoFlujo(CASO);

    assertThat(estado.observacionesAbiertas()).isEmpty();
    assertThat(estado.porDocumento()).isEmpty();
    assertThat(estado.generales()).isEmpty();
  }
}
