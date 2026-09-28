package com.lexia.api;

import com.lexia.api.modules.actos.ActoNotarialService;
import com.lexia.api.modules.expedientes.documentos.DocumentoTextoOcrRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class LexiaApiApplicationTests {

	@MockitoBean private ActoNotarialService actoNotarialService;

	@MockitoBean private DocumentoTextoOcrRepository documentoTextoOcrRepository;

	@Test
	void contextLoads() {
	}

}
