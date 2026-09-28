package com.lexia.api.modules.ia.cotejo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoDtos.DocumentoOcrResultadoDTO;
import com.lexia.api.modules.expedientes.caso.ExpedienteDtos.DatosExtraidosDTO;
import com.lexia.api.modules.ia.cotejo.CotejoDtos.CotejoComparacion;
import com.lexia.api.modules.ia.cotejo.CotejoDtos.CotejoResponse;
import com.lexia.api.modules.ia.cotejo.CotejoMotor.Bloque;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionDocumento;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

class CotejoDatosServiceTest {

  @Test
  void parteElFormatoNativoDeLaCache() {
    String contenido =
        """
        CEDULA:
        :
        : Juan Carlos Pérez García
        Cédula 091.234.567-8

        MINUTA:
        :
        : Compareciente Juan Perez
        """;
    List<Bloque> bloques = CotejoMotor.parse(contenido);
    assertEquals(2, bloques.size());
    assertEquals("CEDULA", bloques.get(0).tipo());
    assertTrue(bloques.get(0).texto().contains("091.234.567-8"));
    assertEquals("MINUTA", bloques.get(1).tipo());
    assertTrue(bloques.get(1).texto().contains("Juan Perez"));
  }

  @Test
  void parteMarcadoresCuandoE03NoUsaElFormatoNativo() {
    String contenido =
        """
        [[DOC:Cédula]]
        texto cedula

        [[DOC:Escritura]]
        texto escritura
        """;
    List<Bloque> bloques = CotejoMotor.parse(contenido);
    assertEquals(2, bloques.size());
    assertEquals("Cédula", bloques.get(0).tipo());
    assertEquals("Escritura", bloques.get(1).tipo());
  }

  @Test
  void parteFormatoDocumentoDelFrontend() {
    String contenido =
        """
        === DOCUMENTO: Cédula ===
        Juan Carlos Pérez García
        Cédula 091.234.567-8

        === DOCUMENTO: Minuta ===
        Compareciente Juan Perez
        """;
    List<Bloque> bloques = CotejoMotor.parse(contenido);
    assertEquals(2, bloques.size());
    assertEquals("Cédula", bloques.get(0).tipo());
    assertTrue(bloques.get(0).texto().contains("091.234.567-8"));
    assertEquals("Minuta", bloques.get(1).tipo());
    assertTrue(bloques.get(1).texto().contains("Juan Perez"));
  }

  @Test
  void normalizaAcentosEspaciosCedulaFechasYMontos() {
    assertEquals("juan perez", CotejoMotor.fold("  Juan   Pérez "));
    assertEquals("0912345678", CotejoMotor.comparable("identificacion", "091.234.567-8"));
    assertEquals("0912345678", CotejoMotor.comparable("identificacion", "0912345678"));
    assertEquals("2026-10-12", CotejoMotor.comparable("vigencia", "Vigente hasta 12 oct. 2026"));
    assertEquals("2026-10-12", CotejoMotor.comparable("vigencia", "12/10/2026"));
    assertEquals("1250", CotejoMotor.comparable("avaluo", "USD 1.250,00"));
    assertEquals("1250", CotejoMotor.comparable("montos", "1250.00"));
  }

  @Test
  void coincideDiferenciaYNoEncontradoSinDatosFijos() {
    List<CotejoMotor.DocCampos> docs =
        List.of(
            campos(
                "Cédula",
                Map.of(
                    "Nombres y apellidos", "Juan Carlos Pérez García",
                    "Cédula", "091.234.567-8",
                    "Vigencia documental", "12 oct. 2026")),
            campos(
                "Minuta",
                Map.of(
                    "nombre completo", "juan carlos perez garcia",
                    "numero de identificacion", "0912345678",
                    "Linderos del inmueble", "Norte: vía principal")),
            campos(
                "Avalúo",
                Map.of(
                    "nombres", "JUAN CARLOS PEREZ GARCIA",
                    "identificacion", "0912345678",
                    "linderos", "Norte: calle secundaria",
                    "Avalúo", "1.250,00")));

    List<CotejoComparacion> filas = CotejoMotor.comparar(docs);
    assertEquals(CotejoMotor.COINCIDE, estado(filas, "nombres"));
    assertEquals("Juan Carlos Pérez García", valor(filas, "nombres"));
    assertEquals(CotejoMotor.COINCIDE, estado(filas, "identificacion"));
    assertEquals(CotejoMotor.DIFERENCIA, estado(filas, "linderos"));
    assertNull(valor(filas, "linderos"));
    assertEquals("Cédula ↔ Minuta ↔ Avalúo", filas.get(0).relacion());
    assertEquals(CotejoMotor.NO_ENCONTRADO, estado(filas, "vigencia"));
    assertEquals(CotejoMotor.NO_ENCONTRADO, estado(filas, "avaluo"));
    assertTrue(filas.stream().noneMatch(f -> "resumen".equals(f.campo())));
  }

  @Test
  void omiteClavesQueSoloAparecenEnUnDocumento() {
    List<CotejoMotor.DocCampos> docs =
        List.of(
            campos("Cédula", Map.of("huella", "ABC")),
            campos("Minuta", Map.of("sello", "XYZ")));
    assertTrue(CotejoMotor.comparar(docs).isEmpty());
  }

  @Test
  void cotejarLeeElTextoDeE04YNoInventaCampos() {
    String contenido =
        """
        CEDULA:
        :
        : documento de identidad

        ESCRITURA:
        :
        : escritura publica
        """;
    AtomicReference<String> sessionPedida = new AtomicReference<>();
    CotejoDatosService service =
        new CotejoDatosService(
            id -> {
              sessionPedida.set(id);
              return contenido;
            },
            ia((texto, tipo) -> {
              if ("CEDULA".equals(tipo)) {
                return ExtraccionDocumento.fromDatos(
                    new DatosExtraidosDTO(
                        "CEDULA",
                        "identidad",
                        Map.of("nombre", "Ana López", "cédula", "0102030405")));
              }
              return ExtraccionDocumento.fromDatos(
                  new DatosExtraidosDTO(
                      "ESCRITURA",
                      "contrato",
                      Map.of("nombres y apellidos", "Ana Lopez", "identificacion", "0102030405")));
            }));

    CotejoResponse respuesta = service.cotejar("exp-42");
    assertEquals("exp-42", sessionPedida.get());
    assertEquals("exp-42", respuesta.sessionId());
    assertEquals(2, respuesta.resumen().coinciden());
    assertEquals(0, respuesta.resumen().diferencias());
    assertEquals(2, respuesta.resumen().reglasAplicadas());
    assertEquals(CotejoMotor.COINCIDE, estado(respuesta.comparaciones(), "nombres"));
    assertEquals("Cédula ↔ Escritura", respuesta.comparaciones().get(0).relacion());
  }

  @Test
  void sinDosDocumentosDevuelveCotejoVacio() {
    CotejoDatosService service =
        new CotejoDatosService(
            id -> "un solo bloque sin marcadores",
            ia((texto, tipo) -> {
              throw new AssertionError("no debe llamar a la IA");
            }));
    CotejoResponse respuesta = service.cotejar("s1");
    assertTrue(respuesta.comparaciones().isEmpty());
    assertEquals(0, respuesta.resumen().total());
  }

  @Test
  void cotejaLosArchivosYaExtraidosSinVolverALlamarIaNiCache() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    String cedula =
        mapper.writeValueAsString(
            new DatosExtraidosDTO(
                "CEDULA", "", Map.of("nombre", "Ana López", "cédula", "0102030405")));
    String papeleta =
        mapper.writeValueAsString(
            new DatosExtraidosDTO(
                "PAPELETA",
                "",
                Map.of("nombres y apellidos", "Ana Lopez", "identificacion", "0102030405")));
    CotejoDatosService service =
        new CotejoDatosService(
            id -> {
              throw new AssertionError("no debe leer la caché E04");
            },
            ia(
                (texto, tipo) -> {
                  throw new AssertionError("no debe volver a llamar a la IA");
                }),
            id ->
                List.of(
                    new DocumentoOcrResultadoDTO(
                        "d1", "cedula.png", "CEDULA", "ocr", cedula, "LEGIBLE", null, 2),
                    new DocumentoOcrResultadoDTO(
                        "d2", "papeleta.png", "PAPELETA", "ocr", papeleta, "LEGIBLE", null, 2)),
            mapper);

    CotejoResponse respuesta = service.cotejar("exp-1");
    assertEquals(2, respuesta.resumen().coinciden());
    assertEquals(0, respuesta.resumen().diferencias());
    assertEquals("cedula.png ↔ papeleta.png", respuesta.comparaciones().get(0).relacion());
    assertEquals(CotejoMotor.COINCIDE, estado(respuesta.comparaciones(), "nombres"));
    assertEquals(CotejoMotor.COINCIDE, estado(respuesta.comparaciones(), "identificacion"));
  }

  @Test
  void fallaSiLaIaNoEstructuraNingunDocumento() {
    String contenido =
        """
        [[DOC:Cédula]]
        a

        [[DOC:Minuta]]
        b
        """;
    CotejoDatosService service =
        new CotejoDatosService(
            id -> contenido, ia((texto, tipo) -> ExtraccionDocumento.error("sin proveedor")));
    assertThrows(ApiException.class, () -> service.cotejar("s2"));
  }

  private static CotejoMotor.DocCampos campos(String tipo, Map<String, String> datos) {
    Map<String, Object> clave = new LinkedHashMap<>();
    clave.putAll(datos);
    return CotejoMotor.desdeExtraccion(tipo, new DatosExtraidosDTO(tipo, "", clave));
  }

  private static String estado(List<CotejoComparacion> filas, String campo) {
    return filas.stream()
        .filter(f -> campo.equals(f.campo()))
        .findFirst()
        .orElseThrow()
        .estado();
  }

  private static String valor(List<CotejoComparacion> filas, String campo) {
    return filas.stream()
        .filter(f -> campo.equals(f.campo()))
        .findFirst()
        .orElseThrow()
        .valor();
  }

  private static AnalisisDocumentoService ia(
      BiFunction<String, String, ExtraccionDocumento> extraer) {
    return new AnalisisDocumentoService() {
      @Override
      public boolean isConfigured() {
        return true;
      }

      @Override
      public ExtraccionDocumento extraerDatosClave(String textoOcr, String tipoDocumento) {
        return extraer.apply(textoOcr, tipoDocumento);
      }
    };
  }
}
