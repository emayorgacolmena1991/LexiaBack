package com.lexia.api.modules.expedientes.escrituracion.secuencial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.BorradorPromocionService;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.EscrituracionDtos.EstudioTituloRequest;
import com.lexia.api.modules.expedientes.escrituracion.core.MinutaDraftRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleObservation;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleObservationRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleStudy;
import com.lexia.api.modules.expedientes.escrituracion.core.TitleStudyRepository;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFile;
import com.lexia.api.modules.expedientes.escrituracion.core.WritingFileRepository;
import com.lexia.api.modules.expedientes.proceso.ProductTemplateRepository;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** POST estudio-titulo sincroniza por codigo|mensaje: no recrea las observaciones que ya existen. */
class EstudioTituloSincronizacionTest {

  private static final UUID TENANT = UUID.randomUUID();
  private static final UUID CASO = UUID.randomUUID();
  private static final UUID CEDULA = UUID.randomUUID();
  private static final UUID AVALUO = UUID.randomUUID();

  private final List<TitleObservation> filas = new ArrayList<>();
  private final TitleObservationRepository observaciones = mock(TitleObservationRepository.class);
  private TitleStudy study;
  private EscrituracionAbogadoService service;
  private TitleObservation deIa;
  private TitleObservation faltante;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT, UUID.randomUUID()));
    LegalCaseRepository legalCases = mock(LegalCaseRepository.class);
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(mock(LegalCase.class)));
    WritingFile ficha = WritingFile.create(TENANT, CASO);
    WritingFileRepository fichas = mock(WritingFileRepository.class);
    when(fichas.findByCaseIdAndTenantIdAndDeletedAtIsNull(CASO, TENANT)).thenReturn(Optional.of(ficha));
    study = TitleStudy.create(TENANT, ficha.getId());
    TitleStudyRepository studies = mock(TitleStudyRepository.class);
    when(studies.findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(ficha.getId(), TENANT))
        .thenReturn(Optional.of(study));
    when(observaciones.findByTitleStudyIdAndTenantIdOrderByCreatedAtAsc(any(), eq(TENANT)))
        .thenAnswer(llamada -> new ArrayList<>(filas));
    when(observaciones.save(any()))
        .thenAnswer(
            llamada -> {
              filas.add(llamada.getArgument(0));
              return llamada.getArgument(0);
            });
    doAnswer(llamada -> filas.remove(llamada.<TitleObservation>getArgument(0)))
        .when(observaciones)
        .delete(any());
    BorradorPromocionService promocion = mock(BorradorPromocionService.class);
    when(promocion.asegurarExpediente(CASO)).thenReturn(CASO);
    service =
        new EscrituracionAbogadoService(
            legalCases,
            fichas,
            studies,
            observaciones,
            mock(MinutaDraftRepository.class),
            mock(ProductTemplateRepository.class),
            mock(ProductoBiessService.class),
            mock(AuthorizationService.class),
            null,
            promocion);

    // Observaciones de la IA: una ligada a la cédula con el avalúo relacionado y un faltante.
    deIa =
        TitleObservation.create(
            TENANT, study.getId(), "[HIGH] NOMBRE_DIFIERE: El nombre no coincide", CEDULA, List.of(AVALUO), null);
    faltante =
        TitleObservation.create(TENANT, study.getId(), "[HIGH] FALTA_PODER: Falta el poder", null, List.of(), "PODER");
    filas.add(deIa);
    filas.add(faltante);
  }

  @AfterEach
  void limpiar() {
    AuthContext.clear();
  }

  private void estudio(List<String> textos) {
    service.registrarEstudio(CASO, new EstudioTituloRequest("WITH_OBSERVATIONS", "Resumen", textos));
  }

  private static final String TEXTO_IA = "[HIGH] NOMBRE_DIFIERE: El nombre no coincide";
  private static final String TEXTO_FALTANTE = "[HIGH] FALTA_PODER: Falta el poder";

  @Test
  void mismasObservacionesConservanIdsDocumentosYFaltante() {
    estudio(List.of(TEXTO_IA, TEXTO_FALTANTE));

    assertThat(filas).containsExactly(deIa, faltante);
    assertEquals(CEDULA, deIa.getLegalDocumentId());
    assertEquals(List.of(AVALUO), deIa.getDocumentosRelacionados());
    assertEquals("PODER", faltante.getTipoDocumentoFaltante());
    verify(observaciones, never()).delete(any());
  }

  @Test
  void manualExtraSeCreaGeneralYLasDeIaQuedanIntactas() {
    estudio(List.of(TEXTO_IA, TEXTO_FALTANTE, "Revisar linderos con el municipio"));

    assertEquals(3, filas.size());
    assertThat(filas).startsWith(deIa, faltante);
    TitleObservation manual = filas.get(2);
    assertNull(manual.getLegalDocumentId());
    assertNull(manual.getTipoDocumentoFaltante());
    assertThat(manual.getDetail()).contains("Revisar linderos");
  }

  @Test
  void quitandoUnaSoloSeEliminaEsa() {
    estudio(List.of(TEXTO_IA));

    assertThat(filas).containsExactly(deIa);
    verify(observaciones).delete(faltante);
  }

  @Test
  void observacionesNullNoTocaNada() {
    service.registrarEstudio(CASO, new EstudioTituloRequest("APPROVED", "Aprobado", null));

    assertThat(filas).containsExactly(deIa, faltante);
    verify(observaciones, never()).delete(any());
    verify(observaciones, never()).save(any());
    assertEquals("APPROVED", study.getStatus());
  }

  @Test
  void unaResueltaNoReapareceAunqueVengaEnLaLista() {
    TitleObservation resuelta =
        TitleObservation.create(TENANT, study.getId(), "[LOW] FIRMA: Falta firma", AVALUO);
    resuelta.resolve();
    filas.add(resuelta);

    estudio(List.of(TEXTO_IA, TEXTO_FALTANTE, "[LOW] FIRMA: Falta firma"));

    assertThat(filas).containsExactly(deIa, faltante, resuelta);
    assertEquals("RESOLVED", resuelta.getStatus());
  }
}
