package com.lexia.api.modules.expedientes.minutas;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.auth.AuthContext;
import com.lexia.api.modules.expedientes.caso.LegalCaseRepository;
import com.lexia.api.modules.expedientes.documentos.ExtractedData;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.minutas.PrecioFormaPago.Calculado;
import com.lexia.api.modules.expedientes.minutas.PrecioFormaPago.Forma;
import com.lexia.api.modules.expedientes.minutas.PrecioFormaPago.Pago;
import com.lexia.api.modules.identity.AuthorizationService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Precio y anticipos del expediente en {@code extracted_data}, grupo {@value #GRUPO}. Mismo patrón
 * que {@link DatosBiessStore}: una fila por dato, tenant de la sesión.
 */
@Service
public class PrecioFormaPagoService {

  private static final Logger LOG = LoggerFactory.getLogger(PrecioFormaPagoService.class);
  public static final String GRUPO = "precio";
  private static final String VENTA = "precio.venta";
  private static final String PAGOS = "precio.pagos";
  private static final TypeReference<List<PagoGuardado>> LISTA = new TypeReference<>() {};

  private final AuthorizationService authorization;
  private final LegalCaseRepository legalCases;
  private final ExtractedDataRepository extractedData;
  private final DatosBiessStore datosBiess;
  private final ObjectMapper mapper;

  public PrecioFormaPagoService(
      AuthorizationService authorization,
      LegalCaseRepository legalCases,
      ExtractedDataRepository extractedData,
      DatosBiessStore datosBiess,
      ObjectMapper mapper) {
    this.authorization = authorization;
    this.legalCases = legalCases;
    this.extractedData = extractedData;
    this.datosBiess = datosBiess;
    this.mapper = mapper;
  }

  public record PagoEntrada(String forma, String valor, String fecha, String descripcion) {}

  public record PrecioEntrada(String precioVenta, List<PagoEntrada> pagos) {}

  public record PrecioVista(
      String precioVenta,
      List<PagoEntrada> pagos,
      String valorEntrada,
      String saldo,
      String detalleFormaPago,
      String aviso,
      boolean registrado) {}

  @Transactional(readOnly = true)
  public PrecioVista obtener(UUID caseId) {
    authorization.requirePermission("expedientes:caso:leer");
    UUID tenantId = AuthContext.require().tenantId();
    exigirCaso(caseId, tenantId);
    return vista(tenantId, caseId);
  }

  @Transactional
  public PrecioVista guardar(UUID caseId, PrecioEntrada entrada) {
    authorization.requirePermission("expedientes:caso:escribir");
    UUID tenantId = AuthContext.require().tenantId();
    exigirCaso(caseId, tenantId);
    List<Pago> pagos = new ArrayList<>();
    if (entrada != null && entrada.pagos() != null) {
      for (PagoEntrada pago : entrada.pagos()) {
        pagos.add(leerPago(pago));
      }
    }
    Calculado calculado;
    try {
      calculado =
          PrecioFormaPago.calcular(
              leerMonto(entrada == null ? null : entrada.precioVenta(), "precio de venta"),
              pagos,
              montoBiess(caseId, tenantId));
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest(ex.getMessage());
    }
    extractedData.deleteByCaseIdAndTenantIdAndFieldGroupIn(caseId, tenantId, Set.of(GRUPO));
    save(tenantId, caseId, VENTA, calculado.precio().toPlainString());
    save(tenantId, caseId, PAGOS, escribirPagos(pagos));
    LOG.info("Precio guardado case={} pagos={}", caseId, pagos.size());
    return aVista(calculado, pagos);
  }

  /** Capa para la minuta. Vacío si el expediente no tiene precio registrado. */
  @Transactional(readOnly = true)
  public Map<String, String> capa(UUID tenantId, UUID caseId) {
    Guardado guardado = leer(caseId, tenantId);
    if (guardado == null) {
      return Map.of();
    }
    return PrecioFormaPago.calcular(guardado.precio, guardado.pagos, null).capa();
  }

  private PrecioVista vista(UUID tenantId, UUID caseId) {
    Guardado guardado = leer(caseId, tenantId);
    if (guardado == null) {
      return new PrecioVista("", List.of(), "", "", "", null, false);
    }
    Calculado calculado =
        PrecioFormaPago.calcular(guardado.precio, guardado.pagos, montoBiess(caseId, tenantId));
    return aVista(calculado, guardado.pagos);
  }

  private PrecioVista aVista(Calculado calculado, List<Pago> pagos) {
    List<PagoEntrada> filas = new ArrayList<>();
    for (Pago pago : pagos) {
      filas.add(
          new PagoEntrada(
              pago.forma().name(),
              pago.valor().toPlainString(),
              pago.fecha().toString(),
              pago.descripcion() == null ? "" : pago.descripcion()));
    }
    return new PrecioVista(
        calculado.precio().toPlainString(),
        filas,
        calculado.entrada().toPlainString(),
        calculado.saldo().toPlainString(),
        calculado.detalle(),
        calculado.aviso(),
        true);
  }

  private Guardado leer(UUID caseId, UUID tenantId) {
    Map<String, String> values = new LinkedHashMap<>();
    for (ExtractedData row :
        extractedData.findByCaseIdAndTenantIdAndFieldGroupOrderByFieldLabelAsc(
            caseId, tenantId, GRUPO)) {
      if (row.getFieldLabel() != null) {
        values.put(row.getFieldLabel(), row.getFieldValue() == null ? "" : row.getFieldValue());
      }
    }
    if (!StringUtils.hasText(values.get(VENTA))) {
      return null;
    }
    BigDecimal precio = leerMonto(values.get(VENTA), "precio de venta");
    return new Guardado(precio, leerPagos(values.get(PAGOS)));
  }

  private List<Pago> leerPagos(String json) {
    if (!StringUtils.hasText(json)) {
      return List.of();
    }
    try {
      List<PagoGuardado> filas = mapper.readValue(json, LISTA);
      if (filas == null) {
        return List.of();
      }
      List<Pago> pagos = new ArrayList<>();
      for (PagoGuardado fila : filas) {
        pagos.add(
            new Pago(
                Forma.valueOf(fila.forma),
                new BigDecimal(fila.valor),
                LocalDate.parse(fila.fecha),
                fila.descripcion == null ? "" : fila.descripcion));
      }
      return pagos;
    } catch (JsonProcessingException | RuntimeException ex) {
      LOG.warn("Pagos de precio ilegibles case-group={}: {}", GRUPO, ex.getClass().getSimpleName());
      return List.of();
    }
  }

  private String escribirPagos(List<Pago> pagos) {
    List<PagoGuardado> filas = new ArrayList<>();
    for (Pago pago : pagos) {
      filas.add(
          new PagoGuardado(
              pago.forma().name(),
              pago.valor().toPlainString(),
              pago.fecha().toString(),
              pago.descripcion()));
    }
    try {
      return mapper.writeValueAsString(filas);
    } catch (JsonProcessingException ex) {
      throw ApiException.badRequest("No se pudieron guardar los pagos.");
    }
  }

  private Pago leerPago(PagoEntrada entrada) {
    if (entrada == null) {
      throw ApiException.badRequest("Cada pago necesita forma, valor y fecha.");
    }
    Forma forma;
    try {
      forma = Forma.valueOf(entrada.forma() == null ? "" : entrada.forma().trim().toUpperCase());
    } catch (IllegalArgumentException ex) {
      throw ApiException.badRequest("Forma de pago no válida.");
    }
    LocalDate fecha;
    try {
      fecha = LocalDate.parse(entrada.fecha() == null ? "" : entrada.fecha().trim());
    } catch (DateTimeParseException ex) {
      throw ApiException.badRequest("Fecha de pago no válida.");
    }
    return new Pago(
        forma,
        leerMonto(entrada.valor(), "pago"),
        fecha,
        entrada.descripcion() == null ? "" : entrada.descripcion().trim());
  }

  private static BigDecimal leerMonto(String raw, String campo) {
    return NumeroALetras.parsearMonto(raw)
        .orElseThrow(() -> ApiException.badRequest("Indica un " + campo + " válido."));
  }

  private BigDecimal montoBiess(UUID caseId, UUID tenantId) {
    DatosBiessMinuta biess = datosBiess.cargar(caseId, tenantId);
    if (biess == null) {
      return null;
    }
    return NumeroALetras.parsearMonto(biess.monto()).orElse(null);
  }

  private void exigirCaso(UUID caseId, UUID tenantId) {
    legalCases
        .findByIdAndTenantIdAndDeletedAtIsNull(caseId, tenantId)
        .orElseThrow(() -> ApiException.notFound("Expediente no encontrado."));
  }

  private void save(UUID tenantId, UUID caseId, String label, String valor) {
    extractedData.save(ExtractedData.create(tenantId, caseId, label, valor, GRUPO));
  }

  private record Guardado(BigDecimal precio, List<Pago> pagos) {}

  /** JSON persistido. Campos públicos para Jackson. */
  public static final class PagoGuardado {
    public String forma;
    public String valor;
    public String fecha;
    public String descripcion;

    public PagoGuardado() {}

    PagoGuardado(String forma, String valor, String fecha, String descripcion) {
      this.forma = forma;
      this.valor = valor;
      this.fecha = fecha;
      this.descripcion = descripcion;
    }
  }
}
