package com.lexia.api.modules.expedientes.escrituracion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.LegalCase;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstadoMinutaBorrador;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import com.lexia.api.modules.expedientes.minutas.DatosBiessStore;
import com.lexia.api.modules.expedientes.minutas.MinutaGenerationService;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import com.lexia.api.modules.ia.ocr.DocumentosExtraidosStore;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ExpedienteEstadoServiceTest {

  @Mock private AuthorizationService authorization;
  @Mock private LegalCaseRepository legalCases;
  @Mock private WritingFileRepository writingFiles;
  @Mock private MinutaDraftRepository minutaDrafts;
  @Mock private TitleStudyRepository titleStudies;
  @Mock private TitleObservationRepository titleObservations;
  @Mock private ExtractedDataRepository extractedData;
  @Mock private DatosBiessStore datosBiess;
  @Mock private DocumentosExtraidosStore documentos;
  @Mock private MinutaGenerationService minutas;

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void hidratarLeeBdSinReejecutarLlm() {
    UUID tenant = UUID.randomUUID();
    UUID caseId = UUID.randomUUID();
    UUID writingId = UUID.randomUUID();
    AuthContext.set(
        new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), tenant, UUID.randomUUID()));

    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenant))
        .thenReturn(Optional.of(mock(LegalCase.class)));
    when(documentos.cargar(caseId, tenant)).thenReturn(List.of());
    when(extractedData.findByCaseIdAndTenantIdOrderByFieldLabelAsc(caseId, tenant))
        .thenReturn(
            List.of(ExtractedData.create(tenant, caseId, "comprador.nombres", "ANA", "comprador")));

    WritingFile file = mock(WritingFile.class);
    when(file.getId()).thenReturn(writingId);
    when(writingFiles.findByCaseIdAndTenantIdAndDeletedAtIsNull(caseId, tenant))
        .thenReturn(Optional.of(file));
    when(titleStudies.findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(writingId, tenant))
        .thenReturn(Optional.empty());

    MinutaDraft draft = MinutaDraft.create(tenant, writingId, "VH", "MINUTA_COMPRAVENTA");
    when(minutaDrafts.findFirstByWritingFileIdAndTenantIdOrderByCreatedAtDesc(writingId, tenant))
        .thenReturn(Optional.of(draft));
    MinutaViviendaData minuta = new MinutaViviendaData();
    minuta.setMontoPrestamo("120000");
    when(minutas.leerDatos(draft)).thenReturn(minuta);
    when(datosBiess.cargar(caseId, tenant))
        .thenReturn(new DatosBiessMinuta("120000", "5.99", "240", "ANDRE"));

    ExpedienteEstadoService service =
        new ExpedienteEstadoService(
            authorization,
            legalCases,
            writingFiles,
            minutaDrafts,
            titleStudies,
            titleObservations,
            extractedData,
            datosBiess,
            documentos,
            minutas);

    EstadoMinutaBorrador estado = service.hidratar(caseId);

    assertEquals(caseId, estado.caseId());
    assertEquals("120000", estado.datosBiess().monto());
    assertEquals("5.99", estado.datosBiess().tasa());
    assertEquals("ANA", estado.datosExtraidos().comprador().nombres());
    assertTrue(estado.hasDraft());
    assertEquals(draft.getId(), estado.draftId());
    assertEquals("120000", estado.datosMinuta().getMontoPrestamo());
  }
}
