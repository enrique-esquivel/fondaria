package dev.fondaria.contribution.batch;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.rules.MatchingRatioRule;
import dev.fondaria.rules.PlanVersion;
import dev.fondaria.rules.PlanVersionRepository;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * The whole-batch checks of {@code contribution-file-v2.md} §4, checks 3 to 6, in that order, and —
 * step 5, BACKLOG.md — the plan-rule checks that follow once the file is known to bear out its own
 * declarations.
 *
 * <p>Check 1 (the checksum) belongs to the caller, because it is answered from the stored object's
 * bytes rather than from a parsed file, and check 2 (parsing) is {@link ContributionFileParser}'s.
 * From here on the file is known to be well-formed, and every remaining structural question is the
 * same one: <b>does the file bear out what the employer declared when it submitted the batch?</b>
 *
 * <p><b>The order is the contract, not an optimisation.</b> A truncated file is caught by the row
 * count before its columns are summed, so the employer is told "rows are missing" rather than "your
 * employer total is short" — the same underlying fault, but only one of those two answers leads
 * anywhere. Every check stops the batch: no row is staged, no participant is enrolled, and no
 * partial allotment is ever possible (AGENTS.md §1).
 *
 * <p>All arithmetic is {@code long} cents with {@link Math#addExact}. A file whose amounts overflow
 * a {@code long} is malformed rather than silently wrapped into a plausible total.
 *
 * <p><b>No longer static.</b> Resolving the plan a batch declares needs a {@link
 * PlanVersionRepository} collaborator, so this class is a Spring component like everything else
 * {@link BatchValidationRunner} depends on.
 */
@Component
public class ContributionBatchValidator {

  private final PlanVersionRepository planVersions;

  public ContributionBatchValidator(PlanVersionRepository planVersions) {
    this.planVersions = planVersions;
  }

  /**
   * @param organizationId the tenant the batch was submitted under — needed to resolve the plan
   *     version the parity check runs against
   * @param file a file that has already parsed
   * @param declared what the submission said this batch is
   * @throws BatchRejectedException the first check that fails, carrying its own code
   */
  public void validate(
      OrganizationId organizationId, ContributionFile file, BatchDeclaration declared) {
    requireRowCountMatches(file, declared);
    requireNoDuplicateParticipants(file);
    requireSourceTotalsMatch(file, declared);
    requireDeclaredTotalMatches(declared);
    PlanVersion plan = requirePlanVersionResolves(organizationId, declared);
    requireEmployerMatchRatioHolds(plan, file);
  }

  /** Check 3 — the cheap truncation guard, before anything is summed. */
  private static void requireRowCountMatches(ContributionFile file, BatchDeclaration declared) {
    int actual = file.rows().size();
    if (actual != declared.declaredRowCount()) {
      throw new BatchRejectedException(
          BatchRejection.of(
              BatchRejectionCode.ROW_COUNT_MISMATCH,
              "The submission declares "
                  + declared.declaredRowCount()
                  + " participant rows and the file contains "
                  + actual
                  + ".",
              Map.of("declaredRowCount", declared.declaredRowCount(), "actualRowCount", actual)));
    }
  }

  /**
   * Check 4 — no two rows share a participant.
   *
   * <p>One rule, because there is one row shape. A participant appears exactly once, whatever they
   * contribute from, so a second row for them is a duplicated declaration whose two copies may
   * disagree about the employment dates as well as the money.
   */
  private static void requireNoDuplicateParticipants(ContributionFile file) {
    Set<String> seen = new HashSet<>();
    for (ContributionFile.ParticipantRow row : file.rows()) {
      if (!seen.add(row.participantInternalRef())) {
        // The participant reference is not rendered: it identifies a person, and a
        // rejection body is logged by everything it passes through (AGENTS.md §7). The
        // employer finds the row in its own file.
        throw new BatchRejectedException(
            BatchRejection.of(
                BatchRejectionCode.DUPLICATE_ROW,
                "The file contains more than one row for the same participant. Each participant"
                    + " has exactly one row, with a column per contribution source.",
                Map.of("rowCount", file.rows().size())));
      }
    }
  }

  /**
   * Check 5 — each declared source total equals the sum of that source's column.
   *
   * <p>Reported per source rather than in aggregate on purpose: Task 3.1 requires employee and
   * employer money to stay separately represented end to end, and that includes how a discrepancy
   * is reported. "Your employer total is 1,000 short" is actionable; "your file is 1,000 short" is
   * not.
   *
   * <p>Every row carries every column and the declaration carries every source, so unlike v1 there
   * is no missing-or-orphan case to check: both sides always cover the same source set.
   */
  private static void requireSourceTotalsMatch(ContributionFile file, BatchDeclaration declared) {
    Map<ContributionSource, Long> actual = new EnumMap<>(ContributionSource.class);
    for (ContributionFile.ParticipantRow row : file.rows()) {
      for (Map.Entry<ContributionSource, Long> amount : row.amounts().entrySet()) {
        actual.merge(amount.getKey(), amount.getValue(), Math::addExact);
      }
    }

    for (ContributionSource source : ContributionSource.values()) {
      long summed = actual.getOrDefault(source, 0L);
      long declaredCents = declared.declaredCentsFor(source);
      if (summed != declaredCents) {
        throw new BatchRejectedException(
            BatchRejection.of(
                BatchRejectionCode.SOURCE_SUBTOTAL_MISMATCH,
                "The declared " + source + " total does not equal the sum of that column.",
                Map.of(
                    "source",
                    source.name(),
                    "declaredCents",
                    declaredCents,
                    "actualCents",
                    summed)));
      }
    }
  }

  /**
   * Check 6 — the grand total against the source totals that make it up. This is the number the
   * reconciliation gate then compares against money actually received, so it is the last thing
   * checked about the batch's own arithmetic and the first thing checked outside it.
   */
  private static void requireDeclaredTotalMatches(BatchDeclaration declared) {
    long summed = 0L;
    for (long sourceTotal : declared.declaredSourceCents().values()) {
      summed = Math.addExact(summed, sourceTotal);
    }
    if (summed != declared.declaredTotalCents()) {
      throw new BatchRejectedException(
          BatchRejection.of(
              BatchRejectionCode.DECLARED_TOTAL_MISMATCH,
              "The declared grand total does not equal the sum of the declared source totals.",
              Map.of(
                  "declaredTotalCents",
                  declared.declaredTotalCents(),
                  "sourceTotalSumCents",
                  summed)));
    }
  }

  /**
   * Check 7 — the plan the batch declares must resolve to a version in force on the batch's value
   * date. Nothing enforces {@code planId} at submission (BACKLOG.md step 5, question 3); this is
   * where an unknown or not-yet-effective plan actually surfaces.
   */
  private PlanVersion requirePlanVersionResolves(
      OrganizationId organizationId, BatchDeclaration declared) {
    Optional<PlanVersion> plan =
        planVersions.resolveAsOf(organizationId, declared.planId(), declared.valueDate());
    return plan.orElseThrow(
        () ->
            new BatchRejectedException(
                BatchRejection.of(
                    BatchRejectionCode.PLAN_VERSION_NOT_FOUND,
                    "No plan version is in force for the declared plan on the batch's value"
                        + " date.")));
  }

  /**
   * Check 8 — the one rule step 5 enforces (docs/DOMAIN.md point 5: parity is validated per
   * participant, never averaged across a batch). Stops at the first breaching row: this is a
   * whole-batch rejection (BACKLOG.md step 5, question 1 — per-row rejection is Task 3.5's
   * contract), so which row breached first changes nothing about the outcome, and counting every
   * breach would cost a full pass over the file for no benefit. The count in the details is of
   * breaching rows, never a reference to one — nothing here identifies a participant.
   */
  private static void requireEmployerMatchRatioHolds(PlanVersion plan, ContributionFile file) {
    int breaches = 0;
    for (ContributionFile.ParticipantRow row : file.rows()) {
      long employeeCents = row.amountOf(ContributionSource.EMPLOYEE);
      long employerCents = row.amountOf(ContributionSource.EMPLOYER);
      if (MatchingRatioRule.breaches(plan, employeeCents, employerCents)) {
        breaches++;
      }
    }
    if (breaches > 0) {
      throw new BatchRejectedException(
          BatchRejection.of(
              BatchRejectionCode.EMPLOYER_MATCH_RATIO_BREACH,
              "At least one row's employer amount does not match the plan's employer matching"
                  + " ratio.",
              Map.of("breachingRowCount", breaches)));
    }
  }
}
