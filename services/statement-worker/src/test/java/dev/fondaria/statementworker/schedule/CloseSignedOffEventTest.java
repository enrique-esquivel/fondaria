package dev.fondaria.statementworker.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Decoding the wire shape {@code eod-job}'s {@code CloseSignedOffEvent} actually produces. */
class CloseSignedOffEventTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private static final String VALID_MESSAGE =
      """
      {
        "schemaVersion": "v1",
        "businessDate": "2026-08-31",
        "signedOffAt": "2026-09-01T00:03:12.500000Z",
        "reconciliationRunId": "11111111-1111-1111-1111-111111111111",
        "custodianRunId": "22222222-2222-2222-2222-222222222222",
        "snapshotRunId": "33333333-3333-3333-3333-333333333333",
        "snapshotObjectKey": "snapshots/dt=2026-08-31/positions.ndjson.gz",
        "snapshotManifestKey": "snapshots/dt=2026-08-31/manifest.json",
        "snapshotPositionCount": 250000,
        "lastBusinessDayOfMonth": true
      }
      """;

  @Test
  @DisplayName("decodes every field this service reads")
  void decodesTheFieldsThisServiceReads() {
    CloseSignedOffEvent event = CloseSignedOffEvent.fromJson(MAPPER, VALID_MESSAGE);

    assertThat(event.isSupportedVersion()).isTrue();
    assertThat(event.businessDate().toString()).isEqualTo("2026-08-31");
    assertThat(event.period()).isEqualTo("2026-08");
    assertThat(event.snapshotObjectKey()).isEqualTo("snapshots/dt=2026-08-31/positions.ndjson.gz");
    assertThat(event.snapshotPositionCount()).isEqualTo(250_000L);
    assertThat(event.lastBusinessDayOfMonth()).isTrue();
  }

  @Test
  @DisplayName("a missing required field fails the decode rather than defaulting silently")
  void missingFieldFailsDecode() {
    String malformed = VALID_MESSAGE.replace("\"businessDate\": \"2026-08-31\",", "");
    assertThatThrownBy(() -> CloseSignedOffEvent.fromJson(MAPPER, malformed))
        .isInstanceOf(MalformedCloseSignedOffEventException.class);
  }

  @Test
  @DisplayName("an unrecognised schemaVersion is not a supported version")
  void unrecognisedSchemaVersion() {
    String v2 = VALID_MESSAGE.replace("\"schemaVersion\": \"v1\"", "\"schemaVersion\": \"v2\"");
    CloseSignedOffEvent event = CloseSignedOffEvent.fromJson(MAPPER, v2);
    assertThat(event.isSupportedVersion()).isFalse();
  }
}
