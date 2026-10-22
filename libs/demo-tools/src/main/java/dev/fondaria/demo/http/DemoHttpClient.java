package dev.fondaria.demo.http;

import dev.fondaria.demo.json.Json;
import dev.fondaria.demo.json.JsonParser;
import dev.fondaria.demo.k8s.Kubectl;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The only way this module talks to the platform (`phase-D-demo.md`: "everything below is driven
 * through the NodePorts ... exactly as a customer integration would"). Every call carries an
 * explicit timeout (`AGENTS.md` §4) and nothing here retries -- a retry would hide a platform fault
 * rather than report it (Task D.2's own requirement).
 */
public final class DemoHttpClient implements AutoCloseable {

  private final HttpClient client;
  private final Duration requestTimeout;
  private final Supplier<String> bearerTokenSupplier;
  private final Kubectl kubectl;

  public DemoHttpClient(Duration connectTimeout, Duration requestTimeout) {
    this(connectTimeout, requestTimeout, null, null);
  }

  /**
   * @param bearerTokenSupplier called once per request to mint the {@code Authorization} header
   *     (Task D.3, `ADR-0026`) -- {@code null} for calls that need none, e.g. `valuation-api`,
   *     which carries no `AuthorisationEnforcer` yet. Never applied to {@link #putBytes}: that path
   *     uploads to a presigned object-store URL, which authenticates itself and does not expect
   *     this platform's bearer token at all.
   * @param kubectl used only by {@link #putBytes}, to reach the object store's presigned-upload
   *     host (Task D.3) -- {@code null} disables that path (an attempted upload then fails as it
   *     always did, DNS resolution of the internal service name).
   */
  public DemoHttpClient(
      Duration connectTimeout,
      Duration requestTimeout,
      Supplier<String> bearerTokenSupplier,
      Kubectl kubectl) {
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Objects.requireNonNull(connectTimeout, "connectTimeout"))
            .build();
    this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout");
    this.bearerTokenSupplier = bearerTokenSupplier;
    this.kubectl = kubectl;
  }

  public static DemoHttpClient standard() {
    return new DemoHttpClient(Duration.ofSeconds(5), Duration.ofSeconds(30));
  }

  public static DemoHttpClient authenticated(Supplier<String> bearerTokenSupplier) {
    return new DemoHttpClient(
        Duration.ofSeconds(5),
        Duration.ofSeconds(30),
        bearerTokenSupplier,
        new Kubectl(env("KUBE_CONTEXT", "kind-fondaria"), env("K8S_NAMESPACE", "fondaria")));
  }

  private static String env(String variable, String defaultValue) {
    String value = System.getenv(variable);
    return (value == null || value.isBlank()) ? defaultValue : value;
  }

  public JsonResponse postJson(URI uri, Object requestBody) {
    return postJson(uri, requestBody, Map.of());
  }

  /** As {@link #postJson(URI, Object)}, with additional headers (e.g. {@code Idempotency-Key}). */
  public JsonResponse postJson(URI uri, Object requestBody, Map<String, String> extraHeaders) {
    String json = Json.write(requestBody);
    HttpRequest.Builder builder =
        baseRequest(uri)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
    extraHeaders.forEach(builder::header);
    return send("POST", uri, builder.build());
  }

  public JsonResponse getJson(URI uri) {
    HttpRequest request = baseRequest(uri).GET().build();
    return send("GET", uri, request);
  }

  /**
   * Uploads raw bytes to a presigned URL. Not JSON, and the platform never sees this call.
   *
   * <p>The URL's host is the object store's <em>internal</em> service name (e.g. {@code
   * object-store}) -- `contribution-api`/`valuation-api` presign against the same endpoint they use
   * for their own reads, and this module runs on the operator's machine, outside cluster DNS (Task
   * D.3). When a {@link Kubectl} is available, this opens a transient port-forward to that service
   * name and connects there instead -- but the AWS SigV4 signature in the URL's query string covers
   * the {@code Host} header (`SignedHeaders=host`), so the request must still present the
   * *original* host:port, not `localhost`, or the object store recomputes a different signature and
   * rejects it. Setting that requires {@code -Djdk.httpclient.allowRestrictedHeaders=host} (set
   * once, in {@code DemoTools.main}, before any {@link HttpClient} is built).
   */
  public int putBytes(URI uri, byte[] content) {
    if (kubectl == null) {
      return sendPut(uri, content);
    }
    String service = uri.getHost();
    int remotePort = uri.getPort();
    int localPort = 28000 + Math.abs(service.hashCode() % 1000);
    try (Kubectl.PortForward forward = kubectl.portForward(service, localPort, remotePort)) {
      String query = uri.getRawQuery();
      URI local =
          URI.create(
              uri.getScheme()
                  + "://localhost:"
                  + localPort
                  + uri.getRawPath()
                  + (query != null ? "?" + query : ""));
      return sendPut(local, content, uri.getHost() + ":" + uri.getPort());
    }
  }

  private int sendPut(URI uri, byte[] content) {
    return sendPut(uri, content, null);
  }

  private int sendPut(URI uri, byte[] content, String hostHeaderOverride) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri)
            .timeout(requestTimeout)
            .PUT(HttpRequest.BodyPublishers.ofByteArray(content));
    if (hostHeaderOverride != null) {
      builder.header("Host", hostHeaderOverride);
    }
    try {
      return client.send(builder.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    } catch (IOException e) {
      throw new DemoHttpException("PUT", uri.toString(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DemoHttpException("PUT", uri.toString(), e);
    }
  }

  private HttpRequest.Builder baseRequest(URI uri) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(uri).timeout(requestTimeout).header("Accept", "application/json");
    if (bearerTokenSupplier != null) {
      builder.header("Authorization", "Bearer " + bearerTokenSupplier.get());
    }
    return builder;
  }

  private JsonResponse send(String method, URI uri, HttpRequest request) {
    HttpResponse<String> response;
    try {
      response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new DemoHttpException(method, uri.toString(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DemoHttpException(method, uri.toString(), e);
    }
    int status = response.statusCode();
    String body = response.body();
    if (status >= 400) {
      throw new DemoHttpException(method, uri.toString(), status, body);
    }
    Map<String, Object> parsed =
        (body == null || body.isBlank()) ? Map.of() : JsonParser.parseObject(body);
    return new JsonResponse(status, parsed);
  }

  @Override
  public void close() {
    client.close();
  }
}
