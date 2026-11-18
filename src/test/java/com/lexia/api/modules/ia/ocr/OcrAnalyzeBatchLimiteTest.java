package com.lexia.api.modules.ia.ocr;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.lexia.api.common.api.ApiException;
import com.lexia.api.modules.expedientes.documentos.CargaDocumentoService;
import com.lexia.api.modules.expedientes.documentos.ClasificacionDocumentoService;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchDocument;
import com.lexia.api.modules.ia.ocr.OcrFlujoDtos.AnalyzeBatchRequest;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

class OcrAnalyzeBatchLimiteTest {

  @Test
  void loteSobreElTopeSeRechazaSinTocarAzureNiElPool() {
    AzureCalidadDocumentoService azure = mock(AzureCalidadDocumentoService.class);
    Executor pool = mock(Executor.class);
    OcrAzureBatchService service =
        new OcrAzureBatchService(
            azure,
            mock(CargaDocumentoService.class),
            mock(OcrSessionCacheService.class),
            mock(DocumentoTextoOcrRepository.class),
            pool,
            mock(ClasificacionDocumentoService.class),
            mock(PlatformTransactionManager.class));
    List<AnalyzeBatchDocument> docs =
        IntStream.rangeClosed(0, OcrAzureBatchService.MAX_DOCUMENTOS_BATCH)
            .mapToObj(i -> new AnalyzeBatchDocument("f" + i, "f" + i + ".pdf", null, null))
            .toList();

    assertThrows(
        ApiException.class, () -> service.analyzeBatch(new AnalyzeBatchRequest("s1", docs)));
    verifyNoInteractions(azure, pool);
  }
}
