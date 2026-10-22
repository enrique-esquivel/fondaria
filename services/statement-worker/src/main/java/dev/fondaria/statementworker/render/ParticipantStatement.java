package dev.fondaria.statementworker.render;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import dev.fondaria.common.ParticipantId;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.Objects;

/**
 * The waterfall {@code ParticipantStatement} names ({@code contracts/openapi/balance-api.yaml}):
 * opening balance, contributions by source, withdrawals, fees, tax, market movement, closing
 * balance (Task 7.5b, {@code docs/DOMAIN.md} §4.2).
 *
 * <p>{@code withdrawalsCents}, {@code feesCents} and {@code taxWithheldCents} are always zero in
 * v1: Phase 22 (withdrawals, fees, tax withholding) is unbuilt, and {@code ADR-0022} §4 is explicit
 * that this task computes the waterfall from what exists today rather than deferring statements
 * until Phase 22 lands. They are carried as real fields, not omitted, so the contract does not
 * change shape the day Phase 22 starts populating them.
 *
 * <h2>The JSON is rendered field by field, not reflected off the record</h2>
 *
 * <p>Same discipline as {@code SnapshotManifest#render} and {@code CloseSignedOffEvent#toJson}:
 * {@link #render} writes each field explicitly and in a fixed order, so the stored bytes — and
 * therefore the hash — are a property of this method, never of a serializer's own field ordering.
 * This is what makes "rerun produces an identical hash" (Task 7.5b's own acceptance criterion)
 * hold: the same {@link StatementJob} always renders to the same bytes, because every input here is
 * either a number carried on that job or {@link #FOOTER_DISCLOSURE}, a fixed authored string —
 * never the wall clock.
 */
public record ParticipantStatement(
    ParticipantId participantId,
    String period,
    long openingBalanceCents,
    long contributionsEmployeeCents,
    long contributionsEmployerCents,
    long contributionsVoluntaryCents,
    long withdrawalsCents,
    long feesCents,
    long taxWithheldCents,
    long marketMovementCents,
    long closingBalanceCents,
    Instant generatedAt) {

  /**
   * The example text already committed to {@code contracts/openapi/balance-api.yaml}'s {@code
   * ParticipantStatement.footerDisclosure} — authored Spanish, naming {@code
   * docs/rounding-policy.md} as the source of the unit-rounding rule (AGENTS.md §7: user-facing
   * text is never built from raw figures). Reused verbatim rather than re-authored, so the
   * contract's own documented example and what this service actually renders are the same sentence.
   */
  public static final String FOOTER_DISCLOSURE =
      "El remanente de redondeo de unidades no se asigna a los participantes; ver"
          + " docs/rounding-policy.md.";

  public ParticipantStatement {
    Objects.requireNonNull(participantId, "participantId must not be null");
    Objects.requireNonNull(period, "period must not be null");
    Objects.requireNonNull(generatedAt, "generatedAt must not be null");
  }

  /**
   * Builds a statement, computing the market-movement plug rather than accepting it — the same
   * reasoning {@code SnapshotPosition.of} gives for computing {@code value} and {@code
   * unrealisedGain}: the one arithmetic this class exists to record must not be suppliable as an
   * inconsistent number.
   *
   * <p>{@code marketMovement = closing − opening − contributions + withdrawals + fees + tax}
   * ({@code contracts/openapi/balance-api.yaml}'s own definition). Signed and unclamped (AGENTS.md
   * §4, {@code docs/DOMAIN.md} §5.1) — a falling NAV makes this negative, and nothing here or
   * downstream may floor it at zero.
   */
  public static ParticipantStatement of(
      ParticipantId participantId,
      String period,
      long openingBalanceCents,
      long contributionsEmployeeCents,
      long contributionsEmployerCents,
      long contributionsVoluntaryCents,
      long closingBalanceCents,
      Instant generatedAt) {
    long withdrawalsCents = 0L;
    long feesCents = 0L;
    long taxWithheldCents = 0L;
    long contributions =
        contributionsEmployeeCents + contributionsEmployerCents + contributionsVoluntaryCents;
    long marketMovementCents =
        closingBalanceCents
            - openingBalanceCents
            - contributions
            + withdrawalsCents
            + feesCents
            + taxWithheldCents;
    return new ParticipantStatement(
        participantId,
        period,
        openingBalanceCents,
        contributionsEmployeeCents,
        contributionsEmployerCents,
        contributionsVoluntaryCents,
        withdrawalsCents,
        feesCents,
        taxWithheldCents,
        marketMovementCents,
        closingBalanceCents,
        generatedAt);
  }

  /** The statement's rendered bytes and their content hash — the object this is written as. */
  public RenderedObject render() {
    ByteArrayOutputStream out = new ByteArrayOutputStream(512);
    try (JsonGenerator json = new JsonFactory().createGenerator(out)) {
      json.writeStartObject();
      json.writeStringField("participantId", participantId.value().toString());
      json.writeStringField("period", period);
      json.writeNumberField("openingBalanceCents", openingBalanceCents);
      json.writeNumberField("contributionsEmployeeCents", contributionsEmployeeCents);
      json.writeNumberField("contributionsEmployerCents", contributionsEmployerCents);
      json.writeNumberField("contributionsVoluntaryCents", contributionsVoluntaryCents);
      json.writeNumberField("withdrawalsCents", withdrawalsCents);
      json.writeNumberField("feesCents", feesCents);
      json.writeNumberField("taxWithheldCents", taxWithheldCents);
      json.writeNumberField("marketMovementCents", marketMovementCents);
      json.writeNumberField("closingBalanceCents", closingBalanceCents);
      json.writeStringField("generatedAt", generatedAt.toString());
      json.writeStringField("footerDisclosure", FOOTER_DISCLOSURE);
      json.writeEndObject();
    } catch (IOException e) {
      throw new UncheckedIOException("could not render a participant statement", e);
    }
    return RenderedObject.of(out.toByteArray());
  }
}
