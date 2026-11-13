package com.lexia.api.modules.notary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.lexia.api.modules.expedientes.documentos.LegalDocument;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class NotaryAlertasTest {

  private static final LocalDate OTORGAMIENTO = LocalDate.of(2026, 8, 20);
  /** Versión con soporte de ingresos: las pruebas de edad no ven FONDOS_SIN_SOPORTE. */
  private static final List<String> CON_SOPORTE = List.of("CEDULA", LegalDocument.TIPO_SOPORTE_INGRESOS);

  private final ObjectMapper mapper = new ObjectMapper();

  /** Comprador y cónyuge con nombre; sin fechas ni marcas. */
  private ObjectNode payload() {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("nombre_conyuge_1", "Ana Pérez");
    payload.put("nombre_conyuge_2", "Luis Mora");
    payload.put("nombre_vendedor", "ACME S.A.");
    return payload;
  }

  private ObjectNode conFechas(String comprador, String conyuge) {
    ObjectNode payload = payload();
    payload.put("fecha_nacimiento_conyuge_1", comprador);
    payload.put("fecha_nacimiento_conyuge_2", conyuge);
    return payload;
  }

  private static List<NotaryDtos.AlertaDto> calcular(ObjectNode payload) {
    return NotaryAlertas.calcular(payload, () -> OTORGAMIENTO, CON_SOPORTE);
  }

  private static List<String> codigos(List<NotaryDtos.AlertaDto> alertas) {
    return alertas.stream().map(NotaryDtos.AlertaDto::codigo).toList();
  }

  @Test
  void sesentaYCuatroNoAplicaYSesentaYCincoExactosSi() {
    assertThat(codigos(calcular(conFechas("21/08/1961", "21/08/1961")))).isEmpty();
    assertEquals(64, NotaryAlertas.edad(LocalDate.of(1961, 8, 21), OTORGAMIENTO));

    assertThat(codigos(calcular(conFechas("20/08/1961", "21/08/1961"))))
        .containsExactly(NotaryAlertas.TARIFA_TERCERA_EDAD, NotaryAlertas.IMPUESTOS_TERCERA_EDAD);
    assertEquals(65, NotaryAlertas.edad(LocalDate.of(1961, 8, 20), OTORGAMIENTO));
  }

  @Test
  void compradorMayorDisparaTarifaEImpuestosConSuNombre() {
    List<NotaryDtos.AlertaDto> alertas = calcular(conFechas("1950-03-15", "1990-01-01"));

    assertThat(codigos(alertas))
        .containsExactly(NotaryAlertas.TARIFA_TERCERA_EDAD, NotaryAlertas.IMPUESTOS_TERCERA_EDAD);
    NotaryDtos.AlertaDto tarifa = alertas.get(0);
    assertEquals(NotaryAlertas.NIVEL_AVISO, tarifa.nivel());
    assertEquals("Aplicar descuento de tercera edad en la tarifa notarial", tarifa.detalle());
    assertThat(tarifa.personas())
        .containsExactly(new NotaryDtos.AlertaPersonaDto(NotaryAlertas.ROL_COMPRADOR, "Ana Pérez"));
    assertEquals(
        "Considerar exoneración en la liquidación de impuestos y llenar el formulario correspondiente",
        alertas.get(1).detalle());
    assertThat(NotaryAlertas.conTarifaOImpuestos(alertas)).isTrue();
  }

  @Test
  void soloVendedorMayorDisparaSoloImpuestos() {
    ObjectNode payload = conFechas("1990-01-01", "1991-01-01");
    payload.put("fecha_nacimiento_vendedor", "10/05/1945");

    List<NotaryDtos.AlertaDto> alertas = calcular(payload);

    assertThat(codigos(alertas)).containsExactly(NotaryAlertas.IMPUESTOS_TERCERA_EDAD);
    assertThat(alertas.get(0).personas())
        .containsExactly(new NotaryDtos.AlertaPersonaDto(NotaryAlertas.ROL_VENDEDOR, "ACME S.A."));
  }

  @Test
  void vendedorSinFechaComoPersonaJuridicaNoAplicaNiSeReclama() {
    List<NotaryDtos.AlertaDto> alertas = calcular(conFechas("1990-01-01", "1991-01-01"));

    assertThat(alertas).isEmpty();
  }

  @Test
  void sinFechasSoloAvisaDatoNoDisponibleDeLosCompradores() {
    List<NotaryDtos.AlertaDto> alertas = calcular(payload());

    assertThat(codigos(alertas)).containsExactly(NotaryAlertas.DATO_EDAD_NO_DISPONIBLE);
    NotaryDtos.AlertaDto alerta = alertas.get(0);
    assertEquals(NotaryAlertas.NIVEL_INFO, alerta.nivel());
    assertEquals("No se pudo verificar la edad de: Ana Pérez, Luis Mora", alerta.detalle());
    assertThat(NotaryAlertas.conTarifaOImpuestos(alertas)).isFalse();
  }

  @Test
  void fechaIlegibleCuentaComoNoDisponibleTambienParaElVendedor() {
    ObjectNode payload = conFechas("1990-01-01", "31/02/1950");
    payload.put("fecha_nacimiento_vendedor", "ayer");

    List<NotaryDtos.AlertaDto> alertas = calcular(payload);

    assertThat(codigos(alertas)).containsExactly(NotaryAlertas.DATO_EDAD_NO_DISPONIBLE);
    assertEquals("No se pudo verificar la edad de: Luis Mora, ACME S.A.", alertas.get(0).detalle());
  }

  @Test
  void discapacidadBooleanaOSiDisparaTarifa() {
    ObjectNode payload = conFechas("1990-01-01", "1991-01-01");
    payload.put("discapacidad_conyuge_1", true);
    payload.put("discapacidad_conyuge_2", "SI");

    List<NotaryDtos.AlertaDto> alertas = calcular(payload);

    assertThat(codigos(alertas)).containsExactly(NotaryAlertas.TARIFA_DISCAPACIDAD);
    assertThat(alertas.get(0).personas()).hasSize(2);

    payload.put("discapacidad_conyuge_1", "NO");
    payload.put("discapacidad_conyuge_2", false);
    assertThat(calcular(payload)).isEmpty();
  }

  @Test
  void compradorSinConyugeNoInventaUnoSegundo() {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("nombre_conyuge_1", "Ana Pérez");

    assertEquals(
        "No se pudo verificar la edad de: Ana Pérez", calcular(payload).get(0).detalle());
  }

  @Test
  void sinFechasLegiblesNoBuscaLaFechaDeReferencia() {
    AtomicInteger consultas = new AtomicInteger();

    NotaryAlertas.calcular(
        payload(),
        () -> {
          consultas.incrementAndGet();
          return OTORGAMIENTO;
        },
        CON_SOPORTE);

    assertEquals(0, consultas.get());
  }

  @Test
  void payloadVacioOMigradoNoTieneAlertasDePersonas() {
    assertThat(NotaryAlertas.calcular(mapper.createObjectNode(), () -> OTORGAMIENTO, CON_SOPORTE))
        .isEmpty();
    assertThat(NotaryAlertas.calcular(null, () -> OTORGAMIENTO, CON_SOPORTE)).isEmpty();
  }

  @Test
  void sinSoporteDeIngresosAvisaFondosSinJuzgarSiAlcanzan() {
    List<String> sinSoporte = new java.util.ArrayList<>(List.of("CEDULA"));
    sinSoporte.add(null);

    List<NotaryDtos.AlertaDto> alertas =
        NotaryAlertas.calcular(conFechas("1990-01-01", "1991-01-01"), () -> OTORGAMIENTO, sinSoporte);

    assertThat(codigos(alertas)).containsExactly(NotaryAlertas.FONDOS_SIN_SOPORTE);
    NotaryDtos.AlertaDto fondos = alertas.get(0);
    assertEquals(NotaryAlertas.NIVEL_AVISO, fondos.nivel());
    assertEquals(
        "El bufete no adjuntó soportes de ingresos; verifique el origen de los fondos",
        fondos.detalle());
    assertThat(fondos.personas()).isEmpty();
    assertThat(NotaryAlertas.conTarifaOImpuestos(alertas)).isFalse();
  }

  @Test
  void fondosSinSoporteTambienSinPayloadNiDocumentos() {
    assertThat(codigos(NotaryAlertas.calcular(null, () -> OTORGAMIENTO, List.of())))
        .containsExactly(NotaryAlertas.FONDOS_SIN_SOPORTE);
  }
}
