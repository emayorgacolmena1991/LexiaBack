package com.lexia.api.modules.ia.llm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;

class LlmTimeoutExceptionTest {

  @Test
  void reconoceTimeoutYNoUnErrorHttp() {
    assertTrue(LlmTimeoutException.es(new HttpTimeoutException("request timed out")));
    assertTrue(LlmTimeoutException.es(new LlmTimeoutException("corte", null)));
    assertFalse(LlmTimeoutException.es(new IllegalStateException("HTTP 500")));
  }
}
