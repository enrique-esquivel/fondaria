package dev.fondaria.contribution.batch;

import dev.fondaria.common.Money;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.contribution.organization.OrganizationService;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.LedgerTransfer;
import dev.fondaria.ledger.TransferId;
import dev.fondaria.ledger.TransferIdDeriver;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The ageing report: money received with no file against it, and files with no money against them.
 *
 * <p>Both are first-class states rather than errors. Cash and file are independent arrivals — a
 * wire can land days before its dispersion file or days after it, and neither half is wrong for
 * being early. What is wrong is either half sitting unmatched for a long time, which is what this
 * report makes visible.
 *
 * <p><b>Every amount here comes from the ledger.</b> The unmatched-cash side reads each receipt's
 * transfer through {@link LedgerPort#lookupTransfer}; the unmatched-file side reports the total the
 * employer <i>declared</i>, clearly labelled as such. Nothing on this report is a sum over
 * PostgreSQL rows, and the two numbers are never added together — they are different kinds of fact.
 */
@Service
public class ReconciliationAgeingService {

  private final ContributionBatchRepository batches;
  private final CashClaimRepository claims;
  private final OrganizationService organizations;
  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;
  private final Clock clock;

  public ReconciliationAgeingService(
      ContributionBatchRepository batches,
      CashClaimRepository claims,
      OrganizationService organizations,
      LedgerPort ledger,
      AccountIdDeriver accountIds,
      Clock clock) {
    this.batches = batches;
    this.claims = claims;
    this.organizations = organizations;
    this.ledger = ledger;
    this.accountIds = accountIds;
    this.clock = clock;
  }

  /**
   * @throws dev.fondaria.contribution.organization.OrganizationNotFoundException no organization is
   *     registered under this id
   */
  public AgeingReport forOrganization(OrganizationId organizationId) {
    organizations.get(organizationId);
    Instant now = clock.instant();
    LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
    AccountId employerCollections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());

    List<UnmatchedCash> cash = new ArrayList<>();
    for (UnclaimedReceipt receipt : claims.unclaimed(organizationId)) {
      // A receipt the ledger cannot confirm is still reported — with no amount. It is
      // precisely the item an operator most needs to see, and dropping it would hide the
      // one discrepancy this report exists to surface.
      Optional<Money> amount = confirmed(receipt.transferId(), employerCollections);
      cash.add(
          new UnmatchedCash(
              TransferIdDeriver.toUuid(receipt.transferId()),
              receipt.valueDate(),
              amount.map(Money::cents),
              (int) ChronoUnit.DAYS.between(receipt.valueDate(), today)));
    }

    List<UnmatchedFile> files = new ArrayList<>();
    for (ContributionBatch batch : batches.pendingReconciliation(organizationId)) {
      files.add(
          new UnmatchedFile(
              batch.batchId(),
              batch.declared().payrollPeriod(),
              batch.declared().valueDate(),
              batch.declaredTotalCents(),
              batch.submittedAt(),
              (int) ChronoUnit.DAYS.between(batch.submittedAt(), now)));
    }

    return new AgeingReport(organizationId, now, List.copyOf(cash), List.copyOf(files));
  }

  private Optional<Money> confirmed(TransferId transferId, AccountId employerCollections) {
    return ledger
        .lookupTransfer(transferId)
        .filter(
            transfer ->
                transfer instanceof LedgerTransfer.Cash cash
                    && cash.transferCode() == LedgerCodes.TransferCode.CASH_RECEIPT
                    && cash.creditAccountId().equals(employerCollections))
        .map(transfer -> ((LedgerTransfer.Cash) transfer).amount());
  }

  /** One organization's outstanding halves, as of {@code asOf}. */
  public record AgeingReport(
      OrganizationId organizationId,
      Instant asOf,
      List<UnmatchedCash> unmatchedCash,
      List<UnmatchedFile> unmatchedFiles) {

    public AgeingReport {
      Objects.requireNonNull(organizationId, "organizationId must not be null");
      Objects.requireNonNull(asOf, "asOf must not be null");
      unmatchedCash = List.copyOf(unmatchedCash);
      unmatchedFiles = List.copyOf(unmatchedFiles);
    }
  }

  /**
   * Cash that arrived and no batch has claimed.
   *
   * @param amountCents MXN cents, read from the ledger. Empty when the ledger does not hold the
   *     movement this receipt claims — a discrepancy, shown rather than hidden.
   * @param ageDays days since the wire's value date
   */
  public record UnmatchedCash(
      UUID transferId, LocalDate valueDate, Optional<Long> amountCents, int ageDays) {}

  /**
   * A structurally sound file whose money has not arrived.
   *
   * @param declaredTotalCents what the file's header declared, in MXN cents. A declaration, never a
   *     balance — the whole reason this row is on the report is that no money matches it.
   * @param ageDays days since the batch was submitted
   */
  public record UnmatchedFile(
      UUID batchId,
      String payrollPeriod,
      LocalDate valueDate,
      long declaredTotalCents,
      Instant submittedAt,
      int ageDays) {}
}
