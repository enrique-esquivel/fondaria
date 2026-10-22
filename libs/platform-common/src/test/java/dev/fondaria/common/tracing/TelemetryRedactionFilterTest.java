package dev.fondaria.common.tracing;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A forbidden field surviving into an exported span attribute or metric tag is a privacy incident,
 * not a bug report — every case here is a field {@code AGENTS.md} §7 names explicitly.
 */
class TelemetryRedactionFilterTest {

  private final TelemetryRedactionFilter filter = new TelemetryRedactionFilter();

  @Test
  @DisplayName("strips a low-cardinality key naming an amount")
  void stripsAmount() {
    Observation.Context context = new Observation.Context();
    context.addLowCardinalityKeyValue(KeyValue.of("amountMinorUnits", "150000"));
    context.addLowCardinalityKeyValue(KeyValue.of("code", "CASH_RECEIPT"));

    filter.map(context);

    assertThat(context.getLowCardinalityKeyValues())
        .extracting(KeyValue::getKey)
        .containsExactly("code");
  }

  @Test
  @DisplayName("strips a high-cardinality key naming a token, RFC or email")
  void stripsHighCardinalityPii() {
    Observation.Context context = new Observation.Context();
    context.addHighCardinalityKeyValue(KeyValue.of("bearerToken", "abc.def.ghi"));
    context.addHighCardinalityKeyValue(KeyValue.of("operatorRfc", "ACM010101AB1"));
    context.addHighCardinalityKeyValue(KeyValue.of("operatorEmail", "ops@example.com"));
    context.addHighCardinalityKeyValue(KeyValue.of("transferId", "42"));

    filter.map(context);

    assertThat(context.getHighCardinalityKeyValues())
        .extracting(KeyValue::getKey)
        .containsExactly("transferId");
  }

  @Test
  @DisplayName("an opaque identifier such as participantId or accountId is never stripped")
  void keepsOpaqueIdentifiers() {
    Observation.Context context = new Observation.Context();
    context.addLowCardinalityKeyValue(KeyValue.of("participantId", "9c1f..."));
    context.addLowCardinalityKeyValue(KeyValue.of("accountId", "42"));

    filter.map(context);

    assertThat(context.getLowCardinalityKeyValues())
        .extracting(KeyValue::getKey)
        .containsExactlyInAnyOrder("participantId", "accountId");
  }

  @Test
  @DisplayName("matching is case-insensitive")
  void caseInsensitive() {
    Observation.Context context = new Observation.Context();
    context.addLowCardinalityKeyValue(KeyValue.of("CustomerNAME", "Acme"));

    filter.map(context);

    assertThat(context.getLowCardinalityKeyValues()).isEmpty();
  }

  @Test
  @DisplayName("a hyphenated key such as an HTTP header attribute is still matched")
  void stripsHyphenatedKeys() {
    Observation.Context context = new Observation.Context();
    context.addHighCardinalityKeyValue(
        KeyValue.of("http.request.header.x-idempotency-key", "key-123"));
    context.addHighCardinalityKeyValue(KeyValue.of("http.route", "/v1/cash-receipts"));

    filter.map(context);

    assertThat(context.getHighCardinalityKeyValues())
        .extracting(KeyValue::getKey)
        .containsExactly("http.route");
  }
}
