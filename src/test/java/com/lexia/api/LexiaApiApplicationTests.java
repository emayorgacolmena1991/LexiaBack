package com.lexia.api;

import com.lexia.api.modules.actos.ActoNotarialService;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import com.lexia.api.modules.expedientes.documentos.ExpedienteBorradorStore;
import com.lexia.api.modules.expedientes.documentos.ExtractedDataRepository;
import com.lexia.api.modules.expedientes.escrituracion.ExpedienteEstadoService;
import com.lexia.api.modules.expedientes.escrituracion.IaAnalysisService;
import com.lexia.api.modules.expedientes.minutas.CapturaBiessService;
import com.lexia.api.modules.expedientes.minutas.MinutaGenerationService;
import com.lexia.api.modules.expedientes.reglas.ProductoBiessService;
import com.lexia.api.modules.expedientes.reglas.ValidacionIaService;
import com.lexia.api.modules.ia.llm.ExpedienteCompletoLlmService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class LexiaApiApplicationTests {

	@MockitoBean private ActoNotarialService actoNotarialService;

	@MockitoBean private DocumentoTextoOcrRepository documentoTextoOcrRepository;

	@MockitoBean private ExpedienteBorradorStore expedienteBorradorStore;

	@MockitoBean private ProductoBiessService productoBiessService;

	@MockitoBean private ExpedienteCompletoLlmService expedienteCompletoLlmService;

	@MockitoBean private ValidacionIaService validacionIaService;

	@MockitoBean private IaAnalysisService iaAnalysisService;

	@MockitoBean private MinutaGenerationService minutaGenerationService;

	@MockitoBean private CapturaBiessService capturaBiessService;

	@MockitoBean private ExpedienteEstadoService expedienteEstadoService;

	@MockitoBean private ExtractedDataRepository extractedDataRepository;

	@Test
	void contextLoads() {
	}

}
