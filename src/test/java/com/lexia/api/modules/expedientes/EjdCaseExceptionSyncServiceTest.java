package com.lexia.api.modules.expedientes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class EjdCaseExceptionSyncServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID CASE_ID = UUID.randomUUID();

  @Mock private CaseExceptionRepository exceptions;
  @Mock private CaseValidationRepository validations;

  @InjectMocks private EjdCaseExceptionSyncService syncService;

  @Test
  void opensExceptionWhenValidationFails() {
    LegalCase legalCase = caseEjd();
    CaseValidation fail =
        CaseValidation.create(
            TENANT_ID, CASE_ID, UUID.randomUUID(), "V1 Presencia", "Regla", "FAIL", "v1");
    setField(fail, "evidence", "Faltan documentos");

    when(validations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(CASE_ID, TENANT_ID))
        .thenReturn(List.of(fail));
    when(exceptions.findByCaseIdAndTenantIdAndExceptionTypeAndTitleAndStatus(
            CASE_ID, TENANT_ID, "VALIDATION_FAIL", "V1 Presencia", "OPEN"))
        .thenReturn(Optional.empty());

    syncService.syncForCase(legalCase);

    verify(exceptions).save(any(CaseException.class));
  }

  @Test
  void autoResolvesWhenValidationPasses() {
    LegalCase legalCase = caseEjd();
    CaseValidation pass =
        CaseValidation.create(
            TENANT_ID, CASE_ID, UUID.randomUUID(), "V1 Presencia", "Regla", "PASS", "v1");
    CaseException open =
        CaseException.openFromValidation(
            TENANT_ID, CASE_ID, null, "V1 Presencia", "ev", "MEDIA");

    when(validations.findByCaseIdAndTenantIdOrderByCreatedAtAsc(CASE_ID, TENANT_ID))
        .thenReturn(List.of(pass));
    when(exceptions.findByCaseIdAndTenantIdAndExceptionTypeAndTitleAndStatus(
            CASE_ID, TENANT_ID, "VALIDATION_FAIL", "V1 Presencia", "OPEN"))
        .thenReturn(Optional.of(open));

    syncService.syncForCase(legalCase);

    ArgumentCaptor<CaseException> saved = ArgumentCaptor.forClass(CaseException.class);
    verify(exceptions).save(saved.capture());
    assertEquals("RESOLVED", saved.getValue().getStatus());
  }

  private static LegalCase caseEjd() {
    LegalCase legalCase =
        LegalCase.create(
            TENANT_ID, "LEX-1", "EJD", "Escrituración", "Asunto", "MEDIA", null, UUID.randomUUID(), null);
    setField(legalCase, "id", CASE_ID);
    return legalCase;
  }

  private static void setField(Object target, String field, Object value) {
    try {
      var f = target.getClass().getDeclaredField(field);
      f.setAccessible(true);
      f.set(target, value);
    } catch (ReflectiveOperationException ex) {
      throw new RuntimeException(ex);
    }
  }
}
