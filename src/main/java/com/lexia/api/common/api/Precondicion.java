package com.lexia.api.common.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

/** 409 uniforme antes de encolar un job. */
public final class Precondicion {

  private static final Logger LOG = LoggerFactory.getLogger(Precondicion.class);

  private Precondicion() {}

  public static ApiException fallo(
      String endpoint, String motivo, String detalle, String accion, Map<String, Object> contexto) {
    LOG.info("precondicion endpoint={} motivo={} contexto={}", endpoint, motivo, contexto);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", "PRECONDICION_FALLIDA");
    body.put("motivo", motivo);
    body.put("detalle", detalle);
    body.put("accionSugerida", accion);
    body.put("contexto", contexto == null ? Map.of() : contexto);
    return new ApiException(HttpStatus.CONFLICT, "PRECONDICION_FALLIDA", detalle, List.of(), body);
  }
}
