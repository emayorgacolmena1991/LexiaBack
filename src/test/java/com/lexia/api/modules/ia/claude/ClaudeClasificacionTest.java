package com.lexia.api.modules.ia.claude;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ClasificacionDocumento;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;

class ClaudeClasificacionTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void pideUnaToolConLaListaCerradaMasOtroYLeeTipoYConfianza() throws Exception {
    Stub claude =
        new Stub(
            200,
            "{\"content\":[{\"type\":\"tool_use\",\"name\":\"clasificar_documento\","
                + "\"input\":{\"tipo\":\"CEDULA\",\"confianza\":0.91}}]}");

    ClasificacionDocumento r =
        claude.clasificarDocumento("CEDULA 0912345678", List.of("CEDULA", "AVALUO"), Duration.ofSeconds(5));

    assertEquals("CEDULA", r.tipo());
    assertEquals(0.91, r.confianza());
    assertEquals("claude-haiku-test", r.modelo());
    assertEquals(Duration.ofSeconds(5), claude.limite);
    JsonNode body = JSON.readTree(claude.enviado);
    assertEquals("claude-haiku-test", body.path("model").asText());
    JsonNode tipos = body.at("/tools/0/input_schema/properties/tipo/enum");
    assertEquals(List.of("CEDULA", "AVALUO", "OTRO"), JSON.convertValue(tipos, List.class));
  }

  @Test
  void httpDistintoDe200LanzaSinCitarElCuerpo() {
    Stub claude = new Stub(529, "{\"error\":\"overloaded: CEDULA 0912345678\"}");

    IllegalStateException error =
        assertThrows(
            IllegalStateException.class,
            () -> claude.clasificarDocumento("texto", List.of("CEDULA"), Duration.ofSeconds(5)));

    assertFalse(error.getMessage().contains("0912345678"));
  }

  private static final class Stub extends ClaudeAnalysisService {
    private final int status;
    private final String respuesta;
    String enviado;
    Duration limite;

    Stub(int status, String respuesta) {
      super("test-key", "claude-sonnet-test", "claude-haiku-test", JSON, null);
      this.status = status;
      this.respuesta = respuesta;
    }

    @Override
    protected HttpResponse<String> enviar(String body, Duration limite) {
      this.enviado = body;
      this.limite = limite;
      return new Fija(status, respuesta);
    }
  }

  private record Fija(int statusCode, String body) implements HttpResponse<String> {
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
      return HttpHeaders.of(Map.of(), (a, b) -> true);
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
