package com.lexia.api.modules.expedientes.caso;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.CaseDetailItem;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.PromoverBorradorRequest;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorrador;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.expedientes.minutas.DatosBiessMinuta;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class BorradorPromocionServiceTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID USER = UUID.fromString("c1000000-0000-7000-8000-000000000001");
  private static final UUID DRAFT = UUID.fromString("3c68d00a-019a-456f-abfa-d1b117f45181");
  private static final UUID OFFICIAL = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");

  @Mock private ExpedienteBorradorStore borradores;
  @Mock private LegalCaseRepository legalCases;
  @Mock private ObjectProvider<CaseService> caseServices;
  @Mock private CaseService caseService;

  private BorradorPromocionService service;

  @BeforeEach
  void setUp() {
    AuthContext.set(new AuthPrincipal(USER, UUID.randomUUID(), TENANT, UUID.randomUUID()));
    service = new BorradorPromocionService(borradores, legalCases, caseServices);
  }

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void promoverBorradorCreaExpedienteYReutilizaElId() {
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(DRAFT, TENANT)).thenReturn(Optional.empty());
    when(borradores.find(DRAFT, TENANT))
        .thenReturn(
            Optional.of(
                ExpedienteBorrador.loaded(
                    DRAFT, TENANT, null, "VIV_HIPOTECADA_BIESS", "DAULE", "DIGITAL_SEPARADO")));
    when(caseServices.getIfAvailable()).thenReturn(caseService);
    when(caseService.createCase(any())).thenReturn(detail(OFFICIAL));
    when(caseService.getCase(OFFICIAL)).thenReturn(detail(OFFICIAL));

    CaseDetailItem created =
        service.promover(new PromoverBorradorRequest(DRAFT, "Casa", "Ana", null, null, null, null, null));

    assertEquals(OFFICIAL, created.id());
    verify(borradores).marcarPromovido(DRAFT, TENANT, OFFICIAL);

    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(OFFICIAL, TENANT))
        .thenReturn(Optional.of(org.mockito.Mockito.mock(LegalCase.class)));
    assertEquals(OFFICIAL, service.asegurarExpediente(OFFICIAL));
    verify(caseService, org.mockito.Mockito.times(1)).createCase(any());
  }

  @Test
  void segundaPromocionNoDuplicaExpediente() {
    ExpedienteBorrador row =
        ExpedienteBorrador.loaded(DRAFT, TENANT, null, "VIV_HIPOTECADA_BIESS", "DAULE", "DIGITAL_SEPARADO");
    row.setPromotedCaseId(OFFICIAL);
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(DRAFT, TENANT)).thenReturn(Optional.empty());
    when(legalCases.findByIdAndTenantIdAndDeletedAtIsNull(OFFICIAL, TENANT))
        .thenReturn(Optional.of(org.mockito.Mockito.mock(LegalCase.class)));
    when(borradores.find(DRAFT, TENANT)).thenReturn(Optional.of(row));

    assertEquals(OFFICIAL, service.asegurarExpediente(DRAFT));
    verify(caseService, never()).createCase(any());
  }

  private static CaseDetailItem detail(UUID id) {
    return new CaseDetailItem(
        id,
        "LEX-1",
        "Escrituración",
        "Casa",
        "EJD",
        "En trámite",
        "DRAFT",
        "—",
        "—",
        "Media",
        "MEDIA",
        "—",
        "Ana",
        null,
        null,
        "hoy",
        "hoy",
        null,
        DatosBiessMinuta.empty(),
        false,
        1,
        4);
  }
}
