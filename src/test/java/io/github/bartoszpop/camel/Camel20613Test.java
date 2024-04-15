package io.github.bartoszpop.camel;

import static java.lang.Thread.sleep;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import org.apache.camel.CamelContext;
import org.apache.camel.EndpointInject;
import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.builder.RouteConfigurationBuilder;
import org.apache.camel.builder.endpoint.EndpointRouteBuilder;
import org.apache.camel.component.mock.MockEndpoint;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@SpringBootTest
class Camel20613Test {

  @Autowired
  private ProducerTemplate producerTemplate;

  @Autowired
  private CamelContext camelContext;

  @EndpointInject("mock:other")
  private MockEndpoint otherMock;

  @Test
  void createMultipleTemplatedRoutesInParallel_ConcurrentModificationExceptionThrown() throws InterruptedException {
    // Arrange
    var latch = new CountDownLatch(1);
    camelContext.getCamelContextExtension().registerEndpointCallback((uri, endpoint) -> {
      if (endpoint.getEndpointUri().startsWith("kamelet://template")) {
        latch.countDown();
        try {
          sleep(Duration.ofSeconds(5));
        } catch (InterruptedException e) {
          throw new RuntimeException("Test failed.", e);
        }
      }
      return endpoint;
    });
    otherMock.expectedMessageCount(2);

    // Act
    producerTemplate.sendBody("seda:route", "This is the first invocation.");
    latch.await();
    producerTemplate.requestBody("seda:route",
        "This is the second invocation while EndpointStrategy for the first invocation is still in progress.");

    // Assert
    otherMock.assertIsSatisfied();
  }

  @Configuration
  @EnableAutoConfiguration
  static class Config {

    @Bean
    public RouteBuilder sampleRoute() {
      return new EndpointRouteBuilder() {
        @Override
        public void configure() {
          from(seda("route").concurrentConsumers(2))
              .toD(
                  "kamelet:template?dynamicParameter=${body}"); // the exchange body as a dynamic parameter to create a new instance of the route template with every invocation
        }
      };
    }

    @Bean
    public RouteBuilder sampleRouteTemplate() {
      return new EndpointRouteBuilder() {
        @Override
        public void configure() {
          routeTemplate("template").
              from("kamelet:source")
              .to("mock:some");
        }
      };
    }

    @Bean
    public RouteBuilder sampleRouteConfiguration() {
      return new RouteConfigurationBuilder() {
        @Override
        public void configuration() {
          routeConfiguration()
              .interceptSendToEndpoint("mock:some")
              .skipSendToOriginalEndpoint()
              .to("mock:other");
        }
      };
    }
  }
}
