package dev.fondaria.balanceapi.summary;

import dev.fondaria.common.OrganizationId;
import java.util.List;

/**
 * Who belongs to an organization, and whether the organization exists at all (Task 6.5).
 *
 * <h2>Read whole, and deliberately not paged</h2>
 *
 * <p>The first implementation walked the roster in keyset pages over {@code
 * participant_internal_ref}, so that a page was also one ledger batch. The adversarial review
 * killed it: <b>keyset pagination over a {@code text} column is a silent-wrong-number hazard.</b>
 * The {@code >} comparison and the {@code ORDER BY} both run under the column's collation, while
 * the index that serves them is stored in the collation it was <i>built</i> under. Let those drift
 * — a glibc or ICU version change without a {@code REINDEX} — and a page boundary lands in the
 * wrong place, participants are never returned by any page, and the employer's total is quietly
 * short with nothing raising. That is the intermittent-and-about-money failure {@code AGENTS.md} §4
 * calls the worst available. Pinning {@code COLLATE "C"} would fix the ordering but cost a sort per
 * page.
 *
 * <p>So there is no cursor and no {@code ORDER BY} at all: one equality on the partition-leading
 * key, every row of it, and no ordering for anything to disagree about. What made paging attractive
 * — bounding the ledger request — is handled where it belongs, by {@code
 * OrganizationSummaryService.lookupInBatches}.
 *
 * <p><b>The cost is one list of internal references per request</b>, bounded by the largest
 * employer: roughly 10 MB at the 200,000-employee end of this platform's stated range, transient,
 * on an endpoint that is batch-shaped and low-concurrency by nature. That is the trade, and it is
 * worth it — the alternative bought bounded memory with a way to under-report an employer's money.
 *
 * <p>Read-only, and narrower again than {@code ParticipantDirectory}: this service enrols nobody
 * and closes nobody.
 */
public interface OrganizationRoster {

  /**
   * Whether {@code organizationId} names a registered organization.
   *
   * <p>Asked before anything is aggregated, because "no such organization" and "an organization
   * whose participants hold nothing" are different answers — a {@code 404} and a {@code 200} — and
   * an aggregate over an empty roster cannot tell them apart. Status is deliberately not consulted:
   * a {@code SUSPENDED} or {@code CLOSED} employer still has money in the fund, and a summary that
   * 404s once a contract ends would hide it.
   */
  boolean isRegistered(OrganizationId organizationId);

  /**
   * Every participant internal reference belonging to {@code organizationId}, in no particular
   * order.
   *
   * <p>Every status is included — {@code ACTIVE}, {@code DORMANT} and {@code CLOSED} alike. A
   * closed participant may still hold units, and a roster that filtered them out would produce a
   * participant count describing a different population than the totals beside it.
   *
   * <p>Order is unspecified because nothing depends on it: the response carries no per-participant
   * detail, and its fund list is sorted by fund id. Anything that <i>did</i> depend on it would be
   * re-opening the hazard this interface's javadoc exists to record.
   */
  List<String> participantsOf(OrganizationId organizationId);
}
