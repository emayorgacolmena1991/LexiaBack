package com.lexia.api.modules.ia.llm;

import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DatosExtraidos;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Dictamen;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.DocumentoExtraido;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Inmueble;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Observacion;
import com.lexia.api.modules.ia.llm.ProcesarExpedienteCompletoPayload.Persona;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** El dictamen profundo completa el de Haiku. No reemplaza lo que el cotejo ya llenó. */
public final class AnalisisFusion {

  private AnalisisFusion() {}

  public static ProcesarExpedienteCompletoPayload fusionar(
      ProcesarExpedienteCompletoPayload base, ProcesarExpedienteCompletoPayload extra) {
    if (base == null) {
      return extra;
    }
    if (extra == null) {
      return base;
    }
    return new ProcesarExpedienteCompletoPayload(
        documentos(base.documentosExtraidos(), extra.documentosExtraidos()),
        datos(base.datosExtraidos(), extra.datosExtraidos()),
        dictamen(base.dictamen(), extra.dictamen()));
  }

  private static List<DocumentoExtraido> documentos(
      List<DocumentoExtraido> base, List<DocumentoExtraido> extra) {
    Map<String, DocumentoExtraido> porId = new LinkedHashMap<>();
    for (DocumentoExtraido doc : base) {
      if (doc != null) {
        porId.put(clave(doc.documentoId(), porId.size()), doc);
      }
    }
    for (DocumentoExtraido doc : extra) {
      if (doc == null) {
        continue;
      }
      String id = clave(doc.documentoId(), porId.size());
      porId.putIfAbsent(id, doc);
    }
    return List.copyOf(porId.values());
  }

  private static String clave(String id, int n) {
    return id == null || id.isBlank() ? "doc-" + n : id.trim();
  }

  private static DatosExtraidos datos(DatosExtraidos base, DatosExtraidos extra) {
    if (base == null) {
      return extra;
    }
    if (extra == null) {
      return base;
    }
    return new DatosExtraidos(
        persona(base.comprador(), extra.comprador()),
        persona(base.vendedor(), extra.vendedor()),
        inmueble(base.inmueble(), extra.inmueble()));
  }

  private static Persona persona(Persona base, Persona extra) {
    if (base == null) {
      return extra;
    }
    if (extra == null) {
      return base;
    }
    return new Persona(
        primero(base.nombres(), extra.nombres()),
        primero(base.cedula(), extra.cedula()),
        primero(base.estadoCivil(), extra.estadoCivil()));
  }

  private static Inmueble inmueble(Inmueble base, Inmueble extra) {
    if (base == null) {
      return extra;
    }
    if (extra == null) {
      return base;
    }
    return new Inmueble(
        primero(base.claveCatastral(), extra.claveCatastral()),
        primero(base.linderos(), extra.linderos()),
        base.avaluo() != null ? base.avaluo() : extra.avaluo());
  }

  private static Dictamen dictamen(Dictamen base, Dictamen extra) {
    if (base == null) {
      return extra;
    }
    if (extra == null) {
      return base;
    }
    Map<String, Observacion> unicas = new LinkedHashMap<>();
    for (Observacion obs : base.observaciones()) {
      if (obs != null) {
        unicas.putIfAbsent(firma(obs), obs);
      }
    }
    for (Observacion obs : extra.observaciones()) {
      if (obs != null) {
        unicas.putIfAbsent(firma(obs), obs);
      }
    }
    String estado = peor(base.estado(), extra.estado());
    String resumen = primero(extra.resumen(), base.resumen());
    return new Dictamen(estado, resumen, new ArrayList<>(unicas.values()));
  }

  private static String firma(Observacion obs) {
    return (obs.codigo() == null ? "" : obs.codigo()) + "|" + (obs.mensaje() == null ? "" : obs.mensaje());
  }

  private static String peor(String a, String b) {
    return rango(b) > rango(a) ? b : a;
  }

  private static int rango(String estado) {
    String valor = estado == null ? "" : estado.trim().toUpperCase(Locale.ROOT);
    if ("REJECTED".equals(valor)) {
      return 3;
    }
    if ("WITH_OBSERVATIONS".equals(valor)) {
      return 2;
    }
    if ("APPROVED".equals(valor)) {
      return 1;
    }
    return 0;
  }

  private static String primero(String preferido, String respaldo) {
    return preferido != null && !preferido.isBlank() ? preferido : respaldo;
  }
}
