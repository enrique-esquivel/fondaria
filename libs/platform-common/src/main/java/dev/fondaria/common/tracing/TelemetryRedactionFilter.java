package dev.fondaria.common.tracing;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.Locale;
import java.util.Set;
import java.util.stream.StreamSupport;

/**
 * Strips any observation key-value whose key names a field {@code AGENTS.md} §7 forbids in a log
 * line — an observation materializes as both a span attribute and a metric tag, so this is the one
 * place both are covered.
 *
 * <p>Registered as an {@link ObservationFilter} bean in each service (Spring Boot auto-detects
 * every such bean and applies it to the shared {@code ObservationRegistry}), it runs on every
 * observation this platform creates or that an auto-instrumented library creates on our behalf —
 * Task 11.2's defense in depth against a future call site that adds a forbidden attribute by
 * accident, not the only line of defense (call sites are still expected to never pass one).
 *
 * <p>Matches by substring against a key with every non-alphanumeric character stripped, not an
 * exact key, deliberately: {@code amountMinorUnits}, {@code http.request.header.x-idempotency-key}
 * and {@code db.statement.name} are all denied by one fragment each ({@code amount}, {@code
 * idempotencykey}, {@code name}) without enumerating every separator style a library might choose —
 * {@code x-idempotency-key} would not contain {@code idempotencykey} without stripping its hyphens
 * first, exactly the shape OTel's own semantic conventions use for HTTP header attributes.
 */
public class TelemetryRedactionFilter implements ObservationFilter {

  private static final Set<String> DENIED_KEY_FRAGMENTS =
      Set.of(
          "amount",
          "cents",
          "units",
          "nav",
          "name",
          "rfc",
          "curp",
          "email",
          "iban",
          "token",
          "idempotencykey",
          "salary",
          "balance");

  @Override
  public Observation.Context map(Observation.Context context) {
    String[] deniedLow = deniedKeys(context.getLowCardinalityKeyValues());
    if (deniedLow.length > 0) {
      context.removeLowCardinalityKeyValues(deniedLow);
    }
    String[] deniedHigh = deniedKeys(context.getHighCardinalityKeyValues());
    if (deniedHigh.length > 0) {
      context.removeHighCardinalityKeyValues(deniedHigh);
    }
    return context;
  }

  private static String[] deniedKeys(Iterable<KeyValue> keyValues) {
    return StreamSupport.stream(keyValues.spliterator(), false)
        .map(KeyValue::getKey)
        .filter(TelemetryRedactionFilter::isDenied)
        .toArray(String[]::new);
  }

  private static boolean isDenied(String key) {
    String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    return DENIED_KEY_FRAGMENTS.stream().anyMatch(normalized::contains);
  }
}
