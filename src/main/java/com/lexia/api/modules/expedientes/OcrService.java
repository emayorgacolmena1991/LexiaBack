package com.lexia.api.modules.expedientes;

public interface OcrService {

  ExpedienteDtos.DatosExtraidosDTO analizarDocumento(byte[] fileBytes, String mimeType);
}
