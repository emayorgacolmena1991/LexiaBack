package com.lexia.api.modules.expedientes.coactivas.ingesta;

import static org.assertj.core.api.Assertions.assertThat;

import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.FilaActa;
import com.lexia.api.modules.expedientes.coactivas.ingesta.ActaTableParser.Resultado;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Cabeceras de actas reales de dos delegados distintos (BanEcuador Chone y SAE Daule). */
class ActaCabecerasRealesTest {

  @Test
  void actaChoneSigueLeyendoseIgual() {
    Resultado r =
        ActaTableParser.interpretar(
            List.of(
                List.of(
                    List.of("ACTA DE ENTREGA DE EXPEDIENTES PARA IMPULSO PROCESAL", "", "", "", "", "", "", "", "", ""),
                    List.of("#", "ZONAL", "OFICINA", "UEC", "OPERACIÓN", "NÚMERO DE JUICIO", "DEUDOR PRINCIPAL",
                        "CÉDULA DEUDOR PRINCIPAL", "ETAPA PROCESAL", "No. DE FOJAS"),
                    List.of("1", "PORTOVIEJO", "AG. CHONE", "UEC CHONE", "700299463", "070-2022-00137",
                        "ALAVA RODRIGUEZ DIOMEDES JACINTO", "1305711804", "MEDIDAS CAUTELARES", "86"))));

    assertThat(r.advertencias()).isEmpty();
    assertThat(r.uec()).isEqualTo("UEC CHONE");
    FilaActa f = r.filas().get(0);
    assertThat(f.juicio()).isEqualTo("070-2022-00137");
    assertThat(f.deudor()).isEqualTo("ALAVA RODRIGUEZ DIOMEDES JACINTO");
    assertThat(f.cedula()).isEqualTo("1305711804");
    assertThat(f.operacion()).isEqualTo("700299463");
    assertThat(f.etapa()).isEqualTo("MEDIDAS CAUTELARES");
    assertThat(f.fojas()).isEqualTo(86);
  }

  @Test
  void actaDauleLeeElDeudorDeNombreCoactivadoYLaUec() {
    Resultado r =
        ActaTableParser.interpretar(
            List.of(
                List.of(
                    List.of("ENTREGA RECEPCIÓN DE PROCESOS COACTIVOS", "", "", "", "", "(UNIDAD EJECUCIÓN COACTIVA DAULE)",
                        "", "", "", ""),
                    List.of("Zonal", "Oficina", "Unidad Ejecucion", "No. Proceso", "No. Operación", "Nombre Coactivado",
                        "Cuantía Inicial", "Riesgo Actual", "FOJAS", "Observaciones"),
                    List.of("GUAYAQUIL", "DAULE (AG.)", "DAULE", "021-2019-00185", "210284910",
                        "SANCHEZ SALAS FLORENTINO RICARDO", "$ 5.091,44", "$ 15.456,31", "39", ""))));

    assertThat(r.advertencias()).isEmpty();
    assertThat(r.uec()).isEqualTo("DAULE");
    FilaActa f = r.filas().get(0);
    assertThat(f.juicio()).isEqualTo("021-2019-00185");
    assertThat(f.deudor()).isEqualTo("SANCHEZ SALAS FLORENTINO RICARDO");
    assertThat(f.operacion()).isEqualTo("210284910");
    assertThat(f.oficina()).isEqualTo("DAULE (AG.)");
    assertThat(f.fojas()).isEqualTo(39);
    assertThat(f.cedula()).isNull();
  }

  @Test
  void coactivadoEsDeudorYCoactivaSigueSiendoJuicio() {
    assertThat(ActaTableParser.campoDe("NOMBRE COACTIVADO")).contains(ActaTableParser.Campo.DEUDOR);
    assertThat(ActaTableParser.campoDe("COACTIVADA")).contains(ActaTableParser.Campo.DEUDOR);
    assertThat(ActaTableParser.campoDe("NO COACTIVA")).contains(ActaTableParser.Campo.JUICIO);
    assertThat(ActaTableParser.campoDe("UNIDAD EJECUCION")).contains(ActaTableParser.Campo.UEC);
    assertThat(ActaTableParser.campoDe("UNIDAD DE EJECUCION")).contains(ActaTableParser.Campo.UEC);
  }
}
