package com.lexia.api.modules.notary;

import com.fasterxml.jackson.databind.JsonNode;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import com.lexia.api.modules.expedientes.minutas.MinutaViviendaData;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Alertas de tarifa e impuestos de una remisión, calculadas al vuelo desde el payload de la versión
 * vigente; no se persisten. Lee campos que {@link MinutaViviendaData} aún no declara (fecha de
 * nacimiento y discapacidad), por eso trabaja sobre el JSON.
 */
final class NotaryAlertas {

  static final String TARIFA_TERCERA_EDAD = "TARIFA_TERCERA_EDAD";
  static final String TARIFA_DISCAPACIDAD = "TARIFA_DISCAPACIDAD";
  static final String IMPUESTOS_TERCERA_EDAD = "IMPUESTOS_TERCERA_EDAD";
  static final String DATO_EDAD_NO_DISPONIBLE = "DATO_EDAD_NO_DISPONIBLE";
  static final String FONDOS_SIN_SOPORTE = "FONDOS_SIN_SOPORTE";
  static final String NIVEL_AVISO = "AVISO";
  static final String NIVEL_INFO = "INFO";
  static final String ROL_COMPRADOR = "COMPRADOR";
  static final String ROL_VENDEDOR = "VENDEDOR";
  static final int EDAD_TERCERA_EDAD = 65;
  static final ZoneId ZONA = ZoneId.of("America/Guayaquil");

  /** Las que marcan la fila de la bandeja. */
  static final Set<String> TARIFA_O_IMPUESTOS =
      Set.of(TARIFA_TERCERA_EDAD, TARIFA_DISCAPACIDAD, IMPUESTOS_TERCERA_EDAD);

  private static final Set<String> SI = Set.of("SI", "SÍ", "S", "TRUE", "1");

  private record Persona(
      String rol, String nombre, boolean conFecha, Optional<LocalDate> nacimiento, boolean discapacidad) {

    NotaryDtos.AlertaPersonaDto dto() {
      return new NotaryDtos.AlertaPersonaDto(rol, nombre);
    }
  }

  private NotaryAlertas() {}

  /**
   * @param referencia fecha a la que se calcula la edad; solo se pide si alguien trae fecha de
   *     nacimiento legible.
   * @param tiposDocumento {@code doc_type} de los documentos de la versión vigente.
   */
  static List<NotaryDtos.AlertaDto> calcular(
      JsonNode payload, Supplier<LocalDate> referencia, Collection<String> tiposDocumento) {
    List<NotaryDtos.AlertaDto> alertas = new ArrayList<>(personas(payload, referencia));
    // Solo se avisa que falta el soporte: si los ingresos alcanzan lo decide el cliente.
    if (tiposDocumento.stream().noneMatch(LegalDocument.TIPO_SOPORTE_INGRESOS::equals)) {
      alertas.add(
          new NotaryDtos.AlertaDto(
              FONDOS_SIN_SOPORTE,
              NIVEL_AVISO,
              "Debida diligencia de fondos",
              "El bufete no adjuntó soportes de ingresos; verifique el origen de los fondos",
              List.of()));
    }
    return alertas;
  }

  private static List<NotaryDtos.AlertaDto> personas(JsonNode payload, Supplier<LocalDate> referencia) {
    if (payload == null || !payload.isObject() || payload.isEmpty()) {
      return List.of();
    }
    List<Persona> compradores = compradores(payload);
    Optional<Persona> vendedor = vendedor(payload);
    LocalDate[] fecha = new LocalDate[1];
    Supplier<LocalDate> ref =
        () -> {
          if (fecha[0] == null) {
            fecha[0] = referencia.get();
          }
          return fecha[0];
        };

    List<NotaryDtos.AlertaDto> alertas = new ArrayList<>();
    List<Persona> compradoresMayores =
        compradores.stream().filter(p -> terceraEdad(p, ref)).toList();
    if (!compradoresMayores.isEmpty()) {
      alertas.add(
          alerta(
              TARIFA_TERCERA_EDAD,
              NIVEL_AVISO,
              "Tarifa notarial: tercera edad",
              "Aplicar descuento de tercera edad en la tarifa notarial",
              compradoresMayores));
    }
    List<Persona> conDiscapacidad = compradores.stream().filter(Persona::discapacidad).toList();
    if (!conDiscapacidad.isEmpty()) {
      alertas.add(
          alerta(
              TARIFA_DISCAPACIDAD,
              NIVEL_AVISO,
              "Tarifa notarial: discapacidad",
              "Aplicar descuento por discapacidad en la tarifa notarial",
              conDiscapacidad));
    }
    List<Persona> impuestos = new ArrayList<>(compradoresMayores);
    vendedor.filter(v -> terceraEdad(v, ref)).ifPresent(impuestos::add);
    if (!impuestos.isEmpty()) {
      alertas.add(
          alerta(
              IMPUESTOS_TERCERA_EDAD,
              NIVEL_AVISO,
              "Impuestos: tercera edad",
              "Considerar exoneración en la liquidación de impuestos y llenar el formulario"
                  + " correspondiente",
              impuestos));
    }
    // El vendedor suele ser compañía: sin fecha de nacimiento no se reclama su edad.
    List<Persona> sinEdad = new ArrayList<>();
    compradores.stream().filter(p -> p.nacimiento().isEmpty()).forEach(sinEdad::add);
    vendedor.filter(v -> v.conFecha() && v.nacimiento().isEmpty()).ifPresent(sinEdad::add);
    if (!sinEdad.isEmpty()) {
      alertas.add(
          alerta(
              DATO_EDAD_NO_DISPONIBLE,
              NIVEL_INFO,
              "Edad no verificada",
              "No se pudo verificar la edad de: "
                  + sinEdad.stream().map(Persona::nombre).collect(Collectors.joining(", ")),
              sinEdad));
    }
    return alertas;
  }

  static boolean conTarifaOImpuestos(List<NotaryDtos.AlertaDto> alertas) {
    return alertas.stream().anyMatch(a -> TARIFA_O_IMPUESTOS.contains(a.codigo()));
  }

  /** Edad cumplida a la fecha de referencia; el día del cumpleaños ya cuenta. */
  static int edad(LocalDate nacimiento, LocalDate referencia) {
    return Period.between(nacimiento, referencia).getYears();
  }

  /** Comprador 1 siempre; el cónyuge solo si el payload trae algún dato suyo. */
  private static List<Persona> compradores(JsonNode payload) {
    List<Persona> compradores = new ArrayList<>();
    compradores.add(persona(payload, ROL_COMPRADOR, "conyuge_1", "Comprador"));
    boolean hayConyuge =
        List.of("nombre_conyuge_2", "cedula_conyuge_2", "fecha_nacimiento_conyuge_2", "discapacidad_conyuge_2")
            .stream()
            .anyMatch(campo -> !vacio(payload, campo));
    if (hayConyuge) {
      compradores.add(persona(payload, ROL_COMPRADOR, "conyuge_2", "Cónyuge del comprador"));
    }
    return compradores;
  }

  /** Solo cuenta si trae fecha de nacimiento: la plantilla lo trata como compañía. */
  private static Optional<Persona> vendedor(JsonNode payload) {
    if (vacio(payload, "fecha_nacimiento_vendedor")) {
      return Optional.empty();
    }
    return Optional.of(persona(payload, ROL_VENDEDOR, "vendedor", "Vendedor"));
  }

  private static Persona persona(JsonNode payload, String rol, String sufijo, String porDefecto) {
    String nombre = vacio(payload, "nombre_" + sufijo) ? porDefecto : texto(payload, "nombre_" + sufijo);
    boolean conFecha = !vacio(payload, "fecha_nacimiento_" + sufijo);
    Optional<LocalDate> nacimiento =
        conFecha
            ? LetrasNotariales.parsearFecha(texto(payload, "fecha_nacimiento_" + sufijo))
            : Optional.empty();
    return new Persona(rol, nombre, conFecha, nacimiento, discapacidad(payload.get("discapacidad_" + sufijo)));
  }

  private static boolean terceraEdad(Persona persona, Supplier<LocalDate> referencia) {
    return persona.nacimiento().filter(n -> edad(n, referencia.get()) >= EDAD_TERCERA_EDAD).isPresent();
  }

  /** Booleano o «SI»/«NO». */
  private static boolean discapacidad(JsonNode valor) {
    if (valor == null || valor.isNull()) {
      return false;
    }
    if (valor.isBoolean()) {
      return valor.booleanValue();
    }
    return SI.contains(valor.asText("").trim().toUpperCase(Locale.ROOT));
  }

  private static boolean vacio(JsonNode payload, String campo) {
    JsonNode valor = payload.get(campo);
    return valor == null || valor.isNull() || MinutaViviendaData.isMissing(valor.asText(""));
  }

  private static String texto(JsonNode payload, String campo) {
    return payload.get(campo).asText("").trim();
  }

  private static NotaryDtos.AlertaDto alerta(
      String codigo, String nivel, String titulo, String detalle, List<Persona> personas) {
    return new NotaryDtos.AlertaDto(
        codigo, nivel, titulo, detalle, personas.stream().map(Persona::dto).toList());
  }
}
