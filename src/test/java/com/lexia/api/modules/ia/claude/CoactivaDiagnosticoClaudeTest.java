package com.lexia.api.modules.ia.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ValidacionDocumentoJson;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

/** Diagnóstico coactivo sin red: stop_reason max_tokens no se guarda; hay un solo reintento breve. */
class CoactivaDiagnosticoClaudeTest {

  private static final String CORTADA =
      """
      {"etapa_sugerida":{"codigo":"EMBARGO","justificacion_juridica":"texto cortado a la mitad","evidencia":{"extracto_texto":"cita incompleta"}}}
      """;

  private static final String BREVE =
      """
      {"etapa_sugerida":{"codigo":"EMBARGO","justificacion_juridica":"El expediente se encuentra en Embargo porque se identificó un oficio de retención del 2 de marzo de 2022.","evidencia":{"documento_origen":"Oficio de embargo","foja_pagina":12,"fecha_documento":"2022-03-02","extracto_texto":"Se ordena la retención de fondos"}}}
      """;

  private final ObjectMapper json = new ObjectMapper();

  @Test
  void respuestaTruncadaNoSeGuardaAunqueTraigaCampos() {
    StubClaude claude = new StubClaude();
    claude.responde(sobre("max_tokens", CORTADA));
    claude.responde(sobre("max_tokens", CORTADA));

    ValidacionDocumentoJson r = claude.diagnosticarCoactiva("auditor", "--- FOJA 1 --- pagaré");

    assertTrue(r.esError());
    assertNull(r.json());
    assertEquals("Respuesta truncada (max_tokens) al diagnosticar.", r.error());
    assertEquals(2, claude.enviados.size());
    assertFalse(r.error().contains("cita incompleta"));
  }

  @Test
  void reintentoBreveDevuelveEtapaYEvidenciaSinInventar() throws Exception {
    StubClaude claude = new StubClaude();
    claude.responde(sobre("max_tokens", CORTADA));
    claude.responde(sobre("end_turn", BREVE));

    ValidacionDocumentoJson r = claude.diagnosticarCoactiva("auditor", "--- FOJA 12 --- retención");

    assertFalse(r.esError());
    assertFalse(r.json().contains("texto cortado"));
    assertFalse(r.json().contains("cita incompleta"));
    JsonNode out = json.readTree(r.json());
    JsonNode etapa = out.get("etapa_sugerida");
    assertEquals("EMBARGO", etapa.get("codigo").asText());
    assertTrue(etapa.get("justificacion_juridica").asText().startsWith("El expediente se encuentra en Embargo"));
    assertEquals("Oficio de embargo", etapa.get("evidencia").get("documento_origen").asText());
    assertEquals(12, etapa.get("evidencia").get("foja_pagina").asInt());
    assertEquals("2022-03-02", etapa.get("evidencia").get("fecha_documento").asText());
    assertEquals("Se ordena la retención de fondos", etapa.get("evidencia").get("extracto_texto").asText());

    assertEquals(2, claude.enviados.size());
    JsonNode primera = json.readTree(claude.enviados.get(0));
    JsonNode segunda = json.readTree(claude.enviados.get(1));
    assertEquals(8192, primera.get("max_tokens").asInt());
    assertEquals(1024, segunda.get("max_tokens").asInt());
    assertTrue(primera.toString().contains("datos_extraidos"));
    assertFalse(segunda.toString().contains("datos_extraidos"));
    assertFalse(segunda.toString().contains("documentos_identificados"));
    assertTrue(segunda.toString().contains("\"maxLength\":180"));
    assertTrue(segunda.toString().contains("\"maxLength\":300"));
  }

  private static String sobre(String stop, String input) {
    return "{\"stop_reason\":\""
        + stop
        + "\",\"content\":[{\"type\":\"tool_use\",\"name\":\"diagnosticar_expediente_coactivo\",\"input\":"
        + input.trim()
        + "}],\"usage\":{\"input_tokens\":3,\"output_tokens\":3}}";
  }

  private static final class StubClaude extends ClaudeAnalysisService {
    final List<String> enviados = new ArrayList<>();
    private final ArrayDeque<String> respuestas = new ArrayDeque<>();

    StubClaude() {
      super("test-key", "claude-sonnet-5", new ObjectMapper(), null);
    }

    void responde(String body) {
      respuestas.add(body);
    }

    @Override
    protected HttpResponse<String> enviar(String body, Duration limite) {
      enviados.add(body);
      return new Fija(respuestas.removeFirst());
    }
  }

  private static final class Fija implements HttpResponse<String> {
    private final String body;

    Fija(String body) {
      this.body = body;
    }

    @Override
    public int statusCode() {
      return 200;
    }

    @Override
    public HttpRequest request() {
      return null;
    }

    @Override
    public Optional<HttpResponse<String>> previousResponse() {
      return Optional.empty();
    }

    @Override
    public HttpHeaders headers() {
      return HttpHeaders.of(Map.<String, List<String>>of(), (a, b) -> true);
    }

    @Override
    public String body() {
      return body;
    }

    @Override
    public Optional<SSLSession> sslSession() {
      return Optional.empty();
    }

    @Override
    public URI uri() {
      return URI.create("https://api.anthropic.com/v1/messages");
    }

    @Override
    public HttpClient.Version version() {
      return HttpClient.Version.HTTP_1_1;
    }
  }
}
