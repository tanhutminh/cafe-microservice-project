package com.cafe.orderservice.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunable operational parameter pointing at menu-service's base URL, configured locally in this
 * service's own application.yml under app.clients.menu-service - same tunable-operational-
 * parameter category as InboxProperties/OutboxProperties/SagaReconciliationProperties, but for an
 * outbound dependency's address rather than an internal mechanism. The @DefaultValue fallback is a
 * safety net for a key omitted from application.yml.
 */
@ConfigurationProperties(prefix = "app.clients.menu-service")
public record MenuServiceClientProperties(
    @DefaultValue("http://menu-service:8082") String baseUrl) {}
