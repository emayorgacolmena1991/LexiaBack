package com.lexia.api.modules.expedientes.coactivas.actuacion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.Config;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.Fuentes;
import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaDataMapper.PlantillaDatos;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaDelegado;
import com.lexia.api.modules.expedientes.coactivas.delegados.CoactivaOficina;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaExpediente;
import com.lexia.api.modules.expedientes.coactivas.expediente.CoactivaParticipante;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoactivaPlantillaDataMapperTest {

  static final UUID TENANT = UUID.randomUUID();
  static final ZonedDateTime AHORA =
      ZonedDateTime.of(2026, 10, 5, 11, 56, 0, 0, ZoneId.of("America/Guayaquil"));

  @Test
  void estructuradoTienePrioridadYSeRegistraLaDiscrepancia() {
    Map<String, Object> ocr = ocrCompleto();
    ocr.put("deudor", "el señor JUAN CARLOS PEREZ LOPEZ");
    ocr.put("cedula_deudor_principal", "0999999999");

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocr), AHORA);

    assertEquals("JUAN CARLOS PEREZ LOPEZ", datos.valores().get("nombre_deudor_principal"));
    assertEquals("1310000001", datos.valores().get("cedula_deudor_principal"));
    assertEquals(
        List.of(
            "cedula_deudor_principal",
            "cedula_deudor_principal_1",
            "cedula_deudor_1",
            "cedula_receptor"),
        datos.discrepancias());
    assertEquals("025-2024-00026", datos.valores().get("numero_juicio_coactivo"));
    assertEquals("juan.perez@mail.com", datos.valores().get("correo_notificacion_deudor"));
    assertEquals("MARIA DELEGADA", datos.valores().get("nombre_funcionario_coactiva"));
    assertEquals("BE-GG-2024-001", datos.valores().get("numero_resolucion_delegacion"));
    assertEquals("12 de marzo de 2024", datos.valores().get("fecha_resolucion_delegacion"));
    assertEquals("Portoviejo", datos.valores().get("ciudad_actuacion"));
    assertEquals("4", datos.valores().get("numero_zonal"));
    assertEquals("5 de octubre de 2026", datos.valores().get("fecha_actuacion"));
    assertEquals("11h56", datos.valores().get("hora_actuacion"));
  }

  @Test
  void ocrCompletaLoQueNoEstaEstructurado() {
    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocrCompleto()), AHORA);

    assertEquals("PEDRO GARANTE", datos.valores().get("nombre_garante_solidario"));
    assertEquals("1712345678", datos.valores().get("cedula_garante_solidario"));
    assertEquals("LUIS DEPOSITARIO", datos.valores().get("nombre_depositario_judicial"));
    assertEquals("Banco Pichincha C.A.", datos.valores().get("banco_embargado"));
    assertEquals("ANA GERENTE", datos.valores().get("nombre_gerente_general"));
  }

  @Test
  void cuentasSeAsignanPorTitularYMontosEnLetras() {
    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocrCompleto()), AHORA);
    Map<String, Object> v = datos.valores();

    assertEquals("2200111111", v.get("numero_cuenta_1"));
    assertEquals("2200222222", v.get("numero_cuenta_2"));
    assertEquals("4400333333", v.get("numero_cuenta_3"));
    assertEquals("1,250.50", v.get("monto_embargo_1"));
    assertEquals("MIL DOSCIENTOS CINCUENTA CON 50/100", v.get("monto_embargo_1_letras"));
    assertEquals("300.00", v.get("monto_embargo_2"));
    assertEquals("TRESCIENTOS", v.get("monto_embargo_2_letras"));
    assertEquals("15,000.00", v.get("monto_deuda_total"));
    assertEquals("QUINCE MIL", v.get("monto_deuda_total_letras"));
  }

  @Test
  void sinFuenteLaVariableQuedaFaltanteYNoSeInventa() {
    CoactivaExpediente exp = expediente();
    Fuentes f =
        new Fuentes(exp, List.of(), null, null, null, null, new Config("", "", "", Map.of()), null);

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(f, AHORA);

    assertFalse(datos.analisisDisponible());
    assertFalse(datos.valores().containsKey("nombre_garante_solidario"));
    assertFalse(datos.valores().containsKey("monto_embargo_1"));
    assertFalse(datos.valores().containsKey("monto_embargo_1_letras"));
    assertTrue(datos.valores().values().stream().noneMatch(x -> x.toString().contains("pendiente")));
    assertEquals(
        List.of("nombre_garante_solidario", "numero_cuenta_1"),
        datos.faltantes(List.of("numero_juicio_coactivo", "nombre_garante_solidario", "numero_cuenta_1")));
  }

  @Test
  void cuentaSinTitularIdentificableNoSeAsigna() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put(
        "cuentas_embargadas",
        List.of(Map.of("numero_cuenta", "999", "monto_retenido", 10), Map.of("numero_cuenta", "888", "titular", "GARANTE SOLIDARIO", "monto_retenido", 5)));

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocr), AHORA);

    assertNull(datos.valores().get("numero_cuenta_1"));
    assertEquals("888", datos.valores().get("numero_cuenta_3"));
  }

  @Test
  void titularPorNombreDelParticipante() {
    Fuentes base = fuentes(new LinkedHashMap<>());
    CoactivaParticipante garante =
        CoactivaParticipante.create(TENANT, base.expediente().getId(), "GARANTE", 2, "Pedro Rosales", "MANUAL");
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("cuentas_embargadas", List.of(Map.of("numero_cuenta", "777", "titular", "PEDRO ROSALES", "monto_retenido", 5)));
    Fuentes f =
        new Fuentes(
            base.expediente(),
            List.of(base.participantes().get(0), garante),
            ocr,
            base.oficina(),
            base.delegado(),
            base.secretarioNombre(),
            base.config(),
            null);

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(f, AHORA);

    assertEquals("777", datos.valores().get("numero_cuenta_3"));
    assertEquals("Pedro Rosales", datos.valores().get("nombre_garante_solidario"));
  }

  @Test
  void aliasCubreEtiquetasDeLasPlantillasNuevas() {
    Map<String, Object> ocr = ocrCompleto();
    ocr.put("fecha_embargo", "27 de abril del 2026");
    ocr.put("fecha_liquidacion", "27 de abril de 2026");
    ocr.put("valor_embargo_num", "155.50");

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocr), AHORA);
    Map<String, Object> v = datos.valores();

    assertEquals("JUAN CARLOS PEREZ LOPEZ", v.get("nombre_deudor_1"));
    assertEquals("025-2024-00026", v.get("numero_juicio"));
    assertEquals("025-2024-00026", v.get("numero_proceso_coactivo"));
    assertEquals("Portoviejo", v.get("ciudad"));
    assertEquals("4", v.get("numero_zona"));
    assertEquals("PORTOVIEJO", v.get("zona"));
    assertEquals("15,000.00", v.get("monto_liquidacion"));
    assertEquals("CARLOS SECRETARIO", v.get("abogado_secretario"));
    assertEquals("MARIA DELEGADA", v.get("funcionario_coactiva"));
    assertEquals("PEDRO GARANTE", v.get("nombre_garante_1"));
    assertEquals("1", v.get("foja_inicio_numero"));
    assertEquals("120", v.get("foja_fin_numero"));
    assertEquals("ciento veinte", v.get("foja_fin_texto"));
    assertEquals("Banecuador B.P.", v.get("nombre_institucion_bancaria"));
    assertEquals("5", v.get("dia_entrega"));
    assertEquals("octubre", v.get("mes_entrega"));
    assertEquals("2026", v.get("anio_entrega"));
    assertEquals("27", v.get("dia_embargo"));
    assertEquals("abril", v.get("mes_embargo"));
    assertEquals("2026", v.get("anio_embargo"));
    assertEquals("27 de abril de 2026", v.get("fecha_liquidacion_actualizada"));
    assertEquals("2200111111", v.get("numero_cuenta_retencion"));
    assertEquals("172.50", v.get("valor_honorarios_total_num"));
    assertEquals("150.00", v.get("valor_honorarios_subtotal"));
    assertEquals("22.50", v.get("valor_iva_honorarios"));
    assertEquals("CIENTO CINCUENTA Y CINCO CON 50/100", v.get("valor_embargo_texto"));
    assertEquals("JUAN CARLOS PEREZ LOPEZ", v.get("nombre_receptor"));
  }

  @Test
  void limpiaTratamientosDeLosNombres() {
    assertEquals("Juan Pérez", CoactivaPlantillaDataMapper.limpiarNombre("el/la señor(a) Juan Pérez"));
    assertEquals("Ana Ruiz", CoactivaPlantillaDataMapper.limpiarNombre("Abg. Ana Ruiz"));
    assertEquals("ANA GERENTE", CoactivaPlantillaDataMapper.limpiarNombre("Mgs. ANA GERENTE"));
    assertEquals("Rosa Mena", CoactivaPlantillaDataMapper.limpiarNombre("Sra. Rosa Mena"));
    assertNull(CoactivaPlantillaDataMapper.limpiarNombre("  "));
  }

  @Test
  void montosInvalidosNoSeUsan() {
    assertNull(CoactivaPlantillaDataMapper.monto("no consta"));
    assertNull(CoactivaPlantillaDataMapper.monto(0));
    assertEquals(new BigDecimal("1234.50"), CoactivaPlantillaDataMapper.monto("$ 1.234,50"));
  }

  @Test
  void datosExtraidosAnidadosYAliasesLleganATagsDePlantilla() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("juicio", "025-2024-00099");
    ocr.put("monto_mora", 12_500);
    ocr.put(
        "deudor",
        Map.of("nombre", "el señor ANA DEUDORA", "cedula", "1711111111", "correo", "ana@mail.com"));
    ocr.put("garante", Map.of("nombre", "PEDRO GARANTE", "cedula", "1722222222"));
    ocr.put("liquidacion", Map.of("fecha", "5 de octubre de 2026", "total", 12_500));
    ocr.put("honorarios", Map.of("monto", "172.50", "cuenta", "3001234567"));
    ocr.put(
        "cuentas_embargadas",
        List.of(Map.of("numero_cuenta", "2200111111", "titular", "DEUDOR", "monto_retenido", 100)));

    // Sin juicio/participantes estructurados: los grupos anidados del OCR deben poblar los tags.
    Fuentes base = fuentes(ocr);
    CoactivaExpediente expSinJuicio =
        CoactivaExpediente.create(TENANT, UUID.randomUUID(), null, null);
    expSinJuicio.setDatosBase(null, 2024, "PORTOVIEJO", 120);
    Fuentes soloOcr =
        new Fuentes(
            expSinJuicio,
            List.of(),
            ocr,
            base.oficina(),
            base.delegado(),
            base.secretarioNombre(),
            base.config(),
            null);
    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(soloOcr, AHORA);
    Map<String, Object> v = datos.valores();

    assertEquals("025-2024-00099", v.get("numero_juicio_coactivo"));
    assertEquals("025-2024-00099", v.get("numero_juicio"));
    assertEquals("ANA DEUDORA", v.get("nombre_deudor_1"));
    assertEquals("1711111111", v.get("cedula_deudor_principal"));
    assertEquals("ana@mail.com", v.get("correo_notificacion_1"));
    assertEquals("PEDRO GARANTE", v.get("nombre_garante_1"));
    assertEquals("1722222222", v.get("cedula_garante_1"));
    assertEquals("12,500.00", v.get("monto_deuda_total"));
    assertEquals("12,500.00", v.get("monto_liquidacion"));
    assertEquals("5 de octubre de 2026", v.get("fecha_liquidacion"));
    assertEquals("5 de octubre de 2026", v.get("fecha_liquidacion_actualizada"));
    assertEquals("172.50", v.get("monto_honorarios"));
    assertEquals("172.50", v.get("valor_honorarios_total_num"));
    assertEquals("3001234567", v.get("cuenta_honorarios_abogado"));
    assertEquals("2200111111", v.get("numero_cuenta_1"));
    assertEquals("Delegada", v.get("titulo_delegado"));
  }

  @Test
  void fechaActaNoSeSustituyePorLaFechaDeActuacion() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("fecha_acta", "3 de marzo de 2024");
    ocr.put("fecha_auto_designacion", "1 de febrero de 2024");

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocr), AHORA);

    assertEquals("3 de marzo de 2024", datos.valores().get("fecha_acta"));
    assertEquals("1 de febrero de 2024", datos.valores().get("fecha_auto_designacion"));
    assertEquals("5 de octubre de 2026", datos.valores().get("fecha_actuacion"));
    assertEquals("5 de octubre de 2026", datos.valores().get("fecha_providencia"));
  }

  @Test
  void fechaActaDelSistemaNoDerivaElAutoNiPisaElOcrDelAuto() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("fecha_acta", "9 de septiembre de 2020");
    ocr.put("fecha_auto_designacion", "1 de febrero de 2024");
    Fuentes base = fuentes(ocr);
    Fuentes conActa =
        new Fuentes(
            base.expediente(),
            base.participantes(),
            ocr,
            base.oficina(),
            base.delegado(),
            base.secretarioNombre(),
            base.config(),
            LocalDate.of(2024, 3, 3));

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(conActa, AHORA);

    assertEquals("3 de marzo de 2024", datos.valores().get("fecha_acta"));
    assertEquals("1 de febrero de 2024", datos.valores().get("fecha_auto_designacion"));
    assertFalse(datos.esDeOcr("fecha_acta"));
    assertTrue(datos.esDeOcr("fecha_auto_designacion"));
  }

  @Test
  void montoOriginalPrevaleceYElAliasHeredaLaDiscrepancia() {
    Fuentes f = fuentes(ocrCompleto());
    f.expediente().setMontoOriginal(new BigDecimal("14000.00"));

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(f, AHORA);

    assertEquals("14,000.00", datos.valores().get("monto_deuda_total"));
    assertEquals("14,000.00", datos.valores().get("monto_liquidacion"));
    assertTrue(datos.discrepancias().contains("monto_deuda_total"));
    assertTrue(datos.discrepancias().contains("monto_liquidacion"));
  }

  @Test
  void tituloDistintoQuedaEnDiscrepancias() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("titulo_delegado", "Juez coactivo");

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocr), AHORA);

    assertEquals("Delegada", datos.valores().get("titulo_delegado"));
    assertTrue(datos.discrepancias().contains("titulo_delegado"));
  }

  @Test
  void honorariosConservaElDesgloseDelOcr() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("monto_honorarios", "112.00");
    ocr.put("valor_honorarios_subtotal", "100.00");
    ocr.put("valor_iva_honorarios", "12.00");

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocr), AHORA);
    Map<String, Object> v = datos.valores();

    assertEquals("112.00", v.get("monto_honorarios"));
    assertEquals("100.00", v.get("valor_honorarios_subtotal"));
    assertEquals("12.00", v.get("valor_iva_honorarios"));
    assertEquals("100.00", v.get("valor"));
    assertEquals("12.00", v.get("iva"));
  }

  @Test
  void claveExtraibleColapsaAliasYOmiteLoQueFijaElSistema() {
    assertEquals("nombre_deudor_principal", CoactivaPlantillaDataMapper.claveExtraible("nombre_deudor_1"));
    assertEquals("monto_embargo_total", CoactivaPlantillaDataMapper.claveExtraible("valor_embargo_num"));
    assertEquals("monto_honorarios", CoactivaPlantillaDataMapper.claveExtraible("valor"));
    assertEquals("fecha_acta", CoactivaPlantillaDataMapper.claveExtraible("fecha_acta"));
    assertEquals("titulo_depositario", CoactivaPlantillaDataMapper.claveExtraible("titulo_depositario"));
    assertEquals("titulo_secretario", CoactivaPlantillaDataMapper.claveExtraible("titulo_secretario"));
    assertNull(CoactivaPlantillaDataMapper.claveExtraible("titulo_delegado"));
    assertNull(CoactivaPlantillaDataMapper.claveExtraible("fecha_providencia"));
    assertEquals(
        "cedula_deudor_principal, fecha_acta, titulo_depositario, titulo_secretario",
        CoactivaPlantillaDataMapper.textoExtraccion(
            "[\"cedula_deudor_1\",\"fecha_providencia\",\"fecha_acta\",\"titulo_delegado\",\"titulo_depositario\",\"titulo_secretario\",\"cedula_deudor_principal\"]"));
  }

  @Test
  void numeroZonaUsaAliasOcrSiElSistemaNoLoTieneYNoLoPisaSiLoTiene() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("numero_zona", "7");
    Fuentes base = fuentes(ocr);
    Fuentes sinZonal =
        new Fuentes(
            base.expediente(),
            base.participantes(),
            ocr,
            base.oficina(),
            base.delegado(),
            base.secretarioNombre(),
            new Config("", "", "", Map.of()),
            null);

    PlantillaDatos sinSistema = CoactivaPlantillaDataMapper.construir(sinZonal, AHORA);
    assertEquals("7", sinSistema.valores().get("numero_zona"));
    assertEquals("7", sinSistema.valores().get("numero_zonal"));
    assertTrue(sinSistema.esDeOcr("numero_zona"));

    PlantillaDatos conSistema = CoactivaPlantillaDataMapper.construir(base, AHORA);
    assertEquals("4", conSistema.valores().get("numero_zona"));
    assertEquals("4", conSistema.valores().get("numero_zonal"));
    assertFalse(conSistema.esDeOcr("numero_zonal"));
  }

  @Test
  void depositarioJudicialSaleDeLosAliasYDelTituloAnidado() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put(
        "depositario",
        Map.of(
            "nombre_depositario", "el señor LUIS DEPOSITARIO",
            "cedula_depositario", "091234567-8",
            "titulo", "Abg."));
    ocr.put("titulo_secretario", "Lcda.");

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(ocr), AHORA);

    assertEquals("LUIS DEPOSITARIO", datos.valores().get("nombre_depositario_judicial"));
    assertEquals("LUIS DEPOSITARIO", datos.valores().get("nombre_depositario"));
    assertEquals("0912345678", datos.valores().get("cedula_depositario_judicial"));
    assertEquals("0912345678", datos.valores().get("cedula_depositario"));
    assertEquals("Abg.", datos.valores().get("titulo_depositario"));
    assertEquals("Lcda.", datos.valores().get("titulo_secretario"));
  }

  @Test
  void nombreSecretarioUsaOcrSoloSiNoHaySae() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("nombre_secretario", "Abg. ANA SECRETARIA");
    Fuentes base = fuentes(ocr);
    Fuentes sinSae =
        new Fuentes(
            base.expediente(),
            base.participantes(),
            ocr,
            base.oficina(),
            base.delegado(),
            null,
            base.config(),
            null);

    PlantillaDatos sinAsignacion = CoactivaPlantillaDataMapper.construir(sinSae, AHORA);
    assertEquals("ANA SECRETARIA", sinAsignacion.valores().get("nombre_secretario"));
    assertEquals("ANA SECRETARIA", sinAsignacion.valores().get("nombre_abogado_secretario"));

    PlantillaDatos conSae = CoactivaPlantillaDataMapper.construir(base, AHORA);
    assertEquals("CARLOS SECRETARIO", conSae.valores().get("nombre_secretario"));
    assertFalse(conSae.esDeOcr("nombre_secretario"));
  }

  @Test
  void normalizaClaveIaYNoCopiaNodata() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("NUMERO_OPERACION", "OP-99");
    raw.put("NombreDepositarioJudicial", "el señor LUIS DEPOSITARIO");
    raw.put("montoHonorarios", "nodata");
    raw.put("cedulaDepositario", null);

    Map<String, Object> n = CoactivaPlantillaDataMapper.normalizarExtraidos(raw);
    assertEquals("OP-99", n.get("numero_operacion"));
    assertEquals("el señor LUIS DEPOSITARIO", n.get("nombre_depositario_judicial"));
    assertFalse(n.containsKey("monto_honorarios"));
    assertFalse(n.containsValue("nodata"));

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(fuentes(raw), AHORA);
    assertEquals("OP-778899", datos.valores().get("NUMERO_OPERACION"));
    assertEquals("LUIS DEPOSITARIO", datos.valores().get("nombre_depositario_judicial"));
    assertFalse(datos.valores().containsValue("nodata"));
    assertTrue(datos.valores().values().stream().noneMatch(v -> v == null));
  }

  @Test
  void normalizarExtraidosNoPierdeListasNiInventaClavesAmbiguas() {
    Map<String, Object> raw = new LinkedHashMap<>();
    raw.put("monto_mora", 100);
    raw.put("liquidacion", Map.of("fecha", "1/10/2026", "total", 100));
    raw.put("otro", Map.of("total", 999, "nombre_embargado", "X"));
    raw.put("cuentas_embargadas", List.of(Map.of("numero_cuenta", "1")));

    Map<String, Object> n = CoactivaPlantillaDataMapper.normalizarExtraidos(raw);

    assertEquals(100, n.get("monto_deuda_total"));
    assertEquals("1/10/2026", n.get("fecha_liquidacion"));
    assertEquals(100, n.get("monto_liquidacion"));
    assertFalse(n.containsKey("total"), "no aplastar liquidacion.total ni otro.total como tag total");
    assertEquals("X", n.get("nombre_embargado"));
    assertTrue(n.get("cuentas_embargadas") instanceof List);
  }

  @Test
  void actaEmbargoChequeUsaDatosEstructuradosYOcrAnidado() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("acta", Map.of("fecha", "3 de marzo de 2024"));
    ocr.put("auto_designacion", Map.of("fecha", "1 de febrero de 2024"));
    ocr.put(
        "depositario_judicial",
        Map.of("nombre", "el señor LUIS DEPOSITARIO", "cedula", "0912345678", "titulo", "Abg."));
    ocr.put("secretario", Map.of("nombre", "Abg. ANA SECRETARIA", "titulo", "Lcda."));
    Fuentes base = fuentes(ocr);
    Fuentes sinSae =
        new Fuentes(
            base.expediente(),
            base.participantes(),
            ocr,
            base.oficina(),
            base.delegado(),
            null,
            base.config(),
            null);

    PlantillaDatos datos = CoactivaPlantillaDataMapper.construir(sinSae, AHORA);
    Map<String, Object> v = datos.valores();

    assertEquals("0912345678", v.get("cedula_depositario"));
    assertEquals("Portoviejo", v.get("ciudad"));
    assertEquals("3 de marzo de 2024", v.get("fecha_acta"));
    assertEquals("1 de febrero de 2024", v.get("fecha_auto_designacion"));
    assertEquals("MARIA DELEGADA", v.get("nombre_delegado"));
    assertEquals("LUIS DEPOSITARIO", v.get("nombre_depositario"));
    assertEquals("ANA SECRETARIA", v.get("nombre_secretario"));
    assertEquals("025-2024-00026", v.get("numero_proceso"));
    assertEquals("4", v.get("numero_zona"));
    assertEquals("Delegada", v.get("titulo_delegado"));
    assertEquals("Abg.", v.get("titulo_depositario"));
    assertEquals("Lcda.", v.get("titulo_secretario"));
    assertEquals("PORTOVIEJO", v.get("zona"));
  }

  // ---------------------------------------------------------------------------

  static Fuentes fuentes(Map<String, Object> ocr) {
    CoactivaExpediente exp = expediente();
    CoactivaParticipante deudor =
        CoactivaParticipante.create(TENANT, exp.getId(), "DEUDOR", 1, "JUAN CARLOS PEREZ LOPEZ", "MANUAL");
    deudor.update(
        "DEUDOR", 1, "NATURAL", "CEDULA", "1310000001", "JUAN CARLOS PEREZ LOPEZ",
        "Juan.Perez@mail.com; otro@mail.com", null, null, true);
    CoactivaDelegado delegado = CoactivaDelegado.create(TENANT, "MARIA DELEGADA", null);
    delegado.update(
        "MARIA DELEGADA", null, "Delegada", "BE-GG-2024-001", LocalDate.of(2024, 3, 12),
        null, null, "maria.delegada@banecuador.fin.ec", true, null);
    CoactivaOficina oficina = CoactivaOficina.create(TENANT, "PORTOVIEJO", "Portoviejo", "Manabí", 1);
    return new Fuentes(
        exp,
        List.of(deudor),
        ocr,
        oficina,
        delegado,
        "CARLOS SECRETARIO",
        new Config(
            "",
            "coactiva@banecuador.fin.ec",
            "estudio@juridico.ec",
            CoactivaPlantillaDataMapper.parsearZonales("PORTOVIEJO=4")),
        null);
  }

  static CoactivaExpediente expediente() {
    CoactivaExpediente exp = CoactivaExpediente.create(TENANT, UUID.randomUUID(), "025-2024-00026", null);
    exp.setDatosBase("OP-778899", 2024, "PORTOVIEJO", 120);
    return exp;
  }

  static Map<String, Object> ocrCompleto() {
    Map<String, Object> ocr = new LinkedHashMap<>();
    ocr.put("juicio", "25-2024-26");
    ocr.put("operacion", "OP-778899");
    ocr.put("deudor", "JUAN CARLOS PEREZ LOPEZ");
    ocr.put("nombre_garante_solidario", "el señor PEDRO GARANTE");
    ocr.put("cedula_garante_solidario", "171234567-8");
    ocr.put("nombre_depositario_judicial", "LUIS DEPOSITARIO");
    ocr.put("cedula_depositario_judicial", "0912345678");
    ocr.put("nombre_gerente_general", "Mgs. ANA GERENTE");
    ocr.put("monto_deuda_total", 15000);
    ocr.put("monto_honorarios", "172.50");
    ocr.put("cuenta_honorarios_abogado", "3001234567");
    ocr.put("banco_embargado", "Banco Pichincha C.A.");
    ocr.put(
        "cuentas_embargadas",
        List.of(
            Map.of("numero_cuenta", "2200111111", "tipo", "CORRIENTE", "titular", "DEUDOR", "monto_retenido", 1250.5),
            Map.of("numero_cuenta", "2200222222", "tipo", "CORRIENTE", "titular", "DEUDOR", "monto_retenido", "300"),
            Map.of("numero_cuenta", "4400333333", "tipo", "AHORROS", "titular", "GARANTE", "monto_retenido", 80)));
    return ocr;
  }
}
