package dev.fondaria.statementworker.schedule;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.statementworker.ParticipantKey;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * One message on {@code statement.jobs.v1} (Task 7.5b, {@code ADR-0022}): everything a worker needs
 * to render one participant's monthly waterfall, with the opening/closing values and the
 * contribution split already summed by {@link StatementScheduler} from the snapshot files and
 * {@code workflow.allotment}.
 *
 * <h2>Why the scheduler computes the sums and not the worker</h2>
 *
 * <p>Both snapshot files and the month's whole {@code workflow.allotment} range are read exactly
 * once, by the scheduler, streaming — never once per participant. Sizing the read to "once per job"
 * instead would make each of 250,000 jobs re-open and re-scan gigabyte-scale files, which is
 * quadratic in the participant count and is exactly what Task 7.5b's throughput criterion (a month
 * completing inside one simulated day's batch window, via added consumers) rules out. What the
 * worker still does per job is the part that genuinely parallelises: computing the market-movement
 * plug, resolving the external {@code participantId}, rendering the deterministic bytes, hashing
 * them, writing the object and recording completion — each one independent of every other worker's
 * job, which is what "using additional consumers" actually buys.
 *
 * @param generatedAt the close's {@code signedOffAt} (Task 7.5a) — not the render's own wall-clock
 *     time. {@code ParticipantStatement.generatedAt} must be a property of the book, not of when a
 *     worker happened to process the job, or a redelivered job would render different bytes on its
 *     second attempt and Task 7.5b's "rerun produces an identical hash" criterion would fail on the
 *     one path that most needs it to hold.
 */
public record StatementJob(
    OrganizationId organizationId,
    String participantInternalRef,
    String period,
    Instant generatedAt,
    long openingCents,
    long contributionsEmployeeCents,
    long contributionsEmployerCents,
    long contributionsVoluntaryCents,
    long closingCents,
    CurrencyCode currency) {

  public StatementJob {
    Objects.requireNonNull(organizationId, "organizationId must not be null");
    Objects.requireNonNull(participantInternalRef, "participantInternalRef must not be null");
    Objects.requireNonNull(period, "period must not be null");
    Objects.requireNonNull(generatedAt, "generatedAt must not be null");
    Objects.requireNonNull(currency, "currency must not be null");
  }

  public ParticipantKey participantKey() {
    return new ParticipantKey(organizationId, participantInternalRef);
  }

  public String toJson() {
    ByteArrayOutputStream out = new ByteArrayOutputStream(256);
    try (JsonGenerator json = new JsonFactory().createGenerator(out)) {
      json.writeStartObject();
      json.writeStringField("schemaVersion", StatementJobsTopic.SCHEMA_VERSION);
      json.writeStringField("organizationId", organizationId.value().toString());
      json.writeStringField("participantInternalRef", participantInternalRef);
      json.writeStringField("period", period);
      json.writeStringField("generatedAt", generatedAt.toString());
      json.writeNumberField("openingCents", openingCents);
      json.writeNumberField("contributionsEmployeeCents", contributionsEmployeeCents);
      json.writeNumberField("contributionsEmployerCents", contributionsEmployerCents);
      json.writeNumberField("contributionsVoluntaryCents", contributionsVoluntaryCents);
      json.writeNumberField("closingCents", closingCents);
      json.writeStringField("currency", currency.name());
      json.writeEndObject();
    } catch (IOException e) {
      throw new UncheckedIOException("could not render a statement.jobs.v1 message", e);
    }
    return out.toString(StandardCharsets.UTF_8);
  }

  /**
   * @throws MalformedStatementJobException if the message cannot be decoded — treated as poison.
   */
  public static StatementJob fromJson(ObjectMapper objectMapper, String json) {
    try {
      JsonNode node = objectMapper.readTree(json);
      return new StatementJob(
          new OrganizationId(UUID.fromString(node.get("organizationId").asText())),
          node.get("participantInternalRef").asText(),
          node.get("period").asText(),
          Instant.parse(node.get("generatedAt").asText()),
          node.get("openingCents").asLong(),
          node.get("contributionsEmployeeCents").asLong(),
          node.get("contributionsEmployerCents").asLong(),
          node.get("contributionsVoluntaryCents").asLong(),
          node.get("closingCents").asLong(),
          CurrencyCode.valueOf(node.get("currency").asText()));
    } catch (IOException | RuntimeException e) {
      throw new MalformedStatementJobException("could not decode a statement.jobs.v1 message", e);
    }
  }
}
