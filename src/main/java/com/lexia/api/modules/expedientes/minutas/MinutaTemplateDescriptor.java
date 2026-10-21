package com.lexia.api.modules.expedientes.minutas;

import java.util.List;

/**
 * Descriptor de plantilla .docx por producto + tipo. Extensible: agregar entradas en
 * {@link MinutaTemplateCatalog} sin tocar el renderer.
 *
 * @param requiredFields tags sin los cuales no se genera el documento (se devuelve la lista de
 *     faltantes). El resto de tags vacíos se reportan como pendientes, sin bloquear.
 */
public record MinutaTemplateDescriptor(
    String productCode,
    String templateKind,
    String classpathResource,
    String fileName,
    List<String> requiredFields) {

  public MinutaTemplateDescriptor {
    requiredFields = requiredFields == null ? List.of() : List.copyOf(requiredFields);
  }

  public MinutaTemplateDescriptor(
      String productCode, String templateKind, String classpathResource, String fileName) {
    this(productCode, templateKind, classpathResource, fileName, List.of());
  }
}
