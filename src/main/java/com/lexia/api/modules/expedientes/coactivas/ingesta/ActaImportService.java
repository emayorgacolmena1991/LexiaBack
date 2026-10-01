package com.lexia.api.modules.expedientes.coactivas.ingesta;

import com.fasterxml.jackson.databind.JsonNode;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.auth.AuthPrincipal;
import com.lexia.api.modules.expedientes.coactivas.CoactivaEtapa;
import com.lexia.api.modules.expedientes.coactivas.CoactivaPermisos;
import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.archivos.CoactivaArchivo;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoService;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteService;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpedienteService.NuevoExpediente;
import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.FilaActa;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaDetalle;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaItemDto;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaItemRequest;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaRequest;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ActaResumen;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaIngestaDtos.ConfirmacionResponse;
import com.lexia.api.modules.ia.ocr.AzureDocumentIntelligenceClient;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import com.lexia.api.modules.identity.AuthorizationService;
import java.io.IOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class ActaImportService {

  private static final Logger LOG = LoggerFactory.getLogger(ActaImportService.class);
  private static final String LAYOUT_MODEL = "prebuilt-layout";

  private final CoactivaActaRepository actas;
  private final CoactivaActaItemRepository items;
  private final CoactivaExpedienteRepository expedientes;
  private final CoactivaExpedienteService expedienteService;
  private final CoactivaDelegadoService delegados;
  private final AzureDocumentIntelligenceClient azure;
  private final AuthorizationService authorization;
  private final AppUserRepository users;

  public ActaImportService(
      CoactivaActaRepository actas,
      CoactivaActaItemRepository items,
      CoactivaExpedienteRepository expedientes,
      CoactivaExpedienteService expedienteService,
      CoactivaDelegadoService delegados,
      AzureDocumentIntelligenceClient azure,
      AuthorizationService authorization,
      AppUserRepository users) {
    this.actas = actas;
    this.items = items;
    this.expedientes = expedientes;
    this.expedienteService = expedienteService;
    this.delegados = delegados;
    this.azure = azure;
    this.authorization = authorization;
    this.users = users;
  }

  @Transactional(readOnly = true)
  public List<ActaResumen> listar() {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    return actas.findByTenantIdAndDeletedAtIsNullOrderByCreatedAtDesc(tenantId).stream()
        .map(ActaImportService::toResumen)
        .toList();
  }

  @Transactional(readOnly = true)
  public ActaDetalle detalle(UUID id) {
    authorization.requirePermission(CoactivaPermisos.LEER);
    UUID tenantId = AuthContext.require().tenantId();
    return detalle(tenantId, require(tenantId, id), List.of());
  }

  @Transactional
  public ActaDetalle importar(
      MultipartFile file, String tipo, String oficinaCodigo, LocalDate fechaActa, String titulo) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    if (file == null || file.isEmpty()) {
      throw ApiException.badRequest("Adjunta el acta (Excel, CSV o PDF).");
    }
    String nombre = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
    String fuente;
    List<List<List<String>>> tablas;
    try {
      byte[] bytes = file.getBytes();
      if (nombre.endsWith(".xlsx") || nombre.endsWith(".xls")) {
        fuente = "EXCEL";
        tablas = ActaTableParser.leerExcel(bytes);
      } else if (nombre.endsWith(".csv")) {
        fuente = "EXCEL";
        tablas = ActaTableParser.leerCsv(bytes);
      } else if (nombre.endsWith(".pdf") || nombre.matches(".*\\.(png|jpe?g|tiff?)$")) {
        fuente = "PDF_OCR";
        if (!azure.isConfigured()) {
          throw new ApiException(
              HttpStatus.UNPROCESSABLE_ENTITY,
              "COA_OCR_NO_CONFIGURADO",
              "El OCR no está configurado. Sube el acta en Excel o regístrala manualmente.");
        }
        JsonNode result = azure.analyze(LAYOUT_MODEL, bytes, file.getContentType());
        tablas = ActaTableParser.leerAzureLayout(result);
      } else {
        throw ApiException.badRequest("Formato de acta no soportado. Usa .xlsx, .xls, .csv o .pdf.");
      }
    } catch (IOException | IllegalArgumentException ex) {
      throw ApiException.badRequest("No se pudo leer el archivo del acta: " + ex.getMessage());
    } catch (IllegalStateException ex) {
      LOG.warn("OCR de acta falló: {}", ex.getMessage());
      throw new ApiException(HttpStatus.BAD_GATEWAY, "COA_OCR_ERROR", "El OCR del acta falló: " + ex.getMessage());
    }

    ActaTableParser.Resultado resultado = ActaTableParser.interpretar(tablas);
    CoactivaActa acta = CoactivaActa.create(tenantId, normalizarTipo(tipo), fuente, principal.userId());
    String oficina = delegados.resolverCodigoOficina(tenantId, oficinaCodigo);
    acta.setCabecera(
        CoactivaTexto.truncate(CoactivaTexto.blankToNull(titulo) == null ? file.getOriginalFilename() : titulo, 240),
        oficina,
        fechaActa,
        LocalDate.now(),
        null,
        nombreUsuario(principal),
        null);
    actas.save(acta);

    CoactivaArchivo archivo = expedienteService.guardarArchivo(principal, file, CoactivaArchivo.ACTA);
    archivo.vincularActa(acta.getId());
    acta.setArchivoId(archivo.getId());

    for (FilaActa fila : resultado.filas()) {
      CoactivaActaItem item = CoactivaActaItem.create(tenantId, acta.getId(), fila.fila());
      aplicarDatos(tenantId, item, oficina, fila.oficina(), fila.operacion(), fila.juicio(), fila.deudor(),
          fila.cedula(), fila.etapa(), fila.fojas());
      items.save(item);
    }
    items.flush();
    revalidar(tenantId, acta);
    return detalle(tenantId, acta, resultado.advertencias());
  }

  @Transactional
  public ActaDetalle crearManual(ActaRequest request) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaActa acta = CoactivaActa.create(tenantId, normalizarTipo(request.tipo()), "MANUAL", principal.userId());
    aplicarCabecera(principal, acta, request, nombreUsuario(principal));
    actas.save(acta);
    guardarItems(tenantId, acta, request.items());
    items.flush();
    revalidar(tenantId, acta);
    return detalle(tenantId, acta, List.of());
  }

  @Transactional
  public ActaDetalle actualizar(UUID id, ActaRequest request) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaActa acta = requireBorrador(tenantId, id);
    aplicarCabecera(principal, acta, request, acta.getRecibidoPor());
    if (request.eliminarItemIds() != null) {
      for (UUID itemId : request.eliminarItemIds()) {
        items.findByIdAndTenantId(itemId, tenantId)
            .filter(i -> i.getActaId().equals(id))
            .ifPresent(items::delete);
      }
    }
    guardarItems(tenantId, acta, request.items());
    items.flush();
    revalidar(tenantId, acta);
    return detalle(tenantId, acta, List.of());
  }

  @Transactional
  public ConfirmacionResponse confirmar(UUID id) {
    authorization.requirePermission(CoactivaPermisos.ESCRIBIR);
    AuthPrincipal principal = AuthContext.require();
    UUID tenantId = principal.tenantId();
    CoactivaActa acta = requireBorrador(tenantId, id);
    revalidar(tenantId, acta);
    List<CoactivaActaItem> lista = items.findByTenantIdAndActaIdOrderByFilaAsc(tenantId, id);
    long conError = lista.stream().filter(i -> CoactivaActaItem.ERROR.equals(i.getEstadoMatch())).count();
    if (conError > 0) {
      throw new ApiException(
          HttpStatus.CONFLICT,
          "COA_ACTA_CON_ERRORES",
          conError + " fila(s) con errores. Corrígelas u omítelas antes de confirmar.");
    }
    int creados = 0;
    int duplicados = 0;
    int omitidos = 0;
    for (CoactivaActaItem item : lista) {
      switch (item.getEstadoMatch()) {
        case CoactivaActaItem.PENDIENTE -> {
          CoactivaExpediente expediente =
              expedienteService.crearInterno(
                  principal,
                  new NuevoExpediente(
                      item.getNroJuicio(),
                      item.getNroOperacion(),
                      item.getOficinaCodigo() != null ? item.getOficinaCodigo() : acta.getOficinaCodigo(),
                      null,
                      item.getDeudorNombre(),
                      item.getDeudorCedula(),
                      item.getEtapaReportada(),
                      item.getFojas(),
                      acta.getId(),
                      "ACTA"));
          item.setResultado(CoactivaActaItem.VINCULADO, expediente.getId(), item.getErrores());
          creados++;
        }
        case CoactivaActaItem.DUPLICADO -> duplicados++;
        case CoactivaActaItem.OMITIDO -> omitidos++;
        default -> {}
      }
    }
    acta.confirmar(principal.userId());
    return new ConfirmacionResponse(id, creados, duplicados, omitidos, detalle(tenantId, acta, List.of()));
  }

  // ---------------------------------------------------------------------------
  // Soporte
  // ---------------------------------------------------------------------------

  private void aplicarCabecera(
      AuthPrincipal principal, CoactivaActa acta, ActaRequest request, String recibidoPor) {
    acta.setCabecera(
        CoactivaTexto.blankToNull(request.titulo()),
        request.oficinaCodigo() == null
            ? acta.getOficinaCodigo()
            : delegados.resolverCodigoOficina(principal.tenantId(), request.oficinaCodigo()),
        request.fechaActa(),
        request.fechaRecepcion() == null ? LocalDate.now() : request.fechaRecepcion(),
        CoactivaTexto.blankToNull(request.entregadoPor()),
        recibidoPor,
        CoactivaTexto.blankToNull(request.observaciones()));
  }

  private String nombreUsuario(AuthPrincipal principal) {
    return users.findById(principal.userId())
        .map(AppUser::getDisplayName)
        .map(nombre -> CoactivaTexto.truncate(CoactivaTexto.blankToNull(nombre), 200))
        .orElse(null);
  }

  private void guardarItems(UUID tenantId, CoactivaActa acta, List<ActaItemRequest> requests) {
    if (requests == null) {
      return;
    }
    int siguiente =
        items.findByTenantIdAndActaIdOrderByFilaAsc(tenantId, acta.getId()).stream()
                .mapToInt(CoactivaActaItem::getFila)
                .max()
                .orElse(0)
            + 1;
    for (ActaItemRequest request : requests) {
      CoactivaActaItem item;
      if (request.id() != null) {
        item =
            items.findByIdAndTenantId(request.id(), tenantId)
                .filter(i -> i.getActaId().equals(acta.getId()))
                .orElseThrow(() -> ApiException.notFound("Fila del acta no encontrada."));
      } else {
        item = CoactivaActaItem.create(tenantId, acta.getId(), siguiente++);
      }
      aplicarDatos(
          tenantId,
          item,
          acta.getOficinaCodigo(),
          request.oficinaCodigo(),
          request.nroOperacion(),
          request.nroJuicio(),
          request.deudorNombre(),
          request.deudorCedula(),
          request.etapaReportada(),
          request.fojas());
      item.setResultado(
          Boolean.TRUE.equals(request.omitir()) ? CoactivaActaItem.OMITIDO : CoactivaActaItem.PENDIENTE,
          null,
          null);
      items.save(item);
    }
  }

  private void aplicarDatos(
      UUID tenantId,
      CoactivaActaItem item,
      String oficinaActa,
      String oficina,
      String operacion,
      String juicio,
      String deudor,
      String cedula,
      String etapa,
      Integer fojas) {
    String juicioNorm = CoactivaTexto.normalizarJuicio(juicio);
    String oficinaNorm =
        CoactivaTexto.blankToNull(oficina) == null ? oficinaActa : delegados.resolverCodigoOficina(tenantId, oficina);
    item.setDatos(
        oficinaNorm,
        CoactivaTexto.truncate(CoactivaTexto.blankToNull(operacion), 40),
        juicioNorm,
        CoactivaTexto.anioDeJuicio(juicioNorm),
        CoactivaTexto.truncate(CoactivaTexto.nombrePropio(deudor), 240),
        CoactivaTexto.normalizarIdentificacion(cedula),
        CoactivaTexto.truncate(CoactivaTexto.blankToNull(etapa), 160),
        fojas);
  }

  /** Recalcula estado/errores de las filas no omitidas de un acta en borrador. */
  private void revalidar(UUID tenantId, CoactivaActa acta) {
    List<CoactivaActaItem> lista = items.findByTenantIdAndActaIdOrderByFilaAsc(tenantId, acta.getId());
    List<String> juicios =
        lista.stream().map(CoactivaActaItem::getNroJuicio).filter(Objects::nonNull).distinct().toList();
    Map<String, UUID> existentes = new HashMap<>();
    if (!juicios.isEmpty()) {
      for (CoactivaExpediente e : expedientes.findByTenantIdAndNroJuicioInAndDeletedAtIsNull(tenantId, juicios)) {
        existentes.put(e.getNroJuicio(), e.getId());
      }
    }
    Map<String, Integer> vistos = new HashMap<>();
    for (CoactivaActaItem item : lista) {
      if (CoactivaActaItem.OMITIDO.equals(item.getEstadoMatch())
          || CoactivaActaItem.VINCULADO.equals(item.getEstadoMatch())) {
        continue;
      }
      List<String> errores = new ArrayList<>();
      List<String> avisos = new ArrayList<>();
      if (item.getNroJuicio() == null) {
        errores.add("Falta el número de juicio");
      } else if (CoactivaTexto.detectarJuicio(item.getNroJuicio()).isEmpty()) {
        avisos.add("Formato de juicio no estándar");
      }
      if (item.getDeudorNombre() == null) {
        errores.add("Falta el nombre del deudor");
      }
      if (item.getDeudorCedula() != null && !CoactivaTexto.cedulaValida(item.getDeudorCedula())) {
        avisos.add("Cédula/RUC no válida");
      }
      if (item.getEtapaReportada() != null && CoactivaEtapa.fromTextoLibre(item.getEtapaReportada()).isEmpty()) {
        avisos.add("Etapa reportada no reconocida");
      }
      String estado = CoactivaActaItem.PENDIENTE;
      UUID expedienteId = null;
      if (item.getNroJuicio() != null) {
        Integer previa = vistos.putIfAbsent(item.getNroJuicio(), item.getFila());
        if (previa != null) {
          errores.add("Juicio repetido en el acta (fila " + previa + ")");
        } else if (existentes.containsKey(item.getNroJuicio())) {
          estado = CoactivaActaItem.DUPLICADO;
          expedienteId = existentes.get(item.getNroJuicio());
          avisos.add("Ya existe un expediente con este juicio");
        }
      }
      if (!errores.isEmpty()) {
        estado = CoactivaActaItem.ERROR;
      }
      List<String> mensajes = new ArrayList<>(errores);
      mensajes.addAll(avisos);
      item.setResultado(estado, expedienteId, mensajes.isEmpty() ? null : String.join("; ", mensajes));
    }
    acta.setTotalItems(lista.size());
  }

  private CoactivaActa require(UUID tenantId, UUID id) {
    return actas
        .findByIdAndTenantIdAndDeletedAtIsNull(id, tenantId)
        .orElseThrow(() -> ApiException.notFound("Acta no encontrada."));
  }

  private CoactivaActa requireBorrador(UUID tenantId, UUID id) {
    CoactivaActa acta = require(tenantId, id);
    if (!acta.isBorrador()) {
      throw new ApiException(HttpStatus.CONFLICT, "COA_ACTA_CONFIRMADA", "El acta ya fue confirmada.");
    }
    return acta;
  }

  private static String normalizarTipo(String tipo) {
    return tipo != null && "DEVOLUCION".equalsIgnoreCase(tipo.trim()) ? "DEVOLUCION" : "ENTREGA";
  }

  private ActaDetalle detalle(UUID tenantId, CoactivaActa acta, List<String> advertencias) {
    List<CoactivaActaItem> lista = items.findByTenantIdAndActaIdOrderByFilaAsc(tenantId, acta.getId());
    Map<String, Long> conteos =
        lista.stream()
            .collect(Collectors.groupingBy(CoactivaActaItem::getEstadoMatch, LinkedHashMap::new, Collectors.counting()));
    return new ActaDetalle(
        toResumen(acta),
        acta.getEntregadoPor(),
        acta.getRecibidoPor(),
        acta.getObservaciones(),
        lista.stream().map(ActaImportService::toDto).toList(),
        conteos,
        advertencias);
  }

  private static ActaResumen toResumen(CoactivaActa a) {
    return new ActaResumen(
        a.getId(),
        a.getTipo(),
        a.getTitulo(),
        a.getOficinaCodigo(),
        a.getFechaActa(),
        a.getFechaRecepcion(),
        a.getTotalItems(),
        a.getEstado(),
        a.getFuente(),
        a.getArchivoId(),
        a.getCreatedAt(),
        a.getConfirmadaAt());
  }

  private static ActaItemDto toDto(CoactivaActaItem i) {
    return new ActaItemDto(
        i.getId(),
        i.getFila(),
        i.getOficinaCodigo(),
        i.getNroOperacion(),
        i.getNroJuicio(),
        i.getAnio(),
        i.getDeudorNombre(),
        i.getDeudorCedula(),
        i.getDeudorCedula() != null && CoactivaTexto.cedulaValida(i.getDeudorCedula()),
        i.getEtapaReportada(),
        CoactivaEtapa.fromTextoLibre(i.getEtapaReportada()).map(Enum::name).orElse(null),
        i.getFojas(),
        i.getExpedienteId(),
        i.getEstadoMatch(),
        i.getErrores());
  }
}
