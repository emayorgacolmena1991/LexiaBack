package com.lexia.api.modules.expedientes.coactivas.actuacion;

import java.util.List;

/**
 * Catálogo canónico de plantillas coactivas (valores de V66 + embargo-banco de V64).
 * La siembra por tenant vive en {@link CoactivaPlantillaCatalogSeeder}.
 */
public final class CoactivaPlantillaCatalog {

  public record Entrada(String codigo, String nombre, String etapa, String rutaDocx) {}

  /** Orden estable; códigos únicos. */
  public static final List<Entrada> ENTRADAS =
      List.of(
          e("opi", "FORMATO ORDEN DE PAGO INMEDIATO.docx", "OPI_EMITIDA",
              "templates/coactivas/IMPULSO_ORDEN_PAGO_VARIABLES.docx"),
          e("ratificacion-gye", "FORMATO RATIFICACION DE MEDIDAS GYE.docx", "MEDIDAS_CAUTELARES",
              "templates/coactivas/RATIFICACION_GYE_VARIABLES.docx"),
          e("ratificacion-portoviejo", "FORMATO RATIFICACION PORTOVIEJO.docx", "MEDIDAS_CAUTELARES",
              "templates/coactivas/RATIFICACION_PORTOVIEJO_VARIABLES.docx"),
          e("ratificacion-portoviejo-2", "FORMATO RATIFICACION PORTOVIEJO 2.docx", "MEDIDAS_CAUTELARES",
              "templates/coactivas/RATIFICACION_PORTOVIEJO_VARIABLES_2.docx"),
          e("ratificacion-chone", "FORMATO RATIFICACION CHONE - EL CARMEN.docx", "MEDIDAS_CAUTELARES",
              "templates/coactivas/RATIFICACION_CHONE_VARIABLES.docx"),
          e("escrito-providencia", "PROVIDENCIA ATENCION ESCRITO.docx", "ESCRITO",
              "templates/coactivas/FORMATO_PROV_CONTESTACION_ESCRITO_TEMPLATE.docx"),
          e("acta-entrega-copias", "ACTA DE ENTREGA DE COPIAS CERTIFICADAS.docx", "ESCRITO",
              "templates/coactivas/ACTA_ENTREGA_COPIAS_CERTIFICADAS.docx"),
          e("convenio-traslado", "PROVIDENCIA CORRER TRASLADO.docx", "CONVENIO",
              "templates/coactivas/CORRER_TRASLADO_FACILIDAD_PAGO.docx"),
          e("embargo-transferencia", "FORMATO EMBARGO DE VALORES TRANSFERENCIA.docx", "EMBARGO",
              "templates/coactivas/EMBARGO_VALORES_TRANSFERENCIA.docx"),
          e("embargo-oficio", "OFICIO DE EMBARGO TRANSFERENCIA.docx", "EMBARGO",
              "templates/coactivas/EMBARGO_TRANSFERENCIA.docx"),
          e("oficio-embargo", "OFICIO DE EMBARGO.docx", "EMBARGO",
              "templates/coactivas/OFICIO_EMBARGO_VARIABLES.docx"),
          e("embargo-cheque", "ACTA POSESION DEPOSITARIO JUDICIAL.docx", "EMBARGO",
              "templates/coactivas/ACTA_POSESION_DEPOSITARIO_JUDICIAL_EMBARGO_CHEQUE.docx"),
          e("embargo-banco", "FORMATO PROVIDENCIA DE EMBARGO.docx", "EMBARGO",
              "templates/coactivas/FORMATO_PROV_EMBARGO_TEMPLATE.docx"),
          e("honorarios-solicitud", "SOLICITUD CARGA HONORARIOS EMBARGO.docx", "HONORARIOS",
              "templates/coactivas/SOLICITUD_CARGA_HONORARIOS_EMBARGO_TRANSFERENCIA.docx"),
          e("honorarios-providencia", "PROV. COBRO DE HONORARIOS POR EMBARGO.docx", "HONORARIOS",
              "templates/coactivas/FORMATO_PROV_COBRO_HONORARIOS_EMBARGO_TEMPLATE.docx"),
          e("honorarios-informe", "INFORME COBRO HONORARIOS POR EMBARGO.docx", "HONORARIOS",
              "templates/coactivas/INFORME_HONORARIOS_EMBARGO_VARIABLES_ANEXOS.docx"),
          e("honorarios-abono-providencia", "PROV. COBRO DE HONORARIOS POR ABONO.docx", "HONORARIOS",
              "templates/coactivas/FORMATO_PROV_COBRO_HONORARIOS_POR_ABONO_TEMPLATE.docx"),
          e("honorarios-abono-informe", "INFORME COBRO HONORARIOS POR ABONO.docx", "HONORARIOS",
              "templates/coactivas/INFORME_HONORARIOS_ABONO_VARIABLES.docx"));

  private CoactivaPlantillaCatalog() {}

  private static Entrada e(String codigo, String nombre, String etapa, String ruta) {
    return new Entrada(codigo, nombre, etapa, ruta);
  }
}
