package com.lexia.api.modules.expedientes.minutas;

/**
 * Descriptor de plantilla .docx por producto + tipo. Extensible: agregar entradas en
 * {@link MinutaTemplateCatalog} sin tocar el renderer.
 */
public record MinutaTemplateDescriptor(
    String productCode, String templateKind, String classpathResource, String fileName) {}
