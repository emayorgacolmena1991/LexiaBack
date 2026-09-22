package com.lexia.api;

import com.lexia.api.modules.actos.ActoNotarialService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

@SpringBootTest
@ActiveProfiles("test")
class LexiaApiApplicationTests {

	@MockitoBean private ActoNotarialService actoNotarialService;

	@Test
	void contextLoads() {
	}

}
