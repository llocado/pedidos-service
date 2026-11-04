package com.supermercado.pedidos.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Activa la tarea programada de retencion de eventos y sus propiedades (purga.*). */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(PurgaProperties.class)
public class PurgaConfig {
}
