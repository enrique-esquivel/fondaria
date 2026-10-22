package dev.fondaria.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.ParticipantId;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;

/**
 * The Query API as a caller sees it: over HTTP, against the running {@code balance-api}, with the
 * <b>raw</b> response body kept alongside the parsed one.
 *
 * <p>The raw body matters to Task 6.6's own criterion — "no endpoint returns a float in JSON,
 * asserted on the raw body". Binding the response to its record would hide exactly what the
 * criterion asks about: Jackson reads {@code 45000.0} into a {@code long} field without complaint,
 * so a float that reached the wire would be invisible to every assertion made afterwards. The bytes
 * are the only place that question can be answered.
 */
final class QueryApi {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private final PlatformStack stack;

  QueryApi(PlatformStack stack) {
    this.stack = stack;
  }

  record Response(String path, int status, String body) {

    JsonNode json() {
      try {
        return MAPPER.readTree(body);
      } catch (Exception e) {
        throw new IllegalStateException("response from " + path + " is not JSON: " + body, e);
      }
    }

    long asLong(String field) {
      return json().get(field).asLong();
    }
  }

  Response balance(ParticipantId participantId) {
    return get("/v1/participants/" + participantId.value() + "/balance");
  }

  Response movements(ParticipantId participantId) {
    return get("/v1/participants/" + participantId.value() + "/movements");
  }

  Response balanceAsOf(ParticipantId participantId, LocalDate date) {
    return get("/v1/participants/" + participantId.value() + "/balance-as-of/" + date);
  }

  Response summary(OrganizationId organizationId) {
    return get("/v1/organizations/" + organizationId.value() + "/summary");
  }

  Response get(String path) {
    try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
      HttpResponse<String> response =
          http.send(
              HttpRequest.newBuilder(URI.create(stack.balanceApiBaseUrl() + path))
                  .timeout(Duration.ofSeconds(30))
                  .header("Accept", "application/json")
                  .GET()
                  .header("Authorization", PlatformStack.internalServiceAuthorization())
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      return new Response(path, response.statusCode(), response.body());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted calling " + path, e);
    } catch (Exception e) {
      throw new IllegalStateException("could not call " + path, e);
    }
  }
}
