package com.lexia.api.modules.expedientes.ocr;


import com.lexia.api.modules.expedientes.caso.ExpedienteDtos;
public interface OcrService {

  ExpedienteDtos.DatosExtraidosDTO analizarDocumento(byte[] fileBytes, String mimeType);

  ExpedienteDtos.DatosExtraidosDTO analizarTexto(String texto);
}
