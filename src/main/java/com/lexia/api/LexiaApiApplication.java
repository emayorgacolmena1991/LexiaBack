package com.lexia.api;

import com.lexia.api.modules.auth.AuthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AuthProperties.class)
public class LexiaApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(LexiaApiApplication.class, args);
	}

}
