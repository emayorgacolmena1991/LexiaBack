package com.lexia.api.modules.expedientes.coactivas.actuacion;

import com.lexia.api.modules.expedientes.coactivas.CoactivaTexto;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegado;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegadoRepository;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficina;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficinaRepository;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipante;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipanteRepository;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaActa;
import com.lexia.api.modules.expedientes.coactivas.ingesta.CoactivaActaRepository;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
  private static final Pattern CLAVE_JSON = Pattern.compile("\"([^\"]+)\"");
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
  private final CoactivaActaRepository actas;
  private final AppUserRepository usuarios;
  private final Config config;
  private final ZoneId zona;

  public CoactivaPlantillaDataMapper(
      CoactivaParticipanteRepository participantes,
      CoactivaAnalisisRepository analisis,
      CoactivaDiagnosticoParser parser,
      CoactivaOficinaRepository oficinas,
      CoactivaDelegadoRepository delegados,
      CoactivaActaRepository actas,
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
    this.actas = actas;
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
            config,
            fechaActa(tenantId, expediente.getActaEntregaId()));
    PlantillaDatos datos = construir(fuentes, ZonedDateTime.now(zona));
    if (!datos.discrepancias().isEmpty()) {
      LOG.warn(
          "Plantilla coactiva expediente={}: OCR difiere del dato estructurado en {}",
          expedienteId,
          datos.discrepancias());
    }
    return datos;
  }

  /** Fecha del acta de entrega vinculada. Sin acta o sin fecha, null: no se inventa. */
  private LocalDate fechaActa(UUID tenantId, UUID actaId) {
    if (actaId == null) {
      return null;
    }
    return actas
        .findByIdAndTenantIdAndDeletedAtIsNull(actaId, tenantId)
        .map(CoactivaActa::getFechaActa)
        .orElse(null);
  }

  static PlantillaDatos construir(Fuentes f, ZonedDateTime ahora) {
    Map<String, Object> ocr = normalizarExtraidos(f.datosExtraidos());
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
        texto(ocr, "nombre_garante_solidario", "garante"));
    r.cedula(
        "cedula_garante_solidario",
        garante == null ? null : garante.getIdentificacion(),
        texto(ocr, "cedula_garante_solidario", "cedula_garante"));
    r.nombre(
        "nombre_depositario_judicial",
        null,
        texto(ocr, "nombre_depositario_judicial", "nombre_depositario", "depositario"));
    r.cedula(
        "cedula_depositario_judicial",
        null,
        texto(ocr, "cedula_depositario_judicial", "cedula_depositario"));

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
    r.texto(
        "titulo_delegado",
        delegado == null ? null : CoactivaTexto.blankToNull(delegado.getCargo()),
        texto(ocr, "titulo_delegado"));
    r.nombre("nombre_gerente_general", cfg.gerenteGeneral(), texto(ocr, "nombre_gerente_general"));
    // Secretario: SAE asignado, si no el OCR (nombre_secretario u otro alias). El delegado no lo sustituye.
    String secretarioOcr =
        texto(ocr, "nombre_abogado_secretario", "nombre_secretario", "abogado_secretario");
    if (mismaPersona(secretarioOcr, r.valor("nombre_funcionario_coactiva"))) {
      secretarioOcr = null;
    }
    r.nombre("nombre_abogado_secretario", f.secretarioNombre(), secretarioOcr);

    r.correo(
        "correo_estudio_juridico_externo",
        cfg.correoEstudioJuridico(),
        texto(ocr, "correo_estudio_juridico_externo"));
    r.correo(
        "correo_coactiva_institucional",
        cfg.correoInstitucional(),
        texto(ocr, "correo_coactiva_institucional"));

    r.sinComparar("ciudad_actuacion", f.oficina() == null ? null : f.oficina().getNombre(), null);
    String zonal =
        exp.getOficinaCodigo() == null
            ? null
            : cfg.zonales().get(exp.getOficinaCodigo().toUpperCase(Locale.ROOT));
    // Tratado aunque el sistema no tenga zona: el alias OCR igual llena si el dato de sistema falta.
    r.sinComparar("numero_zonal", zonal, texto(ocr, "numero_zona", "numero_zonal"));
    r.sinComparar("fecha_acta", fechaLarga(f.fechaActa()), texto(ocr, "fecha_acta"));
    r.sinComparar("fecha_actuacion", fechaLarga(ahora.toLocalDate()), null);
    r.sinComparar("hora_actuacion", HORA.format(ahora), null);

    r.montoPreferido("monto_deuda_total", monto(exp.getMontoOriginal()), monto(ocr.get("monto_deuda_total")));
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
    r.sinComparar("banco_embargado", null, banco);

    completar(r, f, ocr, ahora);
    return new PlantillaDatos(r.valores, r.discrepancias, f.datosExtraidos() != null, r.desdeOcr);
  }

  /** Mismo nombre tras limpiar tratamientos (p. ej. el OCR etiqueta al delegado como secretario). */
  static boolean mismaPersona(String a, Object b) {
    String ca = limpiarNombre(a);
    String cb = limpiarNombre(b == null ? null : b.toString());
    return ca != null && cb != null && CoactivaTexto.claveNombre(ca).equals(CoactivaTexto.claveNombre(cb));
  }

  /**
   * Aplana grupos anidados del diagnóstico IA y traduce sinónimos OCR a las claves que ya
   * consumen {@link #construir} / las plantillas. Las listas estructuradas (p. ej.
   * {@code cuentas_embargadas}) se conservan intactas.
   */
  static Map<String, Object> normalizarExtraidos(Map<String, Object> raw) {
    if (raw == null || raw.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> out = new LinkedHashMap<>();
    for (Map.Entry<String, Object> e : raw.entrySet()) {
      String key = normalizarClave(e.getKey());
      Object value = e.getValue();
      if (key == null || value == null) {
        continue;
      }
      if (value instanceof Map<?, ?> grupo) {
        expandirGrupo(key, grupo, out);
      } else if (value instanceof Collection<?>) {
        out.putIfAbsent(key, value);
      } else {
        Object limpio = escalar(value);
        if (limpio != null) {
          out.putIfAbsent(key, limpio);
        }
      }
    }
    // No promover monto_liquidacion → deuda: si solo viene de liquidación, debe caducar con ella.
    aliasSiAusente(out, "monto_deuda_total", "monto_mora", "valor_deuda", "total_liquidacion");
    aliasSiAusente(out, "fecha_liquidacion", "fecha_liquidacion_actualizada");
    aliasSiAusente(out, "nombre_deudor_principal", "deudor");
    aliasSiAusente(out, "cedula_deudor_principal", "cedula_deudor");
    aliasSiAusente(out, "nombre_garante_solidario", "garante");
    aliasSiAusente(out, "cedula_garante_solidario", "cedula_garante");
    aliasSiAusente(out, "nombre_depositario_judicial", "depositario");
    aliasSiAusente(out, "cedula_depositario_judicial", "cedula_depositario");
    aliasSiAusente(out, "monto_honorarios", "honorarios", "valor_honorarios");
    aliasSiAusente(out, "monto_abono", "valor_abono");
    // Alias de plantilla que la IA devolvió con el nombre del tag (p. ej. nombre_deudor_1):
    // se promueven al canónico para que pasen por la misma validación y el mismo origen.
    for (Map.Entry<String, String> e : ALIAS_A_CANONICO.entrySet()) {
      aliasSiAusente(out, e.getValue(), e.getKey());
    }
    return out;
  }

  private static void expandirGrupo(String grupo, Map<?, ?> map, Map<String, Object> out) {
    String g = grupo.toLowerCase(Locale.ROOT);
    for (Map.Entry<?, ?> e : map.entrySet()) {
      if (e.getKey() == null || e.getValue() == null || e.getValue() instanceof Map || e.getValue() instanceof Collection) {
        continue;
      }
      if (escalar(e.getValue()) == null) {
        continue;
      }
      String leaf = normalizarClave(String.valueOf(e.getKey()));
      if (leaf == null) {
        continue;
      }
      String destino = destinoGrupo(g, leaf);
      if (destino != null) {
        out.putIfAbsent(destino, e.getValue());
      }
    }
  }

  /** Solo destinos que ya existen como tags/aliases de plantilla; no inventa nombres nuevos. */
  private static String destinoGrupo(String grupo, String leaf) {
    if (grupo.equals("acta") || grupo.equals("acta_entrega")) {
      if (leaf.equals("fecha") || leaf.equals("fecha_acta")) {
        return "fecha_acta";
      }
      return null;
    }
    if (grupo.equals("auto_designacion")
        || grupo.equals("auto_de_designacion")
        || grupo.equals("designacion_depositario")) {
      if (leaf.equals("fecha")
          || leaf.equals("fecha_designacion")
          || leaf.equals("fecha_auto_designacion")
          || leaf.equals("fecha_auto_de_designacion")) {
        return "fecha_auto_designacion";
      }
      return null;
    }
    if (grupo.equals("liquidacion") || grupo.equals("liquidacion_actualizada")) {
      if (leaf.equals("fecha") || leaf.equals("fecha_liquidacion") || leaf.equals("fecha_liquidacion_actualizada")) {
        return "fecha_liquidacion";
      }
      if (leaf.equals("monto") || leaf.equals("total") || leaf.equals("monto_liquidacion") || leaf.equals("valor")) {
        return "monto_liquidacion";
      }
      if (leaf.equals("monto_deuda_total")) {
        return "monto_deuda_total";
      }
      return null;
    }
    if (grupo.equals("deudor") || grupo.equals("deudor_principal")) {
      if (leaf.equals("nombre") || leaf.equals("nombres") || leaf.equals("nombre_completo")) {
        return "deudor";
      }
      if (leaf.equals("cedula") || leaf.equals("identificacion")) {
        return "cedula_deudor";
      }
      if (leaf.equals("correo") || leaf.equals("email")) {
        return "correo_notificacion_deudor";
      }
      return null;
    }
    if (grupo.equals("garante") || grupo.equals("garante_solidario")) {
      if (leaf.equals("nombre") || leaf.equals("nombres") || leaf.equals("nombre_completo")) {
        return "nombre_garante_solidario";
      }
      if (leaf.equals("cedula") || leaf.equals("identificacion")) {
        return "cedula_garante_solidario";
      }
      return null;
    }
    if (grupo.equals("delegado") || grupo.equals("funcionario_coactiva")) {
      if (leaf.equals("nombre") || leaf.equals("nombres") || leaf.equals("nombre_delegado")) {
        return "nombre_funcionario_coactiva";
      }
      if (leaf.equals("titulo") || leaf.equals("cargo") || leaf.equals("titulo_delegado")) {
        return "titulo_delegado";
      }
      if (leaf.equals("resolucion") || leaf.equals("numero_resolucion") || leaf.equals("numero_resolucion_delegacion")) {
        return "numero_resolucion_delegacion";
      }
      if (leaf.equals("fecha_resolucion") || leaf.equals("fecha_resolucion_delegacion")) {
        return "fecha_resolucion_delegacion";
      }
      return null;
    }
    if (grupo.equals("secretario") || grupo.equals("abogado_secretario")) {
      if (leaf.equals("nombre") || leaf.equals("nombres") || leaf.equals("nombre_secretario")) {
        return "nombre_secretario";
      }
      if (leaf.equals("titulo") || leaf.equals("cargo") || leaf.equals("titulo_secretario")) {
        return "titulo_secretario";
      }
      return null;
    }
    if (grupo.equals("depositario") || grupo.equals("depositario_judicial")) {
      if (leaf.equals("nombre")
          || leaf.equals("nombres")
          || leaf.equals("nombre_completo")
          || leaf.equals("nombre_depositario")
          || leaf.equals("nombre_depositario_judicial")) {
        return "nombre_depositario_judicial";
      }
      if (leaf.equals("cedula")
          || leaf.equals("identificacion")
          || leaf.equals("cedula_depositario")
          || leaf.equals("cedula_depositario_judicial")) {
        return "cedula_depositario_judicial";
      }
      if (leaf.equals("titulo") || leaf.equals("titulo_depositario")) {
        return "titulo_depositario";
      }
      return null;
    }
    if (grupo.equals("honorarios") || grupo.equals("honorario")) {
      if (leaf.equals("monto") || leaf.equals("total") || leaf.equals("valor") || leaf.equals("monto_honorarios")) {
        return "monto_honorarios";
      }
      if (leaf.equals("subtotal") || leaf.equals("valor_honorarios_subtotal")) {
        return "valor_honorarios_subtotal";
      }
      if (leaf.equals("iva") || leaf.equals("valor_iva_honorarios")) {
        return "valor_iva_honorarios";
      }
      if (leaf.equals("cuenta") || leaf.equals("numero_cuenta") || leaf.equals("cuenta_honorarios_abogado")) {
        return "cuenta_honorarios_abogado";
      }
      return null;
    }
    // Grupo no catalogado: solo promover hojas que ya parecen tags de plantilla (con _).
    return leaf.contains("_") ? leaf : null;
  }

  private static void aliasSiAusente(Map<String, Object> out, String canonical, String... aliases) {
    if (escalar(buscar(out, canonical)) != null) {
      return;
    }
    for (String alias : aliases) {
      Object v = escalar(buscar(out, alias));
      if (v != null) {
        out.put(canonical, v);
        return;
      }
    }
  }

  private static Object escalar(Object raw) {
    if (raw == null || raw instanceof Map || raw instanceof Collection) {
      return null;
    }
    if (raw instanceof String s) {
      String t = s.trim();
      if (t.isEmpty() || "null".equalsIgnoreCase(t) || CoactivaVariableBinder.NODATA.equalsIgnoreCase(t)) {
        return null;
      }
    }
    return raw;
  }

  /** {@code NUMERO_OPERACION}, {@code numeroOperacion} y {@code numero-operacion} → {@code numero_operacion}. */
  static String normalizarClave(String raw) {
    if (raw == null) {
      return null;
    }
    String s = raw.trim();
    if (s.isEmpty()) {
      return null;
    }
    s = s.replaceAll("([\\p{Ll}\\p{Nd}])(\\p{Lu})", "$1_$2");
    s = s.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{Nd}]+", "_").replaceAll("_+", "_");
    s = s.replaceAll("^_|_$", "");
    return s.isEmpty() ? null : s;
  }

  static Object buscar(Map<String, Object> datos, String key) {
    if (datos == null || key == null) {
      return null;
    }
    if (datos.containsKey(key)) {
      return datos.get(key);
    }
    String n = normalizarClave(key);
    if (n == null) {
      return null;
    }
    if (datos.containsKey(n)) {
      return datos.get(n);
    }
    for (Map.Entry<String, Object> e : datos.entrySet()) {
      if (n.equals(normalizarClave(e.getKey()))) {
        return e.getValue();
      }
    }
    return null;
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
    totalEmbargo(r);

    ALIASES.forEach((canonico, alias) -> r.copiar(canonico, alias.toArray(String[]::new)));
    // Fuera de ALIASES a propósito: si la deuda solo viniera de la liquidación debe caducar con ella.
    r.copiar("monto_deuda_total", "monto_liquidacion");
    r.copiar("monto_deuda_total_letras", "monto_liquidacion_letras");
    r.copiarSiAusente("monto_embargo_total", "valor_embargo_num");
    r.copiarSiAusente("monto_embargo_total_letras", "valor_embargo_texto");
    r.copiarSiAusente("valor_embargo_num_letras", "valor_embargo_texto");
    r.copiarSiAusente("numero_cuenta_1", "numero_cuenta_retencion");
    r.copiarSiAusente("numero_cuenta_1", "numero_cuenta_retencion_1");
    r.copiarSiAusente("nombre_deudor_principal", "nombre_receptor");
    r.copiarSiAusente("cedula_deudor_principal", "cedula_receptor");
    r.copiarSiAusente("ciudad_actuacion", "agencia");
    r.copiarSiAusente("ciudad_actuacion", "unidad_ejecucion_coactiva");
    r.copiarSiAusente("fecha_liquidacion", "fecha_liquidacion_actualizada");
    // fecha_auto_designacion no sale de fecha_acta: son fechas distintas y solo entra si el OCR la trae.
    r.copiarSiAusente("detalle_abono", "detalle_abono_texto");
    r.copiarSiAusente("detalle_abono_texto", "detalle_abono");
    partirFecha(r, "fecha_embargo", "dia_embargo", "mes_embargo", "anio_embargo");
    partirFecha(r, "fecha_factura", "dia_factura", "mes_factura", "anio_factura");
    desglosarHonorarios(r, ocr);
    copiarLetrasSiAusente(r, "monto_embargo_1");
    copiarLetrasSiAusente(r, "monto_embargo_2");
    copiarLetrasSiAusente(r, "monto_embargo_3");
    copiarLetrasSiAusente(r, "monto_retencion_1");
    copiarLetrasSiAusente(r, "monto_retencion_2");
    copiarLetrasSiAusente(r, "monto_abono");
    copiarLetrasSiAusente(r, "monto_credito_original");
    copiarLetrasSiAusente(r, "monto_embargo_total");
    desglosarFecha(r, "fecha_embargo", "anio_embargo", "mes_embargo", "dia_embargo");
    desglosarFecha(r, "fecha_comprobante_pago", "anio_factura", "mes_factura", null);
    derivarIva(r);
  }

  /**
   * Canónico → alias de plantilla. Es la única tabla: {@link #completar} copia valores,
   * {@link #normalizarExtraidos} promueve lo que la IA devolvió con nombre de alias y
   * {@link #claveExtraible} decide qué pedir cuando falta un alias.
   */
  static final Map<String, List<String>> ALIASES;

  static {
    Map<String, List<String>> m = new LinkedHashMap<>();
    m.put("numero_juicio_coactivo", List.of("numero_juicio", "numero_proceso", "numero_proceso_coactivo", "NUMERO_JUICIO"));
    m.put("numero_operacion", List.of("NUMERO_OPERACION"));
    m.put("nombre_deudor_principal", List.of("nombre_deudor_principal_1", "nombre_deudor_1", "nombre_cliente", "DEUDOR_NOMBRE"));
    m.put("cedula_deudor_principal", List.of("cedula_deudor_principal_1", "cedula_deudor_1"));
    m.put("nombre_garante_solidario", List.of("nombre_garante_solidario_1", "nombre_garante_1"));
    m.put("cedula_garante_solidario", List.of("cedula_garante_solidario_1", "cedula_garante_1"));
    m.put("nombre_deudor_principal_2", List.of("nombre_deudor_2"));
    m.put("cedula_deudor_principal_2", List.of("cedula_deudor_2"));
    m.put("nombre_garante_solidario_2", List.of("nombre_garante_2"));
    m.put("cedula_garante_solidario_2", List.of("cedula_garante_2"));
    m.put("nombre_funcionario_coactiva", List.of("funcionario_coactiva", "nombre_delegado"));
    m.put("nombre_abogado_secretario", List.of("abogado_secretario", "nombre_secretario"));
    m.put("ciudad_actuacion", List.of("ciudad", "ciudad_zonal"));
    m.put("numero_zonal", List.of("numero_zona"));
    m.put("fecha_actuacion", List.of("fecha_providencia"));
    m.put("hora_actuacion", List.of("hora"));
    m.put("correo_notificacion_deudor", List.of("correo_notificacion_1"));
    m.put("correo_coactiva_institucional", List.of("correo_notificacion_coactiva"));
    m.put("correo_estudio_juridico_externo", List.of("correo_abogado_externo", "correo_abogado_secretario"));
    m.put("correo_funcionario_coactiva", List.of("correo_delegado"));
    m.put("banco_embargado", List.of("banco_retencion", "institucion_financiera"));
    m.put("nombre_depositario_judicial", List.of("nombre_depositario"));
    m.put("cedula_depositario_judicial", List.of("cedula_depositario"));
    m.put("cuenta_honorarios_abogado", List.of("numero_cuenta_honorarios"));
    m.put("monto_honorarios", List.of("valor_honorarios_total_num", "total"));
    m.put("monto_honorarios_letras", List.of("valor_honorarios_total_texto"));
    m.put("monto_abono", List.of("abono"));
    m.put("monto_embargo_total", List.of("embargo", "valor_embargo_num"));
    m.put("monto_retencion", List.of("valor_retencion", "montoRetencion", "retencion", "monto_retencion_num", "valor_a_retenerse"));
    m.put("monto_retencion_letras", List.of("valor_retencion_texto", "montoRetencionLetras", "valor_a_retenerse_texto"));
    m.put("fecha_auto_designacion", List.of("fecha_designacion", "fecha_auto_de_designacion", "fecha_designacion_depositario"));
    m.put("numero_resolucion_delegacion", List.of("NUMERO_RESOLUCION_DELEGACION"));
    m.put("fecha_resolucion_delegacion", List.of("FECHA_RESOLUCION_DELEGACION"));
    ALIASES = Map.copyOf(m);
  }

  /** alias → canónico (inverso de {@link #ALIASES}). */
  static final Map<String, String> ALIAS_A_CANONICO =
      ALIASES.entrySet().stream()
          .flatMap(e -> e.getValue().stream().map(a -> Map.entry(a, e.getKey())))
          .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

  /** Misma tabla, con la llave ya en snake_case. Solo entra si el alias no es el canónico. */
  static final Map<String, String> ALIAS_NORM;

  static {
    Map<String, String> n = new LinkedHashMap<>();
    ALIAS_A_CANONICO.forEach(
        (alias, canon) -> {
          String k = normalizarClave(alias);
          if (k != null && !k.equals(canon)) {
            n.putIfAbsent(k, canon);
          }
        });
    ALIAS_NORM = Map.copyOf(n);
  }

  /** Tags que solo se calculan a partir de otro dato; el OCR nunca los rellena directamente. */
  private static final Map<String, String> DERIVADO_DE =
      Map.ofEntries(
          Map.entry("valor", "monto_honorarios"),
          Map.entry("iva", "monto_honorarios"),
          Map.entry("total", "monto_honorarios"),
          Map.entry("valor_honorarios_subtotal", "monto_honorarios"),
          Map.entry("valor_iva_honorarios", "monto_honorarios"),
          Map.entry("valor_honorarios_total_num", "monto_honorarios"),
          Map.entry("valor_honorarios_total_texto", "monto_honorarios"),
          Map.entry("abono", "monto_abono"),
          Map.entry("embargo", "monto_embargo_total"),
          Map.entry("valor_embargo_num", "monto_embargo_total"),
          Map.entry("valor_embargo_texto", "monto_embargo_total"),
          Map.entry("dia_embargo", "fecha_embargo"),
          Map.entry("mes_embargo", "fecha_embargo"),
          Map.entry("anio_embargo", "fecha_embargo"),
          Map.entry("dia_factura", "fecha_factura"),
          Map.entry("mes_factura", "fecha_factura"),
          Map.entry("anio_factura", "fecha_factura"),
          Map.entry("detalle_abono_texto", "detalle_abono"));

  /**
   * Tags sin fuente documental: los fija el sistema (fecha/hora/ciudad de la actuación, fojas),
   * la configuración (correos institucionales, gerente) o una asignación (delegado, secretario),
   * o son anexos que adjunta el abogado. Si faltan, quedan pendientes para intervención manual:
   * no se le piden a la IA.
   */
  private static final Pattern NO_EXTRAIBLE =
      Pattern.compile(
          "(?i)^(anexo_\\d+|fecha_actuacion|hora_actuacion|(dia|mes|anio)_entrega|ciudad_actuacion|"
              + "ciudad_sucursal|numero_zonal|zona|agencia|unidad_ejecucion_coactiva|nombre_institucion_bancaria|"
              + "foja_\\w+|titulo_(?!(?:depositario|secretario)$)\\w+|numero_oficio|nombre_abogado_secretario|nombre_gerente_\\w+|"
              + "nombre_secretario_zonal|nombre_funcionario_coactiva|correo_funcionario_coactiva|"
              + "correo_coactiva_institucional|correo_estudio_juridico_externo|correo_coordinacion)$");

  /**
   * Clave canónica que se le pediría al OCR/IA para resolver {@code tag}, o {@code null} si el
   * dato no sale de los documentos. Un alias se resuelve a su canónico y un derivado
   * ({@code valor}, {@code abono}, {@code *_letras}) a la variable de la que se calcula.
   */
  static String claveExtraible(String tag) {
    if (tag == null || tag.isBlank()) {
      return null;
    }
    String clave = normalizarClave(tag.trim());
    if (clave == null) {
      return null;
    }
    for (int i = 0; i < 3; i++) {
      String previa = clave;
      clave = ALIAS_A_CANONICO.getOrDefault(clave, clave);
      clave = ALIAS_NORM.getOrDefault(clave, clave);
      clave = DERIVADO_DE.getOrDefault(clave, clave);
      if (clave.endsWith("_letras")) {
        clave = clave.substring(0, clave.length() - "_letras".length());
      }
      if (clave.equals(previa)) {
        break;
      }
    }
    return NO_EXTRAIBLE.matcher(clave).matches() ? null : clave;
  }

  /**
   * Claves canónicas distintas que sí salen del documento, a partir del JSON de variables de
   * plantilla. Alias y derivados colapsan; lo que fija el sistema se omite.
   */
  public static String textoExtraccion(String variablesJson) {
    if (variablesJson == null || variablesJson.isBlank()) {
      return "";
    }
    LinkedHashSet<String> claves = new LinkedHashSet<>();
    Matcher m = CLAVE_JSON.matcher(variablesJson);
    while (m.find()) {
      String clave = claveExtraible(m.group(1));
      if (clave != null) {
        claves.add(clave);
      }
    }
    return String.join(", ", claves);
  }

  private static void persona(Resolver r, Map<String, Object> ocr, String nombreKey, String cedulaKey, CoactivaParticipante p) {
    r.nombre(nombreKey, p == null ? null : p.getNombreCompleto(), texto(ocr, nombreKey));
    r.cedula(cedulaKey, p == null ? null : p.getIdentificacion(), texto(ocr, cedulaKey));
  }

  /**
   * Lo que el OCR traiga con el mismo nombre de variable. Se saltan: alias (ya promovidos al
   * canónico), derivados (se calculan) y anexos (sin fuente fiable).
   */
  private static void volcarOcr(Resolver r, Map<String, Object> ocr) {
    for (Map.Entry<String, Object> e : ocr.entrySet()) {
      Object raw = e.getValue();
      String key = e.getKey();
      if (raw == null || raw instanceof Map || raw instanceof Collection || !desdeOcrPermitido(key)) {
        continue;
      }
      if (esMonto(key)) {
        r.montoSiAusente(key, monto(raw));
      } else {
        r.ocrSiAusente(key, raw.toString());
      }
    }
  }

  static boolean desdeOcrPermitido(String key) {
    if (key == null || ALIAS_A_CANONICO.containsKey(key)) {
      return false;
    }
    String n = normalizarClave(key);
    String k = n == null ? key : n;
    String canon = ALIAS_NORM.get(k);
    return (canon == null || canon.equals(k))
        && !DERIVADO_DE.containsKey(k)
        && !k.startsWith("anexo_");
  }

  static boolean esMonto(String key) {
    if (key.endsWith("_letras") || key.endsWith("_texto")) {
      return false;
    }
    return key.startsWith("monto_") || key.startsWith("valor_") || key.equals("valor") || key.equals("iva") || key.equals("total") || key.equals("abono");
  }

  /** {@code monto_embargo_total}: lo que diga el OCR o, si no consta, la suma de los embargos 1..3. */
  private static void totalEmbargo(Resolver r) {
    if (r.valor("monto_embargo_total") != null) {
      return;
    }
    BigDecimal suma = BigDecimal.ZERO;
    boolean alguno = false;
    for (int i = 1; i <= 3; i++) {
      BigDecimal m = monto(r.valor("monto_embargo_" + i));
      if (m != null) {
        suma = suma.add(m);
        alguno = true;
      }
    }
    if (alguno) {
      r.monto("monto_embargo_total", suma);
    }
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
    r.derivado(fechaKey, dia, m.group(1));
    r.derivado(fechaKey, mes, m.group(2).toLowerCase(ES));
    r.derivado(fechaKey, anio, m.group(3));
  }

  /**
   * {@code monto_honorarios} es el total con IVA. Si el OCR ya trae subtotal e IVA, se conservan.
   * El 15 % solo rellena el desglose ausente.
   */
  private static void desglosarHonorarios(Resolver r, Map<String, Object> ocr) {
    BigDecimal subtotalOcr = primeroMonto(ocr, "valor_honorarios_subtotal", "valor");
    BigDecimal ivaOcr = primeroMonto(ocr, "valor_iva_honorarios", "iva");
    BigDecimal total = monto(r.valor("monto_honorarios"));
    if (total == null && subtotalOcr != null && ivaOcr != null) {
      total = subtotalOcr.add(ivaOcr);
      r.monto("monto_honorarios", total);
    }
    if (total == null) {
      return;
    }
    BigDecimal subtotal;
    BigDecimal iva;
    if (subtotalOcr != null && ivaOcr != null) {
      subtotal = subtotalOcr;
      iva = ivaOcr;
    } else if (subtotalOcr != null) {
      subtotal = subtotalOcr;
      iva = total.subtract(subtotal);
    } else if (ivaOcr != null) {
      iva = ivaOcr;
      subtotal = total.subtract(iva);
    } else {
      // ponytail: 15 % fijo si el OCR no trae el desglose; pasar a CoactivaParametros cuando exista.
      subtotal = total.divide(DIVISOR_IVA, 2, RoundingMode.HALF_UP);
      iva = total.subtract(subtotal);
    }
    if (subtotal.signum() < 0 || iva.signum() < 0) {
      subtotal = total.divide(DIVISOR_IVA, 2, RoundingMode.HALF_UP);
      iva = total.subtract(subtotal);
    }
    r.derivado("monto_honorarios", "valor_honorarios_subtotal", formatoMonto(subtotal));
    r.derivado("monto_honorarios", "valor_iva_honorarios", formatoMonto(iva));
    r.derivado("monto_honorarios", "valor", formatoMonto(subtotal));
    r.derivado("monto_honorarios", "iva", formatoMonto(iva));
    r.derivado("monto_honorarios", "total", formatoMonto(total));
  }

  private static BigDecimal primeroMonto(Map<String, Object> ocr, String... keys) {
    for (String key : keys) {
      BigDecimal valor = monto(ocr.get(key));
      if (valor != null) {
        return valor;
      }
    }
    return null;
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
      Object v = buscar(datos, key);
      if (v == null || v instanceof Map || v instanceof Collection) {
        continue;
      }
      String s = CoactivaTexto.blankToNull(v.toString());
      if (s != null && !"null".equalsIgnoreCase(s) && !CoactivaVariableBinder.NODATA.equalsIgnoreCase(s)) {
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

  /**
   * Resolución estructurado-primero; el OCR solo completa y sus diferencias se registran. Toda
   * variable que pasa por {@code preferir}/{@code sinComparar}/{@code monto} queda en {@code
   * tratados}: aunque se rechace su valor, el volcado crudo del OCR ya no la rellena.
   */
  private static final class Resolver {
    private final Map<String, Object> valores = new LinkedHashMap<>();
    private final List<String> discrepancias = new ArrayList<>();
    private final Set<String> tratados = new HashSet<>();
    private final Set<String> desdeOcr = new HashSet<>();

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
      tratados.add(variable);
      String e = CoactivaTexto.blankToNull(estructurado);
      String valor = e != null ? e : CoactivaTexto.blankToNull(ocr);
      if (valor != null) {
        valores.put(variable, valor);
        if (e == null) {
          desdeOcr.add(variable);
        }
      }
    }

    /** Estructurado gana; si el OCR trae otro monto, queda la discrepancia. */
    void montoPreferido(String variable, BigDecimal estructurado, BigDecimal ocr) {
      tratados.add(variable);
      tratados.add(variable + "_letras");
      if (estructurado == null && ocr == null) {
        return;
      }
      BigDecimal valor = estructurado != null ? estructurado : ocr;
      valores.put(variable, formatoMonto(valor));
      valores.put(variable + "_letras", letras(valor));
      if (estructurado == null) {
        desdeOcr.add(variable);
        desdeOcr.add(variable + "_letras");
      } else if (ocr != null && estructurado.compareTo(ocr) != 0) {
        discrepancias.add(variable);
      }
    }

    /** Los montos sin dato de expediente salen del OCR (o de sumar montos del OCR). */
    void monto(String variable, BigDecimal valor) {
      tratados.add(variable);
      tratados.add(variable + "_letras");
      if (valor == null) {
        return;
      }
      valores.put(variable, formatoMonto(valor));
      valores.put(variable + "_letras", letras(valor));
      desdeOcr.add(variable);
      desdeOcr.add(variable + "_letras");
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
      if (v != null && !"null".equalsIgnoreCase(v) && !CoactivaVariableBinder.NODATA.equalsIgnoreCase(v)) {
        valores.put(variable, v);
      }
    }

    /** Como {@link #ponerSiAusente} pero respeta {@code tratados} y marca el origen OCR. */
    void ocrSiAusente(String variable, String valor) {
      if (variable == null || tratados.contains(variable) || valores.containsKey(variable)) {
        return;
      }
      ponerSiAusente(variable, valor);
      if (valores.containsKey(variable)) {
        desdeOcr.add(variable);
      }
    }

    /** Valor derivado (cálculo, partición de fecha): hereda el origen de {@code origen}. */
    void derivado(String origen, String variable, String valor) {
      ponerSiAusente(variable, valor);
      if (valores.containsKey(variable) && desdeOcr.contains(origen)) {
        desdeOcr.add(variable);
      }
    }

    void copiar(String origen, String... destinos) {
      Object v = valores.get(origen);
      if (v == null || v.toString().isBlank()) {
        return;
      }
      boolean difiere = discrepancias.contains(origen);
      for (String destino : destinos) {
        valores.put(destino, v);
        if (desdeOcr.contains(origen)) {
          desdeOcr.add(destino);
        } else {
          desdeOcr.remove(destino);
        }
        if (difiere) {
          anotarDiscrepancia(destino);
        }
      }
    }

    void copiarSiAusente(String origen, String destino) {
      copiarSiAusente(origen, destino, null);
    }

    /** Si {@code valorLiteral} es null, copia el valor de {@code origen}. */
    void copiarSiAusente(String origen, String destino, String valorLiteral) {
      if (valores.containsKey(destino)) {
        return;
      }
      Object v = valores.get(origen);
      if (v == null || v.toString().isBlank()) {
        return;
      }
      Object escrito = valorLiteral == null ? v : valorLiteral;
      if (escrito.toString().isBlank()) {
        return;
      }
      valores.put(destino, escrito);
      if (desdeOcr.contains(origen)) {
        desdeOcr.add(destino);
      }
      if (discrepancias.contains(origen)) {
        anotarDiscrepancia(destino);
      }
    }

    private void anotarDiscrepancia(String variable) {
      if (!discrepancias.contains(variable)) {
        discrepancias.add(variable);
      }
    }

    Object valor(String variable) {
      return valores.get(variable);
    }

    private void preferir(
        String variable, String estructurado, String ocr, java.util.function.UnaryOperator<String> clave) {
      tratados.add(variable);
      if (estructurado != null) {
        valores.put(variable, estructurado);
        if (ocr != null && !clave.apply(estructurado).equals(clave.apply(ocr))) {
          discrepancias.add(variable);
        }
      } else if (ocr != null) {
        valores.put(variable, ocr);
        desdeOcr.add(variable);
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
      Config config,
      LocalDate fechaActa) {}

  /**
   * @param valores variable → valor listo para la plantilla; solo contiene variables resueltas
   * @param discrepancias variables donde el OCR difiere del dato estructurado (se usó el estructurado)
   * @param analisisDisponible hay un análisis IA ANALIZADO del expediente
   * @param desdeOcr variables cuyo valor salió del OCR/IA (no de un dato estructurado ni de
   *     configuración); el resto son de sistema
   */
  public record PlantillaDatos(
      Map<String, Object> valores,
      List<String> discrepancias,
      boolean analisisDisponible,
      Set<String> desdeOcr) {

    public PlantillaDatos {
      Map<String, Object> limpios = new LinkedHashMap<>();
      if (valores != null) {
        valores.forEach(
            (k, v) -> {
              if (k != null && escalar(v) != null) {
                limpios.put(k, v);
              }
            });
      }
      valores = Map.copyOf(limpios);
      discrepancias = List.copyOf(discrepancias);
      desdeOcr = desdeOcr == null ? Set.of() : Set.copyOf(desdeOcr);
    }

    public PlantillaDatos(Map<String, Object> valores, List<String> discrepancias, boolean analisisDisponible) {
      this(valores, discrepancias, analisisDisponible, Set.of());
    }

    public boolean esDeOcr(String tag) {
      return desdeOcr.contains(tag);
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

  private static void copiarLetrasSiAusente(Resolver r, String tag) {
    Object raw = r.valor(tag);
    if (raw == null) {
      return;
    }
    String letras = NumeroALetras.monto(raw.toString());
    if (letras.isEmpty()) {
      return;
    }
    r.copiarSiAusente(tag, tag + "_letras", letras.toUpperCase(ES));
  }

  /** ISO ({@code LocalDate.parse}). {@code dia} null omite el día. No pisa un desglose ya escrito. */
  private static void desglosarFecha(Resolver r, String fechaKey, String anio, String mes, String dia) {
    Object raw = r.valor(fechaKey);
    if (raw == null) {
      return;
    }
    LocalDate fecha;
    try {
      fecha = LocalDate.parse(raw.toString().trim());
    } catch (java.time.format.DateTimeParseException e) {
      return;
    }
    if (anio != null) {
      r.derivado(fechaKey, anio, String.valueOf(fecha.getYear()));
    }
    if (mes != null) {
      r.derivado(fechaKey, mes, fecha.getMonth().getDisplayName(TextStyle.FULL, ES));
    }
    if (dia != null) {
      r.derivado(fechaKey, dia, String.valueOf(fecha.getDayOfMonth()));
    }
  }

  /** 15 % de {@code monto_honorarios}. Solo rellena IVA ausente. */
  private static void derivarIva(Resolver r) {
    BigDecimal total = monto(r.valor("monto_honorarios"));
    if (total == null) {
      return;
    }
    BigDecimal iva = total.multiply(new BigDecimal("0.15")).setScale(2, RoundingMode.HALF_UP);
    String texto = formatoMonto(iva);
    r.derivado("monto_honorarios", "valor_iva_honorarios", texto);
    r.derivado("monto_honorarios", "iva", texto);
  }
}
