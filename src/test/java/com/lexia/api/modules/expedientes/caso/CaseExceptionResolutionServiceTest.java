package com.lexia.api.modules.expedientes.caso;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.ResolveExceptionRequest;
import com.lexia.api.modules.identity.AuthorizationService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CaseExceptionResolutionServiceTest {

  private static final UUID TENANT_ID = UUID.fromString("b1000000-0000-7000-8000-000000000001");
  private static final UUID CASE_ID = UUID.randomUUID();
  private static final UUID EXCEPTION_ID = UUID.randomUUID();

  @Mock private CaseExceptionRepository exceptions;
  @Mock private CaseNoteRepository caseNotes;
  @Mock private CaseActionRepository caseActions;
  @Mock private AuthorizationService authorization;

  @InjectMocks private CaseExceptionResolutionService resolutionService;

  @BeforeEach
  void auth() {
    AuthContext.set(
        new AuthPrincipal(UUID.randomUUID(), UUID.randomUUID(), TENANT_ID, UUID.randomUUID()));
  }

  @AfterEach
  void clear() {
    AuthContext.clear();
  }

  @Test
  void resolveMarksExceptionResolved() {
    CaseException row =
        CaseException.openFromValidation(
            TENANT_ID, CASE_ID, null, "V1 Presencia", "ev", "MEDIA");
    setField(row, "id", EXCEPTION_ID);

    when(exceptions.findById(EXCEPTION_ID)).thenReturn(Optional.of(row));
    when(authorization.hasPermission("excepciones:item:resolver")).thenReturn(true);

    resolutionService.resolve(
        CASE_ID, EXCEPTION_ID, new ResolveExceptionRequest("Se cargó la documentación faltante y se verificó."));

    verify(exceptions).save(row);
    verify(caseNotes).save(org.mockito.ArgumentMatchers.any());
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
