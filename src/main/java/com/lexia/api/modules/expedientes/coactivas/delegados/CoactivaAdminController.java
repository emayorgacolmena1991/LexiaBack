package com.lexia.api.modules.expedientes.coactivas.delegados;

import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.DelegadoItem;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.DelegadoRequest;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.OficinaItem;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoDtos.OficinaRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/coactivas")
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaAdminController {

  private final CoactivaDelegadoService service;

  public CoactivaAdminController(CoactivaDelegadoService service) {
    this.service = service;
  }

  @GetMapping("/delegados")
  public List<DelegadoItem> delegados() {
    return service.delegadosAdmin();
  }

  @PostMapping("/delegados")
  @ResponseStatus(HttpStatus.CREATED)
  public DelegadoItem crear(@Valid @RequestBody DelegadoRequest request) {
    return service.crearDelegado(request);
  }

  @PutMapping("/delegados/{id}")
  public DelegadoItem actualizar(@PathVariable UUID id, @Valid @RequestBody DelegadoRequest request) {
    return service.actualizarDelegado(id, request);
  }

  @DeleteMapping("/delegados/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void eliminar(@PathVariable UUID id) {
    service.eliminarDelegado(id);
  }

  @GetMapping("/oficinas")
  public List<OficinaItem> oficinas() {
    return service.oficinasAdmin();
  }

  @PostMapping("/oficinas")
  public OficinaItem guardarOficina(@Valid @RequestBody OficinaRequest request) {
    return service.guardarOficina(request);
  }
}
