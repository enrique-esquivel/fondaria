package dev.fondaria.contribution.participant.identity;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.participant.Participant;
import dev.fondaria.contribution.participant.ParticipantNotFoundException;
import dev.fondaria.contribution.participant.ParticipantRepository;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Task 9.5: captures a participant's RFC, CURP, name and date of birth, encrypted per participant
 * (envelope encryption), and crypto-shreds them on request.
 *
 * <p><b>The vehicle named {@code "RETIREMENT_FUND"} is where date of birth is required</b> — its
 * plan's fund is resolved as of {@code asOfDate}, the same "highest {@code (effectiveFrom,
 * version)} not after the date" resolution every other effective-dated lookup in this codebase
 * uses. The outcome and {@code asOfDate} are both stored on the row itself ({@link
 * ParticipantIdentity#dateOfBirthRequired()}, {@link ParticipantIdentity#identityCapturedAsOf()}),
 * so the decision is reproducible years later without re-running current code or depending on
 * today's fund configuration (AGENTS.md §4). {@code vehicleType} is a free-text configuration
 * value, not an enum ({@code reference.fund}'s own design) — a retirement fund registered under any
 * spelling other than exactly {@code "RETIREMENT_FUND"} silently resolves to "not required" rather
 * than failing loudly; this mirrors the same exact-match convention {@code
 * OrganizationPlanRules#isRetirement} already uses in {@code libs/demo-tools}, and closing that gap
 * for every caller is a larger change (a real vehicle-type registry) than this task's scope.
 */
@Component
public class ParticipantIdentityService {

  private static final String RETIREMENT_VEHICLE = "RETIREMENT_FUND";

  private final ParticipantRepository participantRepository;
  private final ParticipantIdentityRepository identityRepository;
  private final PlanVersionRepository planVersionRepository;
  private final FundRepository fundRepository;
  private final ParticipantIdentityMasterKey masterKey;
  private final Clock clock;
  private final IdentityCipher cipher = new IdentityCipher();

  public ParticipantIdentityService(
      ParticipantRepository participantRepository,
      ParticipantIdentityRepository identityRepository,
      PlanVersionRepository planVersionRepository,
      FundRepository fundRepository,
      ParticipantIdentityMasterKey masterKey,
      Clock clock) {
    this.participantRepository = participantRepository;
    this.identityRepository = identityRepository;
    this.planVersionRepository = planVersionRepository;
    this.fundRepository = fundRepository;
    this.masterKey = masterKey;
    this.clock = clock;
  }

  /**
   * Captures (or wholly replaces) a participant's identity fields. Generates a fresh data key every
   * call, so a resubmission — a correction — never reuses key material an earlier submission's key
   * might already have been destroyed under.
   *
   * <p><b>Refuses to run over an already crypto-shredded participant.</b> {@code
   * master.participant_identity} has no separate append-only erasure record, so {@code
   * key_destroyed_at} is the only evidence a shred ever happened; letting a later capture silently
   * clear it back to {@code NULL} would make a completed right-to-erasure request invisibly
   * reversible by anyone still holding capture access (an employer admin, in particular, who was
   * never the one who could destroy the key in the first place — see {@code
   * PopulationAccessControl#assertCanDestroyIdentityKey}). Re-establishing identity for a
   * previously-erased participant is presumably a real business need eventually, but it is a
   * distinct, deliberate operation this method does not perform implicitly.
   */
  public void captureIdentity(
      OrganizationId organizationId, String internalRef, IdentityCaptureCommand command) {
    Participant participant =
        participantRepository
            .find(organizationId, internalRef)
            .orElseThrow(ParticipantNotFoundException::new);

    command.requireFieldsPresent();
    LocalDate asOfDate = LocalDate.now(clock);
    boolean dateOfBirthRequired =
        isRetirementVehicle(organizationId, participant.planId(), asOfDate);
    command.requireDateOfBirthIfNeeded(dateOfBirthRequired);

    Optional<ParticipantIdentity> existing = identityRepository.find(organizationId, internalRef);
    if (existing.map(i -> i.keyDestroyedAt().isPresent()).orElse(false)) {
      throw new IdentityKeyDestroyedException();
    }

    String aad = aad(organizationId, internalRef);
    byte[] dataKey = cipher.generateDataKey();
    try {
      byte[] wrappedKey = cipher.seal(masterKey.bytes(), dataKey, aad + ":dek");
      byte[] encryptedRfc = seal(dataKey, aad, "rfc", command.rfc());
      byte[] encryptedCurp = seal(dataKey, aad, "curp", command.curp());
      byte[] encryptedName = seal(dataKey, aad, "name", command.name());
      Optional<byte[]> encryptedDateOfBirth =
          command.dateOfBirth().map(dob -> seal(dataKey, aad, "dob", dob.toString()));

      Instant now = clock.instant();
      Instant createdAt = existing.map(ParticipantIdentity::createdAt).orElse(now);

      identityRepository.upsert(
          new ParticipantIdentity(
              organizationId,
              internalRef,
              Optional.of(wrappedKey),
              encryptedRfc,
              encryptedCurp,
              encryptedName,
              encryptedDateOfBirth,
              dateOfBirthRequired,
              asOfDate,
              createdAt,
              now,
              Optional.empty()));
    } finally {
      Arrays.fill(dataKey, (byte) 0);
    }
  }

  /**
   * Crypto-shreds this participant's data key. Idempotent: shredding an already-shredded
   * participant, or one who was never captured to begin with, both return normally — right-to-
   * erasure is a state to reach, not a transition that can fail by already being there. {@link
   * ParticipantIdentityNotFoundException} is reserved for a participant with no identity row at
   * all.
   */
  public void destroyKey(OrganizationId organizationId, String internalRef) {
    ParticipantIdentity existing =
        identityRepository
            .find(organizationId, internalRef)
            .orElseThrow(ParticipantIdentityNotFoundException::new);
    if (existing.keyDestroyedAt().isPresent()) {
      return;
    }
    identityRepository.destroyKey(organizationId, internalRef, clock.instant());
  }

  /**
   * Decrypts and returns this participant's identity fields. Not exposed by any controller — see
   * {@link DecryptedIdentity}. Throws {@link IdentityKeyDestroyedException} once {@link
   * #destroyKey} has run for this participant.
   */
  public DecryptedIdentity readIdentity(OrganizationId organizationId, String internalRef) {
    ParticipantIdentity stored =
        identityRepository
            .find(organizationId, internalRef)
            .orElseThrow(ParticipantIdentityNotFoundException::new);
    byte[] wrappedKey = stored.wrappedDataKey().orElseThrow(IdentityKeyDestroyedException::new);

    String aad = aad(organizationId, internalRef);
    byte[] dataKey = cipher.open(masterKey.bytes(), wrappedKey, aad + ":dek");
    try {
      String rfc = open(dataKey, aad, "rfc", stored.encryptedRfc());
      String curp = open(dataKey, aad, "curp", stored.encryptedCurp());
      String name = open(dataKey, aad, "name", stored.encryptedName());
      Optional<LocalDate> dateOfBirth =
          stored.encryptedDateOfBirth().map(ct -> LocalDate.parse(open(dataKey, aad, "dob", ct)));
      return new DecryptedIdentity(rfc, curp, name, dateOfBirth);
    } finally {
      Arrays.fill(dataKey, (byte) 0);
    }
  }

  private boolean isRetirementVehicle(
      OrganizationId organizationId, String planId, LocalDate asOfDate) {
    PlanVersion planVersion =
        planVersionRepository
            .resolveAsOf(organizationId, planId, asOfDate)
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "no plan version resolves for an enrolled participant's own plan"));
    Fund fund =
        fundRepository
            .resolveAsOf(planVersion.fundId(), asOfDate)
            .orElseThrow(
                () ->
                    new IllegalStateException("no fund version resolves for the plan's own fund"));
    return RETIREMENT_VEHICLE.equals(fund.vehicleType());
  }

  private static String aad(OrganizationId organizationId, String internalRef) {
    return organizationId.value() + ":" + internalRef;
  }

  private byte[] seal(byte[] dataKey, String aad, String field, String plaintext) {
    return cipher.seal(dataKey, plaintext.getBytes(StandardCharsets.UTF_8), aad + ":" + field);
  }

  private String open(byte[] dataKey, String aad, String field, byte[] sealed) {
    return new String(cipher.open(dataKey, sealed, aad + ":" + field), StandardCharsets.UTF_8);
  }
}
