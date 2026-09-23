package com.lexia.api.modules.ia.ocr;

/** Texto + legibilidad desde un solo {@code prebuilt-layout}. */
public record LayoutExtractResult(CalidadDocumentoResultado calidad, String texto) {}
