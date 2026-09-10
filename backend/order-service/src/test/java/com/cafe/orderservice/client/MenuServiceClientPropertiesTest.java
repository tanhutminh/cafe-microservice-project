package com.cafe.orderservice.client;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class MenuServiceClientPropertiesTest {
  private static ApplicationContextRunner contextRunner() {
    return new ApplicationContextRunner().withUserConfiguration(TestConfig.class);
  }

  @Test
  void baseUrl_bindsToConfiguredMenuServiceAddress() {
    contextRunner()
        .withPropertyValues("app.clients.menu-service.base-url=http://test-menu-service:9999")
        .run(
            context -> {
              MenuServiceClientProperties properties =
                  context.getBean(MenuServiceClientProperties.class);
              assertThat(properties.baseUrl()).isEqualTo("http://test-menu-service:9999");
            });
  }

  @Test
  void baseUrl_environmentContainsTheConfiguredMenuServiceAddress() {
    contextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .run(
            context ->
                assertThat(
                        context.getEnvironment().getProperty("app.clients.menu-service.base-url"))
                    .isEqualTo("http://menu-service:8082"));
  }

  @Test
  void baseUrl_fallsBackToDefaultWhenOmittedFromConfig() {
    contextRunner()
        .run(
            context -> {
              MenuServiceClientProperties properties =
                  context.getBean(MenuServiceClientProperties.class);
              assertThat(properties.baseUrl()).isEqualTo("http://menu-service:8082");
            });
  }

  @EnableConfigurationProperties(MenuServiceClientProperties.class)
  static class TestConfig {}
}
