package dev.fondaria.testing;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class ContainerImagesTest {

  @Test
  void testImageTagsMatchDockerCompose() throws Exception {
    Path composePath = findDockerCompose();
    assertThat(composePath)
        .as("docker-compose.yml should be found in workspace")
        .isNotNull()
        .exists();

    Yaml yaml = new Yaml();
    Map<String, Object> composeMap;
    try (InputStream is = Files.newInputStream(composePath)) {
      composeMap = yaml.load(is);
    }

    @SuppressWarnings("unchecked")
    Map<String, Map<String, Object>> services =
        (Map<String, Map<String, Object>>) composeMap.get("services");
    assertThat(services).isNotNull();

    assertThat(ContainerImages.POSTGRES)
        .as("PostgreSQL tag should match docker-compose.yml")
        .isEqualTo(services.get("postgres").get("image"));

    assertThat(ContainerImages.REDIS)
        .as("Redis tag should match docker-compose.yml")
        .isEqualTo(services.get("redis").get("image"));

    assertThat(ContainerImages.RABBITMQ)
        .as("RabbitMQ tag should match docker-compose.yml")
        .isEqualTo(services.get("rabbitmq").get("image"));

    assertThat(ContainerImages.OBJECT_STORE)
        .as("Object store image should match docker-compose.yml")
        .isEqualTo(services.get("object-store").get("image"));

    assertThat(ContainerImages.TIGERBEETLE)
        .as("TigerBeetle tag should match docker-compose.yml")
        .isEqualTo(services.get("tigerbeetle").get("image"));

    assertThat(ContainerImages.KEYCLOAK)
        .as("Keycloak tag should match docker-compose.yml")
        .isEqualTo(services.get("keycloak").get("image"));

    assertThat(ContainerImages.TEMPO)
        .as("Tempo tag should match docker-compose.yml")
        .isEqualTo(services.get("tempo").get("image"));

    assertThat(ContainerImages.OTEL_COLLECTOR)
        .as("OpenTelemetry Collector tag should match docker-compose.yml")
        .isEqualTo(services.get("otel-collector").get("image"));
  }

  private Path findDockerCompose() {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      Path file = dir.resolve("infrastructure/compose/docker-compose.yml");
      if (Files.exists(file)) {
        return file;
      }
      dir = dir.getParent();
    }
    return null;
  }
}
