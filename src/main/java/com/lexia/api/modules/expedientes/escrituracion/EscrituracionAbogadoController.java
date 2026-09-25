package com.lexia.api.modules.expedientes.escrituracion;

import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ConfigurarProductoRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.CrearMinutaRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstudioTituloRequest;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.EstudioTituloResponse;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.MinutaItem;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ProductoDetalle;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.ProductoItem;
import com.lexia.api.modules.expedientes.escrituracion.EscrituracionDtos.WritingSnapshot;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;

@RestController
@RequestMapping("/api/v1")
public class EscrituracionAbogadoController {

  private final ProductoBiessService productos;
  private final EscrituracionAbogadoService escritura;

  public EscrituracionAbogadoController(
      ProductoBiessService productos,
      @org.springframework.beans.factory.annotation.Autowired(required = false)
          EscrituracionAbogadoService escritura) {
    this.productos = productos;
    this.escritura = escritura;
  }

  @GetMapping("/productos-biess")
  public List<ProductoItem> listarProductos() {
    return productos.listarProductos();
  }

  @GetMapping("/productos-biess/{code}")
  public ProductoDetalle detalleProducto(
      @PathVariable String code, @RequestParam(required = false) String canton) {
    return productos.detalle(code, canton);
  }

  @GetMapping("/expedientes/{id}/escrituracion")
  public ResponseEntity<WritingSnapshot> snapshot(@PathVariable UUID id) {
    if (escritura == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    return ResponseEntity.ok(escritura.snapshot(id));
  }

  @PutMapping("/expedientes/{id}/escrituracion/producto")
  public ResponseEntity<WritingSnapshot> configurarProducto(
      @PathVariable UUID id, @Valid @RequestBody ConfigurarProductoRequest request) {
    if (escritura == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    return ResponseEntity.ok(escritura.configurarProducto(id, request));
  }

  @PostMapping("/expedientes/{id}/escrituracion/estudio-titulo")
  public ResponseEntity<EstudioTituloResponse> estudioTitulo(
      @PathVariable UUID id, @Valid @RequestBody EstudioTituloRequest request) {
    if (escritura == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    return ResponseEntity.ok(escritura.registrarEstudio(id, request));
  }

  @PostMapping("/expedientes/{id}/escrituracion/minutas")
  @ResponseStatus(HttpStatus.CREATED)
  public ResponseEntity<MinutaItem> crearMinuta(
      @PathVariable UUID id, @Valid @RequestBody(required = false) CrearMinutaRequest request) {
    if (escritura == null) {
      return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(
            escritura.crearMinuta(
                id, request == null ? new CrearMinutaRequest(null) : request));
  }
}
