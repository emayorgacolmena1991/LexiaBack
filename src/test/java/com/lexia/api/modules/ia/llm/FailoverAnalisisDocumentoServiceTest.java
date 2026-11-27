package com.lexia.api.modules.ia.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.lexia.api.modules.ia.ProveedorIaErrores;
import com.lexia.api.modules.ia.claude.ClaudeAnalysisService;
import com.lexia.api.modules.ia.gemini.GeminiAnalysisService;
import com.lexia.api.modules.ia.llm.AnalisisDocumentoService.ExtraccionExpedienteCompleto;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

@ExtendWith(MockitoExtension.class)
class FailoverAnalisisDocumentoServiceTest {

  @Mock private ClaudeAnalysisService claude;
  @Mock private GeminiAnalysisService gemini;
  @Mock private ObjectProvider<ClaudeAnalysisService> claudeProvider;
  @Mock private ObjectProvider<GeminiAnalysisService> geminiProvider;

  private FailoverAnalisisDocumentoService service;

  @BeforeEach
  void setUp() {
    when(claudeProvider.getIfAvailable()).thenReturn(claude);
    when(geminiProvider.getIfAvailable()).thenReturn(gemini);
    service = new FailoverAnalisisDocumentoService(claudeProvider, geminiProvider, "claude", true);
  }

  @Test
  void conmutaAGeminiCuandoClaudeSinCredito() {
    when(claude.isConfigured()).thenReturn(true);
    when(gemini.isConfigured()).thenReturn(true);
    when(claude.procesarExpedienteCompleto(eq("ocr"), eq("sys")))
        .thenReturn(
            ExtraccionExpedienteCompleto.error(
                "Your credit balance is too low to access the Anthropic API"));
  when(gemini.procesarExpedienteCompleto(eq("ocr"), eq("sys")))
        .thenReturn(
            ExtraccionExpedienteCompleto.ok(
                new ProcesarExpedienteCompletoPayload(
                    List.of(),
                    new DatosExtraidos(null, null, null),
                    new Dictamen("APPROVED", "ok", List.of()))));

    ExtraccionExpedienteCompleto resultado = service.procesarExpedienteCompleto("ocr", "sys");

    assertEquals("OK", resultado.estado());
    verify(gemini).procesarExpedienteCompleto("ocr", "sys");
  }

  @Test
  void noConmutaSiFailoverDeshabilitado() {
    service = new FailoverAnalisisDocumentoService(claudeProvider, geminiProvider, "claude", false);
    when(claude.isConfigured()).thenReturn(true);
    when(claude.procesarExpedienteCompleto(anyString(), anyString()))
        .thenReturn(
            ExtraccionExpedienteCompleto.error(
                "Your credit balance is too low to access the Anthropic API"));

    ExtraccionExpedienteCompleto resultado = service.procesarExpedienteCompleto("ocr", "sys");

    assertEquals("ERROR", resultado.estado());
  }

  @Test
  void admiteFailoverSegunClasificacion() {
    assertTrue(
        ProveedorIaErrores.admiteFailoverProveedor(
            "Your credit balance is too low to access the Anthropic API"));
    assertFalse(ProveedorIaErrores.admiteFailoverProveedor("invalid api key"));
    assertTrue(ProveedorIaErrores.admiteFailoverProveedor("rate limit exceeded"));
  }
}