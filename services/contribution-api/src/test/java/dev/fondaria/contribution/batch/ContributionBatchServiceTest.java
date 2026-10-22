package dev.fondaria.contribution.batch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.contribution.config.IdentityProperties;
import dev.fondaria.contribution.objectstore.PresignedUpload;
import dev.fondaria.contribution.organization.FreshTaxIdentifiers;
import dev.fondaria.contribution.organization.OrganizationNotFoundException;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.contribution.organization.RegisterOrganizationCommand;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Submission's own behaviour: it is content-addressed, it never reads the file, and it refuses a
 * call it cannot safely serve.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ContributionBatchServiceTest {

  private static final Instant NOW = Instant.parse("2026-08-19T10:00:00Z");
  private static final String CHECKSUM =
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

  private final InMemoryContributionBatchRepository batches =
      new InMemoryContributionBatchRepository();
  private final StubObjectStore objectStore = new StubObjectStore();

  private OrganizationId organization;
  private ContributionBatchService service;

  @BeforeEach
  void setUp() {
    // A fresh tax identifier per test: fixtures are reused across runs, and an
    // organization a previous run registered would make these assertions meaningless.
    TaxIdentifier taxIdentifier = FreshTaxIdentifiers.fresh();
    UUID organizationNamespace = UUID.randomUUID();
    OrganizationService organizations =
        new OrganizationService(
            new StubOrganizationRepositoryForBatches(),
            new StubLedgerPort(),
            new dev.fondaria.ledger.AccountIdDeriver(UUID.randomUUID()),
            new IdentityProperties(organizationNamespace, UUID.randomUUID()),
            Clock.fixed(NOW, ZoneOffset.UTC));
    organizations.register(
        new RegisterOrganizationCommand(
            taxIdentifier, "Acme S.A. de C.V.", Optional.empty(), Optional.empty()));
    organization = OrganizationId.derive(organizationNamespace, taxIdentifier);
    service =
        new ContributionBatchService(
            batches, objectStore, organizations, Clock.fixed(NOW, ZoneOffset.UTC));
  }

  private String ownKey() {
    return ContributionFiles.objectKey(organization, "file.csv");
  }

  private PresignedUpload slot(OrganizationId organizationId, String fileName) {
    return service.createUploadSlot(
        organizationId, ContributionFiles.PLAN_ID, ContributionFiles.PAYROLL_PERIOD, fileName);
  }

  private ContributionBatch submit(String objectKey, String checksum) {
    return service.submit(
        organization, objectKey, checksum, ContributionFiles.workedExampleDeclaration());
  }

  @Test
  void a_submitted_batch_starts_as_received_and_nothing_has_been_read() {
    ContributionBatch batch = submit(ownKey(), CHECKSUM);

    assertThat(batch.status()).isEqualTo(ContributionBatchStatus.RECEIVED);
    assertThat(batch.declared()).isEqualTo(ContributionFiles.workedExampleDeclaration());
    assertThat(batch.rejection()).isEmpty();
  }

  @Test
  void resubmitting_the_identical_file_returns_the_original_batch_never_a_second_one() {
    // The acceptance criterion. There is no Idempotency-Key here: the checksum is the key.
    ContributionBatch first = submit(ownKey(), CHECKSUM);
    ContributionBatch second = submit(ownKey(), CHECKSUM);

    assertThat(second.batchId()).isEqualTo(first.batchId());
    assertThat(second.submittedAt()).isEqualTo(first.submittedAt());
  }

  @Test
  void a_resubmission_never_resets_a_batch_that_has_already_been_resolved() {
    ContributionBatch first = submit(ownKey(), CHECKSUM);
    batches.resolve(
        new ContributionBatch(
            first.batchId(),
            organization,
            first.objectKey(),
            first.checksumSha256(),
            ContributionBatchStatus.VALIDATING,
            first.declared(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            NOW,
            NOW));

    ContributionBatch again = submit(ownKey(), CHECKSUM);

    assertThat(again.batchId()).isEqualTo(first.batchId());
  }

  @Test
  void a_different_file_at_the_same_key_is_a_different_batch() {
    ContributionBatch first = submit(ownKey(), CHECKSUM);
    ContributionBatch second = submit(ownKey(), "1".repeat(64));

    assertThat(second.batchId()).isNotEqualTo(first.batchId());
  }

  @Test
  void an_object_key_belonging_to_another_organization_is_refused_before_anything_is_fetched() {
    // Without this, one employer could submit another's key and learn from the resulting
    // status whether that file exists and parses.
    String foreign = ContributionFiles.objectKey(new OrganizationId(UUID.randomUUID()), "file.csv");

    assertThatThrownBy(() -> submit(foreign, CHECKSUM))
        .isInstanceOf(InvalidBatchSubmissionException.class);
  }

  @Test
  void an_unregistered_organization_cannot_submit_a_batch() {
    assertThatThrownBy(
            () ->
                service.submit(
                    new OrganizationId(UUID.randomUUID()),
                    "contribution-batches/x/file.csv",
                    CHECKSUM,
                    ContributionFiles.workedExampleDeclaration()))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  @Test
  void an_unregistered_organization_is_never_issued_a_write_grant() {
    assertThatThrownBy(() -> slot(new OrganizationId(UUID.randomUUID()), "acme.csv"))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  @Test
  void an_upload_slot_is_issued_under_this_organizations_own_prefix() {
    PresignedUpload upload = slot(organization, "acme-2026-08-b.csv");

    assertThat(upload.objectKey())
        .as("the key carries the plan and payroll period, so a stored object says what it is")
        .startsWith(
            "contribution-batches/"
                + organization.value()
                + "/"
                + ContributionFiles.PLAN_ID
                + "/"
                + ContributionFiles.PAYROLL_PERIOD
                + "/")
        .endsWith("-acme-2026-08-b.csv");
  }

  @Test
  void two_upload_slots_never_collide_even_for_the_same_file_name() {
    String first = slot(organization, "acme.csv").objectKey();
    String second = slot(organization, "acme.csv").objectKey();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void a_file_name_can_never_escape_the_key_it_is_appended_to() {
    // The name is caller-controlled text. A key built naively from it could name any
    // object in the bucket.
    PresignedUpload upload = slot(organization, "../../../etc/passwd?x=1&y=2");

    assertThat(upload.objectKey())
        .startsWith("contribution-batches/" + organization.value() + "/")
        .doesNotContain("..")
        .doesNotContain("?")
        .doesNotContain("&");
    assertThat(upload.objectKey().chars().filter(c -> c == '/').count()).isEqualTo(4);
  }

  @Test
  void a_presigned_url_is_never_rendered_by_tostring() {
    // It is a signed credential with a short life, and a record's default toString would
    // put it in any log line that printed the object.
    PresignedUpload upload = slot(organization, "acme.csv");

    assertThat(upload.toString()).doesNotContain(upload.url().toString()).contains("REDACTED");
  }
}
