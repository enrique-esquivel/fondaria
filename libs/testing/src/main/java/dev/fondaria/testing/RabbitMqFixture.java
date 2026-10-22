package dev.fondaria.testing;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton fixture for RabbitMQ container. Starts once per JVM and provides connection details and
 * reset.
 */
public final class RabbitMqFixture {

  private static final Logger LOGGER = LoggerFactory.getLogger(RabbitMqFixture.class);
  private static volatile RabbitMqFixture instance;

  private final RabbitMQContainer container;

  private RabbitMqFixture() {
    ContainerImages.initDockerEnv();
    LOGGER.info("Starting RabbitMQ container with image {}", ContainerImages.RABBITMQ);
    this.container =
        new RabbitMQContainer(DockerImageName.parse(ContainerImages.RABBITMQ))
            // Not .withUser(): that helper creates an ADDITIONAL user via `rabbitmqadmin declare
            // user` over docker exec, using flag syntax the image's bundled rabbitmqadmin v2
            // (RabbitMQ 4.x) rejects — it fails silently (Testcontainers only logs the error),
            // and even a successful declare would create the user with no vhost permissions,
            // which withUser() never grants either. withAdminUser/withAdminPassword instead set
            // the fields RabbitMQContainer.configure() itself writes into RABBITMQ_DEFAULT_USER/
            // RABBITMQ_DEFAULT_PASS, so "funds" becomes the seeded admin at first boot — plain
            // .withEnv() of the same two keys does not work here, because configure() sets them
            // from these fields unconditionally, overwriting any value set directly.
            .withAdminUser("funds")
            .withAdminPassword("funds_dev_pass")
            .withPluginsEnabled("rabbitmq_consistent_hash_exchange")
            .withReuse(true)
            .withLogConsumer(new Slf4jLogConsumer(LOGGER));
    this.container.start();
    LOGGER.info(
        "RabbitMQ container started at AMQP {}:{}, Mgmt {}:{}",
        getHost(),
        getAmqpPort(),
        getHost(),
        getManagementPort());
  }

  public static RabbitMqFixture getInstance() {
    if (instance == null) {
      synchronized (RabbitMqFixture.class) {
        if (instance == null) {
          instance = new RabbitMqFixture();
        }
      }
    }
    return instance;
  }

  public String getContainerId() {
    return container.getContainerId();
  }

  public String getHost() {
    return container.getHost();
  }

  public int getAmqpPort() {
    return container.getAmqpPort();
  }

  public int getPort() {
    return container.getAmqpPort();
  }

  public int getManagementPort() {
    return container.getHttpPort();
  }

  public String getUsername() {
    return "funds";
  }

  public String getPassword() {
    return "funds_dev_pass";
  }

  public String getAmqpUrl() {
    return "amqp://" + getUsername() + ":" + getPassword() + "@" + getHost() + ":" + getAmqpPort();
  }

  public synchronized void reset() {
    LOGGER.info("Resetting RabbitMQ queues via management API...");
    try {
      HttpClient client = HttpClient.newHttpClient();
      String authHeader =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString(
                      (getUsername() + ":" + getPassword()).getBytes(StandardCharsets.UTF_8));

      HttpRequest getQueuesReq =
          HttpRequest.newBuilder()
              .uri(URI.create("http://" + getHost() + ":" + getManagementPort() + "/api/queues"))
              .header("Authorization", authHeader)
              .GET()
              .build();
      HttpResponse<String> response =
          client.send(getQueuesReq, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) {
        // A non-200 here (401 in particular) previously fell through to the regex below,
        // matched nothing, and reset() returned having silently reset nothing at all —
        // exactly how the funds/funds_dev_pass credential regression above went unnoticed.
        throw new IllegalStateException(
            "GET /api/queues returned "
                + response.statusCode()
                + ", expected 200: "
                + response.body());
      }

      Matcher matcher = Pattern.compile("\"name\":\"([^\"]+)\"").matcher(response.body());
      while (matcher.find()) {
        String queueName = matcher.group(1);
        HttpRequest deleteReq =
            HttpRequest.newBuilder()
                .uri(
                    URI.create(
                        "http://"
                            + getHost()
                            + ":"
                            + getManagementPort()
                            + "/api/queues/%2F/"
                            + queueName))
                .header("Authorization", authHeader)
                .DELETE()
                .build();
        client.send(deleteReq, HttpResponse.BodyHandlers.ofString());
        LOGGER.info("Deleted RabbitMQ queue: {}", queueName);
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to reset RabbitMQ container", e);
    }
  }
}
