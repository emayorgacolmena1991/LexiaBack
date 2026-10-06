package com.lexia.api.modules.expedientes.coactivas.actuacion;

import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegado;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoRepository;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficina;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficinaRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipante;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipanteRepository;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaAnalisis;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaAnalisisRepository;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnostico;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaDiagnosticoParser;
import com.lexia.api.modules.expedientes.minutas.NumeroALetras;
import com.lexia.api.modules.identity.AppUser;
import com.lexia.api.modules.identity.AppUserRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Variables {@code {{...}}} de las plantillas .docx de Coactivas. Prioridad: datos estructurados
 * del expediente (participantes, delegado, oficina) y configuración; luego {@code datos_extraidos}
 * del último análisis IA ANALIZADO. Montos en letras, fecha y hora se calculan aquí. Un dato sin
 * fuente queda ausente del mapa: nunca se rellena con texto de relleno.
 */
@Component
@ConditionalOnProperty(name = "lexia.auth.enabled", havingValue = "true")
public class CoactivaPlantillaDataMapper {

  private static final Logger LOG = LoggerFactory.getLogger(CoactivaPlantillaDataMapper.class);

  static final String DEUDOR = "DEUDOR";
  static final String GARANTE = "GARANTE";

  private static final Locale ES = Locale.forLanguageTag("es-EC");
  private static final DateTimeFormatter FECHA_LARGA = DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ES);
  private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH'h'mm", ES);
  private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
  private static final Pattern FECHA_PARTES =
      Pattern.compile("(?iu)(\\d{1,2})\\s+de\\s+([\\p{L}]+)\\s+(?:de|del)\\s+(\\d{4})");
  private static final BigDecimal DIVISOR_IVA = new BigDecimal("1.15");
  private static final Pattern TRATAMIENTO =
      Pattern.compile(
          "(?iu)^(?:(?:el|la)(?:\\s*/\\s*la)?\\s+)?"
              + "(?:señor(?:\\(a\\)|a|ita)?|senor(?:\\(a\\)|a)?|sr(?:a|ta)?\\.?|abg\\.?|abogad[oa]|"
              + "mgs\\.?|mgtr\\.?|magíster|magister|ing\\.?|dr(?:a)?\\.?|lcd(?:o|a)\\.?|lic\\.?)\\s+");

  private final CoactivaParticipanteRepository participantes;
  private final CoactivaAnalisisRepository analisis;
  private final CoactivaDiagnosticoParser parser;
  private final CoactivaOficinaRepository oficinas;
  private final CoactivaDelegadoRepository delegados;
  private final AppUserRepository usuarios;
  private final Config config;
  private final ZoneId zona;

  public CoactivaPlantillaDataMapper(
      CoactivaParticipanteRepository participantes,
      CoactivaAnalisisRepository analisis,
      CoactivaDiagnosticoParser parser,
      CoactivaOficinaRepository oficinas,
      CoactivaDelegadoRepository delegados,
      AppUserRepository usuarios,
      @Value("${lexia.coactivas.plantillas.gerente-general:}") String gerenteGeneral,
      @Value("${lexia.coactivas.plantillas.correo-institucional:}") String correoInstitucional,
      @Value("${lexia.coactivas.plantillas.correo-estudio-juridico:}") String correoEstudioJuridico,
      @Value("${lexia.coactivas.plantillas.zonales:}") String zonales,
      @Value("${lexia.coactivas.plantillas.zona-horaria:America/Guayaquil}") String zonaHoraria) {
    this.participantes = participantes;
    this.analisis = analisis;
    this.parser = parser;
    this.oficinas = oficinas;
    this.delegados = delegados;
    this.usuarios = usuarios;
    this.config =
        new Config(gerenteGeneral, correoInstitucional, correoEstudioJuridico, parsearZonales(zonales));
    this.zona = ZoneId.of(zonaHoraria);
  }

  public PlantillaDatos mapear(CoactivaExpediente expediente) {
    UUID tenantId = expediente.getTenantId();
    UUID expedienteId = expediente.getId();
    Optional<CoactivaDiagnostico> diagnostico =
        analisis
            .findFirstByTenantIdAndExpedienteIdAndEstadoOrderByCreatedAtDesc(
                tenantId, expedienteId, CoactivaAnalisis.ANALIZADO)
            .map(a -> parser.parse(a.getResultado()).diagnostico());
    CoactivaOficina oficina =
        expediente.getOficinaCodigo() == null
            ? null
            : oficinas.findByTenantIdAndCodigo(tenantId, expediente.getOficinaCodigo()).orElse(null);
    CoactivaDelegado delegado =
        expediente.getDelegadoId() == null
            ? null
            : delegados.findByIdAndTenantIdAndDeletedAtIsNull(expediente.getDelegadoId(), tenantId).orElse(null);
    String secretario =
        expediente.getSaeUserId() == null
            ? null
            : usuarios.findById(expediente.getSaeUserId()).map(AppUser::getDisplayName).orElse(null);
    Fuentes fuentes =
        new Fuentes(
            expediente,
            participantes.findByTenantIdAndExpedienteIdAndDeletedAtIsNullOrderByOrdenAsc(tenantId, expedienteId),
            diagnostico.map(CoactivaDiagnostico::datosExtraidos).orElse(null),
            oficina,
            delegado,
            secretario,
            config);
    PlantillaDatos datos = construir(fuentes, ZonedDateTime.now(zona));
    if (!datos.discrepancias().isEmpty()) {
      LOG.warn(
          "Plantilla coactiva expediente={}: OCR difiere del dato estructurado en {}",
          expedienteId,
          datos.discrepancias());
    }
    return datos;
  }

  static PlantillaDatos construir(Fuentes f, ZonedDateTime ahora) {
    Map<String, Object> ocr = f.datosExtraidos() == null ? Map.of() : f.datosExtraidos();
    Resolver r = new Resolver();
    CoactivaExpediente exp = f.expediente();
    CoactivaParticipante deudor = primero(f.participantes(), DEUDOR);
    CoactivaParticipante garante = primero(f.participantes(), GARANTE);
    CoactivaDelegado delegado = f.delegado();
    Config cfg = f.config();

    r.juicio("numero_juicio_coactivo", exp.getNroJuicio(), texto(ocr, "juicio", "numero_juicio_coactivo"));
    r.texto("numero_operacion", exp.getNroOperacion(), texto(ocr, "operacion", "numero_operacion"));

    r.nombre(
        "nombre_deudor_principal",
        deudor == null ? null : deudor.getNombreCompleto(),
        texto(ocr, "deudor", "nombre_deudor_principal"));
    r.cedula(
        "cedula_deudor_principal",
        deudor == null ? null : deudor.getIdentificacion(),
        texto(ocr, "cedula_deudor_principal", "cedula_deudor"));
    r.correo(
        "correo_notificacion_deudor",
        deudor == null ? null : deudor.getEmails(),
        texto(ocr, "correo_notificacion_deudor"));
    r.nombre(
        "nombre_garante_solidario",
        garante == null ? null : garante.getNombreCompleto(),
        texto(ocr, "nombre_garante_solidario"));
    r.cedula(
        "cedula_garante_solidario",
        garante == null ? null : garante.getIdentificacion(),
        texto(ocr, "cedula_garante_solidario"));
    r.nombre("nombre_depositario_judicial", null, texto(ocr, "nombre_depositario_judicial"));
    r.cedula("cedula_depositario_judicial", null, texto(ocr, "cedula_depositario_judicial"));

    r.nombre(
        "nombre_funcionario_coactiva",
        delegado == null ? null : delegado.getNombre(),
        texto(ocr, "nombre_funcionario_coactiva"));
    r.correo(
        "correo_funcionario_coactiva",
        delegado == null ? null : delegado.getEmail(),
        texto(ocr, "correo_funcionario_coactiva"));
    r.texto(
        "numero_resolucion_delegacion",
        delegado == null ? null : delegado.getResolucionNumero(),
        texto(ocr, "numero_resolucion_delegacion"));
    r.sinComparar(
        "fecha_resolucion_delegacion",
        delegado == null ? null : fechaLarga(delegado.getResolucionFecha()),
        texto(ocr, "fecha_resolucion_delegacion"));
    r.nombre("nombre_gerente_general", cfg.gerenteGeneral(), texto(ocr, "nombre_gerente_general"));
    r.nombre("nombre_abogado_secretario", f.secretarioNombre(), texto(ocr, "nombre_abogado_secretario"));

    r.correo(
        "correo_estudio_juridico_externo",
        cfg.correoEstudioJuridico(),
        texto(ocr, "correo_estudio_juridico_externo"));
    r.correo(
        "correo_coactiva_institucional",
        cfg.correoInstitucional(),
        texto(ocr, "correo_coactiva_institucional"));

    r.sinComparar("ciudad_actuacion", f.oficina() == null ? null : f.oficina().getNombre(), null);
    r.sinComparar(
        "numero_zonal",
        exp.getOficinaCodigo() == null ? null : cfg.zonales().get(exp.getOficinaCodigo().toUpperCase(Locale.ROOT)),
        null);
    r.sinComparar("fecha_actuacion", fechaLarga(ahora.toLocalDate()), null);
    r.sinComparar("hora_actuacion", HORA.format(ahora), null);

    r.monto("monto_deuda_total", monto(ocr.get("monto_deuda_total")));
    r.monto("monto_honorarios", monto(ocr.get("monto_honorarios")));
    r.sinComparar("cuenta_honorarios_abogado", null, texto(ocr, "cuenta_honorarios_abogado"));

    List<Cuenta> cuentas = cuentas(ocr.get("cuentas_embargadas"), deudor, garante);
    Cuenta[] slots = asignarCuentas(cuentas);
    for (int i = 0; i < slots.length; i++) {
      Cuenta c = slots[i];
      r.sinComparar("numero_cuenta_" + (i + 1), c == null ? null : c.numero(), null);
      r.monto("monto_embargo_" + (i + 1), c == null ? null : c.monto());
    }
    String banco = texto(ocr, "banco_embargado");
    if (banco == null) {
      banco = cuentas.stream().map(Cuenta::banco).filter(b -> b != null).findFirst().orElse(null);
    }
    r.sinComparar("banco_embargado", banco, null);

    completar(r, f, ocr, ahora);
    return new PlantillaDatos(r.valores, r.discrepancias, f.datosExtraidos() != null);
  }

  /**
   * Cubre el resto de etiquetas de las plantillas de {@code templates/coactivas}: alias del dato
   * canónico, segundo deudor/garante, fojas y lo que el OCR traiga con el mismo nombre de variable.
   */
  private static void completar(Resolver r, Fuentes f, Map<String, Object> ocr, ZonedDateTime ahora) {
    List<CoactivaParticipante> deudores = porRol(f.participantes(), DEUDOR);
    List<CoactivaParticipante> garantes = porRol(f.participantes(), GARANTE);
    persona(r, ocr, "nombre_deudor_principal_2", "cedula_deudor_principal_2", deudores.size() > 1 ? deudores.get(1) : null);
    persona(
        r, ocr, "nombre_garante_solidario_2", "cedula_garante_solidario_2", garantes.size() > 1 ? garantes.get(1) : null);

    List<String> correos = new ArrayList<>();
    deudores.forEach(p -> correos.addAll(correosDe(p.getEmails())));
    garantes.forEach(p -> correos.addAll(correosDe(p.getEmails())));
    List<String> unicos = correos.stream().distinct().toList();
    if (unicos.size() > 1) {
      r.ponerSiAusente("correo_notificacion_2", unicos.get(1));
    }
    if (unicos.size() > 2) {
      r.ponerSiAusente("correo_notificacion_3", unicos.get(2));
    }

    CoactivaExpediente exp = f.expediente();
    r.ponerSiAusente("zona", exp.getOficinaCodigo() == null ? null : exp.getOficinaCodigo().toUpperCase(Locale.ROOT));
    r.ponerSiAusente("nombre_institucion_bancaria", "Banecuador B.P.");
    r.ponerSiAusente("dia_entrega", String.valueOf(ahora.getDayOfMonth()));
    r.ponerSiAusente("mes_entrega", ahora.getMonth().getDisplayName(TextStyle.FULL, ES));
    r.ponerSiAusente("anio_entrega", String.valueOf(ahora.getYear()));
    Integer fojas = exp.getFojas();
    if (fojas != null && fojas > 0) {
      r.ponerSiAusente("foja_inicio_numero", "1");
      r.ponerSiAusente("foja_inicio_texto", "uno");
      r.ponerSiAusente("foja_fin_numero", String.valueOf(fojas));
      r.ponerSiAusente("foja_fin_texto", NumeroALetras.entero(fojas.longValue()));
    }

    volcarOcr(r, ocr);

    r.copiar("numero_juicio_coactivo", "numero_juicio", "numero_proceso", "numero_proceso_coactivo");
    r.copiar("nombre_deudor_principal", "nombre_deudor_principal_1", "nombre_deudor_1", "nombre_cliente");
    r.copiar("cedula_deudor_principal", "cedula_deudor_principal_1", "cedula_deudor_1");
    r.copiar("nombre_garante_solidario", "nombre_garante_solidario_1", "nombre_garante_1");
    r.copiar("cedula_garante_solidario", "cedula_garante_solidario_1", "cedula_garante_1");
    r.copiar("nombre_deudor_principal_2", "nombre_deudor_2");
    r.copiar("cedula_deudor_principal_2", "cedula_deudor_2");
    r.copiar("nombre_garante_solidario_2", "nombre_garante_2");
    r.copiar("cedula_garante_solidario_2", "cedula_garante_2");
    r.copiar("nombre_funcionario_coactiva", "funcionario_coactiva", "nombre_delegado");
    r.copiar("nombre_abogado_secretario", "abogado_secretario", "nombre_secretario");
    r.copiar("ciudad_actuacion", "ciudad", "ciudad_zonal");
    r.copiar("numero_zonal", "numero_zona");
    r.copiar("fecha_actuacion", "fecha_providencia", "fecha_acta");
    r.copiar("hora_actuacion", "hora");
    r.copiar("monto_deuda_total", "monto_liquidacion");
    r.copiar("monto_deuda_total_letras", "monto_liquidacion_letras");
    r.copiar("correo_notificacion_deudor", "correo_notificacion_1");
    r.copiar("correo_coactiva_institucional", "correo_notificacion_coactiva");
    r.copiar("correo_estudio_juridico_externo", "correo_abogado_externo", "correo_abogado_secretario");
    r.copiar("correo_funcionario_coactiva", "correo_delegado");
    r.copiar("banco_embargado", "banco_retencion", "institucion_financiera");
    r.copiar("nombre_depositario_judicial", "nombre_depositario");
    r.copiar("cedula_depositario_judicial", "cedula_depositario");
    r.copiar("cuenta_honorarios_abogado", "numero_cuenta_honorarios");
    r.copiar("monto_honorarios", "valor_honorarios_total_num");
    r.copiar("monto_honorarios_letras", "valor_honorarios_total_texto");
    r.copiarSiAusente("valor_embargo_num_letras", "valor_embargo_texto");
    r.copiarSiAusente("numero_cuenta_1", "numero_cuenta_retencion");
    r.copiarSiAusente("numero_cuenta_1", "numero_cuenta_retencion_1");
    r.copiarSiAusente("nombre_deudor_principal", "nombre_receptor");
    r.copiarSiAusente("cedula_deudor_principal", "cedula_receptor");
    r.copiarSiAusente("ciudad_actuacion", "agencia");
    r.copiarSiAusente("ciudad_actuacion", "unidad_ejecucion_coactiva");
    r.copiarSiAusente("fecha_liquidacion", "fecha_liquidacion_actualizada");
    r.copiarSiAusente("fecha_acta", "fecha_auto_designacion");
    partirFecha(r, "fecha_embargo", "dia_embargo", "mes_embargo", "anio_embargo");
    desglosarHonorarios(r);
  }

  private static void persona(Resolver r, Map<String, Object> ocr, String nombreKey, String cedulaKey, CoactivaParticipante p) {
    r.nombre(nombreKey, p == null ? null : p.getNombreCompleto(), texto(ocr, nombreKey));
    r.cedula(cedulaKey, p == null ? null : p.getIdentificacion(), texto(ocr, cedulaKey));
  }

  private static void volcarOcr(Resolver r, Map<String, Object> ocr) {
    for (Map.Entry<String, Object> e : ocr.entrySet()) {
      Object raw = e.getValue();
      if (raw == null || raw instanceof Map || raw instanceof Collection) {
        continue;
      }
      String key = e.getKey();
      if (esMonto(key)) {
        r.montoSiAusente(key, monto(raw));
      } else {
        r.ponerSiAusente(key, raw.toString());
      }
    }
  }

  static boolean esMonto(String key) {
    if (key.endsWith("_letras") || key.endsWith("_texto")) {
      return false;
    }
    return key.startsWith("monto_") || key.startsWith("valor_") || key.equals("valor") || key.equals("iva") || key.equals("total") || key.equals("abono");
  }

  private static void partirFecha(Resolver r, String fechaKey, String dia, String mes, String anio) {
    Object raw = r.valor(fechaKey);
    if (raw == null) {
      return;
    }
    Matcher m = FECHA_PARTES.matcher(raw.toString());
    if (!m.find()) {
      return;
    }
    r.ponerSiAusente(dia, m.group(1));
    r.ponerSiAusente(mes, m.group(2).toLowerCase(ES));
    r.ponerSiAusente(anio, m.group(3));
  }

  private static void desglosarHonorarios(Resolver r) {
    BigDecimal total = monto(r.valor("monto_honorarios"));
    if (total == null) {
      return;
    }
    BigDecimal subtotal = total.divide(DIVISOR_IVA, 2, RoundingMode.HALF_UP);
    BigDecimal iva = total.subtract(subtotal);
    r.ponerSiAusente("valor_honorarios_subtotal", formatoMonto(subtotal));
    r.ponerSiAusente("valor_iva_honorarios", formatoMonto(iva));
    r.ponerSiAusente("valor", formatoMonto(subtotal));
    r.ponerSiAusente("iva", formatoMonto(iva));
    r.ponerSiAusente("total", formatoMonto(total));
  }

  private static List<CoactivaParticipante> porRol(List<CoactivaParticipante> items, String rol) {
    if (items == null) {
      return List.of();
    }
    return items.stream().filter(p -> rol.equals(p.getRol())).toList();
  }

  static List<String> correosDe(String raw) {
    if (raw == null) {
      return List.of();
    }
    Matcher m = EMAIL.matcher(raw);
    List<String> out = new ArrayList<>();
    while (m.find()) {
      out.add(m.group().toLowerCase(Locale.ROOT));
    }
    return out;
  }

  /**
   * La providencia de embargo fija las cuentas 1 y 2 al deudor principal y la 3 al garante
   * solidario: una cuenta sin titular identificable no se asigna.
   */
  static Cuenta[] asignarCuentas(List<Cuenta> cuentas) {
    List<Cuenta> deudor = cuentas.stream().filter(c -> DEUDOR.equals(c.titular())).toList();
    List<Cuenta> garante = cuentas.stream().filter(c -> GARANTE.equals(c.titular())).toList();
    return new Cuenta[] {
      deudor.size() > 0 ? deudor.get(0) : null,
      deudor.size() > 1 ? deudor.get(1) : null,
      garante.isEmpty() ? null : garante.get(0)
    };
  }

  private static List<Cuenta> cuentas(
      Object raw, CoactivaParticipante deudor, CoactivaParticipante garante) {
    List<Cuenta> out = new ArrayList<>();
    if (!(raw instanceof Collection<?> items)) {
      return out;
    }
    for (Object item : items) {
      if (!(item instanceof Map<?, ?> m)) {
        continue;
      }
      @SuppressWarnings("unchecked")
      Map<String, Object> cuenta = (Map<String, Object>) m;
      String numero = texto(cuenta, "numero_cuenta", "numero", "cuenta");
      if (numero == null) {
        continue;
      }
      out.add(
          new Cuenta(
              numero,
              titular(texto(cuenta, "titular"), deudor, garante),
              texto(cuenta, "banco", "institucion"),
              monto(firstNonNull(cuenta.get("monto_retenido"), cuenta.get("monto")))));
    }
    return out;
  }

  private static String titular(
      String raw, CoactivaParticipante deudor, CoactivaParticipante garante) {
    if (raw == null) {
      return null;
    }
    String clave = CoactivaTexto.claveNombre(raw);
    if (clave.contains("GARANTE")) {
      return GARANTE;
    }
    if (clave.contains("DEUDOR")) {
      return DEUDOR;
    }
    if (garante != null && clave.equals(CoactivaTexto.claveNombre(garante.getNombreCompleto()))) {
      return GARANTE;
    }
    if (deudor != null && clave.equals(CoactivaTexto.claveNombre(deudor.getNombreCompleto()))) {
      return DEUDOR;
    }
    return null;
  }

  private static CoactivaParticipante primero(List<CoactivaParticipante> items, String rol) {
    if (items == null) {
      return null;
    }
    return items.stream().filter(p -> rol.equals(p.getRol())).findFirst().orElse(null);
  }

  static String texto(Map<String, Object> datos, String... keys) {
    for (String key : keys) {
      Object v = datos.get(key);
      if (v == null || v instanceof Map || v instanceof Collection) {
        continue;
      }
      String s = CoactivaTexto.blankToNull(v.toString());
      if (s != null && !"null".equalsIgnoreCase(s)) {
        return s;
      }
    }
    return null;
  }

  static BigDecimal monto(Object raw) {
    if (raw == null) {
      return null;
    }
    BigDecimal valor;
    if (raw instanceof Number n) {
      valor = new BigDecimal(n.toString());
    } else {
      valor = NumeroALetras.parsearMonto(raw.toString()).orElse(null);
    }
    return valor == null || valor.signum() <= 0 ? null : valor.setScale(2, RoundingMode.HALF_UP);
  }

  static String formatoMonto(BigDecimal valor) {
    DecimalFormat df = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.US));
    return df.format(valor);
  }

  static String letras(BigDecimal valor) {
    return NumeroALetras.monto(valor).toUpperCase(ES);
  }

  static String limpiarNombre(String raw) {
    String s = CoactivaTexto.blankToNull(raw);
    if (s == null) {
      return null;
    }
    s = s.replaceAll("\\s+", " ");
    String previo;
    do {
      previo = s;
      s = TRATAMIENTO.matcher(s).replaceFirst("").trim();
    } while (!s.equals(previo) && !s.isEmpty());
    return s.isEmpty() ? null : s;
  }

  static String primerCorreo(String raw) {
    if (raw == null) {
      return null;
    }
    Matcher m = EMAIL.matcher(raw);
    return m.find() ? m.group().toLowerCase(Locale.ROOT) : null;
  }

  private static String fechaLarga(LocalDate fecha) {
    return fecha == null ? null : FECHA_LARGA.format(fecha);
  }

  private static Object firstNonNull(Object a, Object b) {
    return a != null ? a : b;
  }

  static Map<String, String> parsearZonales(String raw) {
    Map<String, String> out = new LinkedHashMap<>();
    if (raw == null || raw.isBlank()) {
      return out;
    }
    for (String par : raw.split("[,;]")) {
      String[] kv = par.split("[=:]", 2);
      if (kv.length == 2 && !kv[0].isBlank() && !kv[1].isBlank()) {
        out.put(kv[0].trim().toUpperCase(Locale.ROOT), kv[1].trim());
      }
    }
    return out;
  }

  /** Resolución estructurado-primero; el OCR solo completa y sus diferencias se registran. */
  private static final class Resolver {
    private final Map<String, Object> valores = new LinkedHashMap<>();
    private final List<String> discrepancias = new ArrayList<>();

    void texto(String variable, String estructurado, String ocr) {
      preferir(variable, CoactivaTexto.blankToNull(estructurado), CoactivaTexto.blankToNull(ocr), CoactivaTexto::claveNombre);
    }

    void juicio(String variable, String estructurado, String ocr) {
      preferir(
          variable,
          CoactivaTexto.blankToNull(estructurado),
          CoactivaTexto.normalizarJuicio(ocr),
          CoactivaTexto::normalizarJuicio);
    }

    void nombre(String variable, String estructurado, String ocr) {
      preferir(variable, limpiarNombre(estructurado), limpiarNombre(ocr), CoactivaTexto::claveNombre);
    }

    void cedula(String variable, String estructurado, String ocr) {
      preferir(
          variable,
          CoactivaTexto.normalizarIdentificacion(estructurado),
          CoactivaTexto.normalizarIdentificacion(ocr),
          CoactivaTexto::soloDigitos);
    }

    void correo(String variable, String estructurado, String ocr) {
      preferir(variable, primerCorreo(estructurado), primerCorreo(ocr), s -> s.toLowerCase(Locale.ROOT));
    }

    void sinComparar(String variable, String estructurado, String ocr) {
      String e = CoactivaTexto.blankToNull(estructurado);
      String valor = e != null ? e : CoactivaTexto.blankToNull(ocr);
      if (valor != null) {
        valores.put(variable, valor);
      }
    }

    void monto(String variable, BigDecimal valor) {
      if (valor == null) {
        return;
      }
      valores.put(variable, formatoMonto(valor));
      valores.put(variable + "_letras", letras(valor));
    }

    void montoSiAusente(String variable, BigDecimal valor) {
      if (valores.containsKey(variable)) {
        return;
      }
      monto(variable, valor);
    }

    void ponerSiAusente(String variable, String valor) {
      if (variable == null || valores.containsKey(variable)) {
        return;
      }
      String v = CoactivaTexto.blankToNull(valor);
      if (v != null && !"null".equalsIgnoreCase(v)) {
        valores.put(variable, v);
      }
    }

    void copiar(String origen, String... destinos) {
      Object v = valores.get(origen);
      if (v == null || v.toString().isBlank()) {
        return;
      }
      for (String destino : destinos) {
        valores.put(destino, v);
      }
    }

    void copiarSiAusente(String origen, String destino) {
      if (valores.containsKey(destino)) {
        return;
      }
      Object v = valores.get(origen);
      if (v != null && !v.toString().isBlank()) {
        valores.put(destino, v);
      }
    }

    Object valor(String variable) {
      return valores.get(variable);
    }

    private void preferir(
        String variable, String estructurado, String ocr, java.util.function.UnaryOperator<String> clave) {
      if (estructurado != null) {
        valores.put(variable, estructurado);
        if (ocr != null && !clave.apply(estructurado).equals(clave.apply(ocr))) {
          discrepancias.add(variable);
        }
      } else if (ocr != null) {
        valores.put(variable, ocr);
      }
    }
  }

  record Cuenta(String numero, String titular, String banco, BigDecimal monto) {}

  record Config(
      String gerenteGeneral,
      String correoInstitucional,
      String correoEstudioJuridico,
      Map<String, String> zonales) {}

  record Fuentes(
      CoactivaExpediente expediente,
      List<CoactivaParticipante> participantes,
      Map<String, Object> datosExtraidos,
      CoactivaOficina oficina,
      CoactivaDelegado delegado,
      String secretarioNombre,
      Config config) {}

  /**
   * @param valores variable → valor listo para la plantilla; solo contiene variables resueltas
   * @param discrepancias variables donde el OCR difiere del dato estructurado (se usó el estructurado)
   * @param analisisDisponible hay un análisis IA ANALIZADO del expediente
   */
  public record PlantillaDatos(
      Map<String, Object> valores, List<String> discrepancias, boolean analisisDisponible) {

    public PlantillaDatos {
      valores = Map.copyOf(valores);
      discrepancias = List.copyOf(discrepancias);
    }

    /** Tags de la plantilla sin valor resuelto, en el orden en que aparecen. */
    public List<String> faltantes(Collection<String> tags) {
      return tags.stream().filter(t -> !valores.containsKey(t)).distinct().toList();
    }

    /** Valores resueltos intactos; cada tag faltante queda como {@code ""} (nunca se inventa). */
    public Map<String, Object> valoresConVacios(Collection<String> faltantes) {
      Map<String, Object> completos = new HashMap<>(valores);
      faltantes.forEach(t -> completos.putIfAbsent(t, ""));
      return completos;
    }
  }
}
