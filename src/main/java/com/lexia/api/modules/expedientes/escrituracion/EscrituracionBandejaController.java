package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.modules.expedientes.caso.CaseService;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.EscrituracionBandejaPage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Bandeja de escrituración. Misma lectura que {@code GET /api/v1/expedientes}. */
@RestController
@RequestMapping("/api/v1/escrituracion")
public class EscrituracionBandejaController {

  private final CaseService caseService;

  public EscrituracionBandejaController(
      @Autowired(required = false) CaseService caseService) {
    this.caseService = caseService;
  }

  @GetMapping
  public EscrituracionBandejaPage listar(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "20") int size,
      @RequestParam(required = false) String search,
      @RequestParam(required = false) String estado) {
    if (caseService == null) {
      return EscrituracionBandejaPage.empty(page, size);
    }
    return caseService.bandeja(search, estado, page, size);
  }
}
