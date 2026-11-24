package com.lexia.api.modules.expedientes.coactivas.actuacion;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.lexia.api.modules.expedientes.coactivas.actuacion.CoactivaPlantillaCatalog.Entrada;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class CoactivaPlantillaCatalogSeederTest {

  private final CoactivaPlantillaCatalogSeeder seeder =
      new CoactivaPlantillaCatalogSeeder(new JdbcTemplate());

  @Test
  void tenantNuevoQuedaConCatalogoCompleto() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Memoria mem = new Memoria();

    int insertadas =
        seeder.sembrar(tenantId, CoactivaPlantillaCatalog.ENTRADAS, ruta -> true, mem);

    assertEquals(CoactivaPlantillaCatalog.ENTRADAS.size(), insertadas);
    assertEquals(CoactivaPlantillaCatalog.ENTRADAS.size(), mem.filas.size());
    for (Entrada e : CoactivaPlantillaCatalog.ENTRADAS) {
      assertTrue(mem.filas.containsKey(clave(tenantId, e.codigo())), e.codigo());
      assertTrue(mem.filas.get(clave(tenantId, e.codigo())).activo);
    }
  }

  @Test
  void sembrarDosVecesNoDuplica() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Memoria mem = new Memoria();
    List<Entrada> catalogo = CoactivaPlantillaCatalog.ENTRADAS;

    assertEquals(catalogo.size(), seeder.sembrar(tenantId, catalogo, ruta -> true, mem));
    assertEquals(0, seeder.sembrar(tenantId, catalogo, ruta -> true, mem));
    assertEquals(catalogo.size(), mem.filas.size());
  }

  @Test
  void plantillaDesactivadaSigueDesactivada() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Entrada opi = CoactivaPlantillaCatalog.ENTRADAS.get(0);
    Memoria mem = new Memoria();
    mem.filas.put(clave(tenantId, opi.codigo()), new Fila(opi, false));

    seeder.sembrar(tenantId, List.of(opi), ruta -> true, mem);

    Fila fila = mem.filas.get(clave(tenantId, opi.codigo()));
    assertFalse(fila.activo);
    assertEquals(opi.nombre(), fila.entrada.nombre());
  }

  @Test
  void rutaDocxAusenteSeSaltaSinExcepcion() throws Exception {
    UUID tenantId = UUID.randomUUID();
    Memoria mem = new Memoria();
    Entrada presente = CoactivaPlantillaCatalog.ENTRADAS.get(0);
    Entrada ausente = CoactivaPlantillaCatalog.ENTRADAS.get(1);
    Set<String> existentes = Set.of(presente.rutaDocx());

    assertDoesNotThrow(
        () ->
            seeder.sembrar(
                tenantId,
                List.of(presente, ausente),
                existentes::contains,
                mem));

    assertEquals(1, mem.filas.size());
    assertTrue(mem.filas.containsKey(clave(tenantId, presente.codigo())));
    assertFalse(mem.filas.containsKey(clave(tenantId, ausente.codigo())));
  }

  private static String clave(UUID tenantId, String codigo) {
    return tenantId + "|" + codigo;
  }

  private record Fila(Entrada entrada, boolean activo) {}

  /** Simula ON CONFLICT (tenant_id, codigo) DO NOTHING. */
  private static final class Memoria implements CoactivaPlantillaCatalogSeeder.Insertor {
    final Map<String, Fila> filas = new HashMap<>();
    final Set<String> intentos = new HashSet<>();

    @Override
    public boolean insert(UUID tenantId, Entrada entrada) {
      String k = clave(tenantId, entrada.codigo());
      intentos.add(k);
      if (filas.containsKey(k)) {
        return false;
      }
      filas.put(k, new Fila(entrada, true));
      return true;
    }
  }
}
