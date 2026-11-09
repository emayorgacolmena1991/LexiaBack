package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * P2 #6: el upsert no puede actualizar {@code texto_ocr} y conservar {@code resultado_json}
 * viejo (ni al revés). Texto y polígonos van juntos.
 */
class DocumentoCacheStoreUpsertTest {

  private static final UUID TENANT = UUID.fromString("b1000000-0000-7000-8000-0000000000c6");

  @Test
  void upsertAsignaTextoYPoligonosJuntosSinCoalesce() {
    AtomicReference<String> sql = new AtomicReference<>();
    EntityManager em = mock(EntityManager.class);
    Query query = mock(Query.class);
    when(em.createNativeQuery(anyString()))
        .thenAnswer(
            inv -> {
              sql.set(inv.getArgument(0));
              return query;
            });
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(1);

    new DocumentoCacheStore(em)
        .guardar(
            TENANT,
            "a".repeat(64),
            "coactiva-ocr-v1",
            "AZURE_READ",
            "--- FOJA 1 ---\ntasa de interes\n",
            null);

    String q = sql.get();
    assertTrue(q != null && q.contains("ON CONFLICT"), q);
    assertFalse(
        q.contains("COALESCE(EXCLUDED.texto_ocr"),
        "texto_ocr no debe preservarse con COALESCE: " + q);
    assertFalse(
        q.contains("COALESCE(EXCLUDED.resultado_json"),
        "resultado_json no debe preservarse con COALESCE: " + q);
    assertTrue(q.contains("texto_ocr = EXCLUDED.texto_ocr"), q);
    assertTrue(q.contains("resultado_json = EXCLUDED.resultado_json"), q);
  }

  /**
   * Reproducción: texto nuevo + JSON null. Con COALESCE quedarían polígonos viejos
   * (búsqueda con extractos y cajas vacías/desfasadas). El upsert atómico no.
   */
  @Test
  void textoNuevoConJsonVacioNoConservaPoligonosViejos() {
    String textoViejo = "--- FOJA 1 ---\nviejo\n";
    String polyViejo =
        "{\"pages\":[{\"n\":1,\"w\":1,\"h\":1,\"words\":[{\"t\":\"viejo\",\"p\":[0,0,1,1]}]}]}";
    String textoNuevo = "--- FOJA 1 ---\ntasa de interes\n";

    // COALESCE(EXCLUDED.resultado_json, viejo) con json null → conserva polyViejo.
    DocumentoCacheStore.Hit conCoalesce =
        new DocumentoCacheStore.Hit("AZURE_READ", textoNuevo, polyViejo);
    assertEquals(textoNuevo, conCoalesce.textoOcr());
    assertEquals(polyViejo, conCoalesce.resultadoJson(), "esto es el bug COALESCE");

    DocumentoCacheStore.Hit atomico =
        new DocumentoCacheStore.Hit("AZURE_READ", textoNuevo, null);
    assertEquals(textoNuevo, atomico.textoOcr());
    assertNull(atomico.resultadoJson());

    // El store debe implementar la variante atómica (ver test de SQL).
    AtomicReference<String> sql = new AtomicReference<>();
    EntityManager em = mock(EntityManager.class);
    Query query = mock(Query.class);
    when(em.createNativeQuery(anyString()))
        .thenAnswer(
            inv -> {
              sql.set(inv.getArgument(0));
              return query;
            });
    when(query.setParameter(anyString(), any())).thenReturn(query);
    when(query.executeUpdate()).thenReturn(1);
    new DocumentoCacheStore(em)
        .guardar(TENANT, "b".repeat(64), "coactiva-ocr-v1", "AZURE_READ", textoNuevo, null);

    assertFalse(
        sql.get().contains("COALESCE(EXCLUDED.resultado_json"),
        "si hay COALESCE, texto y polígonos pueden desincronizarse");
  }
}
