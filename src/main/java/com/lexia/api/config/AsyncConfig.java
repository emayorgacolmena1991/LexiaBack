package com.lexia.api.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import com.lexia.api.modules.notifications.BrevoProperties;

@Configuration
@EnableAsync
@EnableConfigurationProperties(BrevoProperties.class)
public class AsyncConfig {}
