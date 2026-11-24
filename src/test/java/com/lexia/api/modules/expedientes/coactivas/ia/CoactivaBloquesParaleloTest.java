package com.lexia.api.modules.expedientes.coactivas.ia;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaExpedienteAnalisisWorker.Paso2;
import com.lexia.api.modules.expedientes.coactivas.ia.CoactivaExpedienteAnalisisWorker.Previo;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService;
import com.lexia.api.modules.tenancy.TenantContext;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Paso 2 con bloques en paralelo: mismo diagnóstico, mismos errores y mismo tenant que en secuencial. */
class CoactivaBloquesParaleloTest {

  private static final Pattern MARCA = Pattern.compile("<<BLOQUE (\\d+)>>");
  private static final UUID ARCHIVO = UUID.randomUUID();
  private static final Previo PREVIO =
      new Previo(UUID.randomUUID(), UUID.randomUUID(), "{\"etapa_procesal_detectada\":\"OPI_EMITIDA\"}", Instant.now());

  @AfterEach
  void limpiar() {
    TenantContext.clear();
  }

  @Test
  void paraleloUneIgualQueSecuencial() {
    List<String> bloques = bloques(7);
    for (int vuelta = 0; vuelta < 5; vuelta++) {
      Paso2 secuencial = worker(1, new LlmFalso(20, Set.of(), Set.of())).llm(ARCHIVO, "reglas", bloques, PREVIO);
      Paso2 paralelo = worker(3, new LlmFalso(20, Set.of(), Set.of())).llm(ARCHIVO, "reglas", bloques, PREVIO);
      assertNull(secuencial.error());
      assertNotNull(secuencial.unido());
      assertEquals(secuencial, paralelo);
    }
  }

  @Test
  void soloElBloqueCeroLlevaHistorial() {
    LlmFalso llm = new LlmFalso(5, Set.of(), Set.of());
    worker(3, llm).llm(ARCHIVO, "reglas", bloques(4), PREVIO);

    assertEquals(4, llm.textos.size());
    llm.textos.forEach(
        (i, texto) -> assertEquals(i == 0, texto.contains("=== HISTORIAL DE DIAGN"), "bloque " + i));
  }

  @Test
  void nuncaHayMasDeNLlamadasSimultaneas() {
    LlmFalso llm = new LlmFalso(40, Set.of(), Set.of());
    worker(3, llm).llm(ARCHIVO, "reglas", bloques(9), PREVIO);

    assertTrue(llm.maxEnVuelo.get() <= 3, "en vuelo: " + llm.maxEnVuelo.get());
    assertTrue(llm.maxEnVuelo.get() > 1, "no corrió en paralelo");
  }

  @Test
  void unBloqueConErrorDaElMismoResultadoQueSecuencial() {
    List<String> bloques = bloques(6);
    for (Set<Integer> errores : List.of(Set.of(2), Set.of(4, 1), Set.of(0), Set.of(5))) {
      Paso2 secuencial = worker(1, new LlmFalso(15, errores, Set.of())).llm(ARCHIVO, "reglas", bloques, PREVIO);
      Paso2 paralelo = worker(3, new LlmFalso(15, errores, Set.of())).llm(ARCHIVO, "reglas", bloques, PREVIO);
      assertNotNull(secuencial.error());
      assertEquals(secuencial, paralelo, "errores en " + errores);
    }
    Paso2 secuencial = worker(1, new LlmFalso(15, Set.of(), Set.of(3))).llm(ARCHIVO, "reglas", bloques, PREVIO);
    Paso2 paralelo = worker(3, new LlmFalso(15, Set.of(), Set.of(3))).llm(ARCHIVO, "reglas", bloques, PREVIO);
    assertNotNull(secuencial.error());
    assertEquals(secuencial, paralelo, "JSON inválido en bloque 3");
  }

  @Test
  void cadaLlamadaVeElTenantDelJob() {
    UUID tenant = UUID.randomUUID();
    TenantContext.setTenantId(tenant);
    LlmFalso llm = new LlmFalso(10, Set.of(), Set.of());

    worker(3, llm).llm(ARCHIVO, "reglas", bloques(6), PREVIO);

    assertEquals(6, llm.tenants.size());
    llm.tenants.values().forEach(t -> assertEquals(tenant, t));
    assertEquals(tenant, TenantContext.getTenantId());
  }

  @Test
  void conUnoCorreEnElHiloDelJob() {
    LlmFalso llm = new LlmFalso(0, Set.of(), Set.of());
    worker(1, llm).llm(ARCHIVO, "reglas", bloques(3), PREVIO);

    llm.hilos.values().forEach(h -> assertEquals(Thread.currentThread().getName(), h));
    assertFalse(llm.hilos.containsValue("coactiva-llm-bloque"));
  }

  private static CoactivaExpedienteAnalisisWorker worker(int paralelo, AnalisisDocumentoService llm) {
    return new CoactivaExpedienteAnalisisWorker(
        null, null, null, null, null, null, null, llm, null,
        new CoactivaDiagnosticoParser(new ObjectMapper()),
        null, null, null, null, null, false, paralelo);
  }

  private static List<String> bloques(int n) {
    List<String> out = new ArrayList<>();
    for (int i = 0; i < n; i++) {
      out.add("<<BLOQUE " + i + ">> --- FOJA " + (i * 10 + 1) + " --- texto");
    }
    return out;
  }

  /**
   * Respuestas que chocan entre bloques: el orden de unión decide qué valor queda (primer valor útil,
   * orden de alertas y acciones, etapa más avanzada).
   */
  private static final class LlmFalso implements AnalisisDocumentoService {
    private static final String[] ETAPAS = {"OPI_EMITIDA", "MEDIDAS_CAUTELARES", "EMBARGO", "OPI_EMITIDA"};

    final int demoraMaxMs;
    final Set<Integer> errores;
    final Set<Integer> invalidos;
    final AtomicInteger enVuelo = new AtomicInteger();
    final AtomicInteger maxEnVuelo = new AtomicInteger();
    final Map<Integer, String> textos = new ConcurrentHashMap<>();
    final Map<Integer, UUID> tenants = new ConcurrentHashMap<>();
    final Map<Integer, String> hilos = new ConcurrentHashMap<>();

    LlmFalso(int demoraMaxMs, Set<Integer> errores, Set<Integer> invalidos) {
      this.demoraMaxMs = demoraMaxMs;
      this.errores = errores;
      this.invalidos = invalidos;
    }

    @Override
    public boolean isConfigured() {
      return true;
    }

    @Override
    public ValidacionDocumentoJson diagnosticarCoactiva(String systemPrompt, String texto) {
      int actual = enVuelo.incrementAndGet();
      maxEnVuelo.accumulateAndGet(actual, Math::max);
      try {
        Matcher m = MARCA.matcher(texto);
        assertTrue(m.find(), texto);
        int i = Integer.parseInt(m.group(1));
        textos.put(i, texto);
        hilos.put(i, Thread.currentThread().getName());
        UUID tenant = TenantContext.getTenantId();
        if (tenant != null) {
          tenants.put(i, tenant);
        }
        if (demoraMaxMs > 0) {
          Thread.sleep(ThreadLocalRandom.current().nextInt(demoraMaxMs + 1));
        }
        if (errores.contains(i)) {
          return ValidacionDocumentoJson.error("fallo LLM bloque " + i);
        }
        if (invalidos.contains(i)) {
          return ValidacionDocumentoJson.ok("esto no es json " + i);
        }
        return ValidacionDocumentoJson.ok(respuesta(i));
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return ValidacionDocumentoJson.error("interrumpido");
      } finally {
        enVuelo.decrementAndGet();
      }
    }

    private static String respuesta(int i) {
      String etapa = ETAPAS[i % ETAPAS.length];
      return """
          {"porcentaje_completitud": %d,
           "etapa_procesal_detectada": "%s",
           "etapa_sugerida": {"codigo": "%s", "justificacion_juridica": "bloque %d"},
           "documentos_identificados": [{"tipo": "DOC_%d", "presente": true, "foja_inicio": %d},
                                        {"tipo": "COMUN", "presente": %s, "foja_inicio": %d}],
           "alertas_inconsistencias": ["alerta %d", "alerta comun"],
           "acciones_sugeridas": [{"codigo_accion": "ACC_%d", "titulo": "Acción %d"},
                                  {"codigo_accion": "ACC_COMUN", "titulo": "Común desde %d"}],
           "datos_extraidos": {"numero_juicio_coactivo": "JUICIO-%d", "solo_%d": "v%d"},
           "siguiente_accion_sugerida": "siguiente %d"}
          """
          .formatted(
              10 * i, etapa, etapa, i, i, i * 10 + 1, i % 2 == 0, i * 10 + 2, i, i, i, i, i, i, i, i);
    }
  }
}
