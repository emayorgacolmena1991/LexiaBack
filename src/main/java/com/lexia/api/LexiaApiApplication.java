package com.lexia.api;

import com.lexia.api.config.EnvFileLoader;
import com.lexia.api.modules.auth.AuthProperties;
import com.lexia.api.modules.auth.E2eProperties;
import com.lexia.api.modules.ia.prompt.PromptRegistryService;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({AuthProperties.class, E2eProperties.class, PromptRegistryService.class})
public class LexiaApiApplication {

	public static void main(String[] args) {
		EnvFileLoader.loadIfPresent();
		SpringApplication.run(LexiaApiApplication.class, args);
	}

}
