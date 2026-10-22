package dev.fondaria.eodjob.custodian;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CustodianStatementIngestionServiceTest {

  private final InMemoryCustodianDirectories.Statements statements =
      new InMemoryCustodianDirectories.Statements();
  private final CustodianStatementIngestionService ingestion =
      new CustodianStatementIngestionService(
          statements, Clock.fixed(Instant.parse("2026-09-04T14:00:00Z"), ZoneOffset.UTC));

  @Test
  void insertsANewStatement() {
    CustodianStatementSubmission submission = aSubmission("checksum-1");

    CustodianStatement stored = ingestion.submit(submission);

    assertThat(stored.providerAccountId()).isEqualTo(submission.providerAccountId());
    assertThat(statements.findFor(submission.providerAccountId(), submission.businessDate()))
        .contains(stored);
  }

  @Test
  void resubmittingTheSameFileIsIdempotent() {
    CustodianStatementSubmission submission = aSubmission("checksum-1");
    CustodianStatement first = ingestion.submit(submission);

    CustodianStatement second = ingestion.submit(submission);

    assertThat(second.statementId())
        .as("the same file arriving twice returns the row already on record, not a second one")
        .isEqualTo(first.statementId());
  }

  @Test
  void aDifferingResubmissionForTheSameDateIsRefused() {
    UUID providerAccountId = UUID.randomUUID();
    LocalDate businessDate = LocalDate.of(2026, 9, 4);
    ingestion.submit(aSubmission(providerAccountId, businessDate, "checksum-1"));

    assertThatThrownBy(
            () -> ingestion.submit(aSubmission(providerAccountId, businessDate, "checksum-2")))
        .isInstanceOf(StatementConflictException.class);
  }

  private static CustodianStatementSubmission aSubmission(String checksum) {
    return aSubmission(UUID.randomUUID(), LocalDate.of(2026, 9, 4), checksum);
  }

  private static CustodianStatementSubmission aSubmission(
      UUID providerAccountId, LocalDate businessDate, String checksum) {
    return new CustodianStatementSubmission(
        providerAccountId, businessDate, 1_000L, 500_00L, 0L, 0L, "provider-file.csv", checksum);
  }
}
