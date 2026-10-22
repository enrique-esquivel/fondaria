package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Money;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.config.SnapshotProperties;
import dev.fondaria.eodjob.reconciliation.HoldingAccountSource;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.TenantRefDeriver;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads the book once, consistently, and renders it as the positions file.
 *
 * <h2>Why this is a separate component: the whole read is one transaction</h2>
 *
 * <p>The pass walks the book a page at a time — a hundred pages at a million positions — and each
 * page needs three reads. Left to autocommit, each of those runs at its own point in time, and a
 * contribution landing halfway through is in some pages and not others. The file would then be
 * internally inconsistent in a way nothing downstream could detect, and its unit total would
 * disagree with the ledger for a reason that has nothing to do with the platform being wrong.
 *
 * <p>{@code REPEATABLE READ} pins one cut for every page, the NAV read and every cost-basis read.
 * PostgreSQL serves it from MVCC, so it takes no locks and blocks no writer; the cost is one
 * long-lived read transaction for the length of the pass. {@code readOnly} is not decoration —
 * nothing here may write, and the snapshot must not be able to become the thing it is describing.
 *
 * <p>It has to be its own bean rather than a method on {@code SnapshotService}, because Spring's
 * transactional proxy does not apply to a call a class makes to itself.
 *
 * <h2>Dense means the question is asked per date, not that the projection stores a row per date
 * </h2>
 *
 * <p>{@code projection.participant_units} is <i>sparse</i> — a row only when a holding changes. The
 * candidate set is every holding the platform has ever allotted into ({@code HoldingAccountSource},
 * reused from Task 7.2), and each is asked what it held <i>as at</i> the business date. A
 * participant who contributed once in March and has not moved since is read from that same March
 * row on every later business date, and appears on every day's file.
 */
@Component
public class PositionFileBuilder {

  private final AccountIdDeriver accountIds;
  private final TenantRefDeriver tenantRefs;
  private final HoldingAccountSource holdings;
  private final PositionUnitsSource positionUnits;
  private final CostBasisSource costBasis;
  private final SnapshotNavSource navs;
  private final SnapshotProperties properties;
  private final BusinessCalendar calendar;

  public PositionFileBuilder(
      AccountIdDeriver accountIds,
      TenantRefDeriver tenantRefs,
      HoldingAccountSource holdings,
      PositionUnitsSource positionUnits,
      CostBasisSource costBasis,
      SnapshotNavSource navs,
      SnapshotProperties properties,
      BusinessCalendar calendar) {
    this.accountIds = Objects.requireNonNull(accountIds, "accountIds must not be null");
    this.tenantRefs = Objects.requireNonNull(tenantRefs, "tenantRefs must not be null");
    this.holdings = Objects.requireNonNull(holdings, "holdings must not be null");
    this.positionUnits = Objects.requireNonNull(positionUnits, "positionUnits must not be null");
    this.costBasis = Objects.requireNonNull(costBasis, "costBasis must not be null");
    this.navs = Objects.requireNonNull(navs, "navs must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.calendar = Objects.requireNonNull(calendar, "calendar must not be null");
  }

  /**
   * Builds the file for a business date.
   *
   * @param ledgerIdByFund the registered funds, so a position can carry its fund's unit ledger
   *     without a lookup per row
   */
  @Transactional(isolation = Isolation.REPEATABLE_READ, readOnly = true)
  public BuiltPositions build(LocalDate businessDate, Map<FundId, Long> ledgerIdByFund) {
    Map<FundId, SnapshotNav> navByFund = navs.liveNavsAsOf(businessDate);
    Instant postedBefore = safelyAfter(businessDate);

    try (PositionFileWriter writer = PositionFileWriter.open(properties.maximumObjectBytes())) {
      Optional<HoldingKey> cursor = Optional.empty();
      int pageSize = properties.holdingPageSize();
      while (true) {
        List<HoldingKey> page = holdings.nextPage(cursor, pageSize);
        if (page.isEmpty()) {
          break;
        }
        writePage(writer, page, businessDate, postedBefore, navByFund, ledgerIdByFund);
        cursor = Optional.of(page.get(page.size() - 1));
        if (page.size() < pageSize) {
          break;
        }
      }
      return new BuiltPositions(writer.finish(), navByFund);
    }
  }

  private void writePage(
      PositionFileWriter writer,
      List<HoldingKey> page,
      LocalDate businessDate,
      Instant postedBefore,
      Map<FundId, SnapshotNav> navByFund,
      Map<FundId, Long> ledgerIdByFund) {
    // Derived once per holding and used twice — to build the units query and to read its answer
    // back. Deriving them again on the way out would be two more UUIDv5 (SHA-1) computations per
    // position, in the one loop the close's whole time budget is spent in.
    Map<HoldingKey, HoldingAccounts> accountsByHolding = new LinkedHashMap<>(page.size());
    Map<String, List<String>> accountsByTenant = new LinkedHashMap<>();
    for (HoldingKey holding : page) {
      HoldingAccounts accounts = accountsOf(holding);
      accountsByHolding.put(holding, accounts);
      accountsByTenant
          .computeIfAbsent(accounts.tenantId(), ignored -> new ArrayList<>())
          .addAll(List.of(accounts.vested(), accounts.unvested()));
    }

    Map<String, Long> unitsByAccount = new HashMap<>();
    for (Map.Entry<String, List<String>> entry : accountsByTenant.entrySet()) {
      unitsByAccount.putAll(
          positionUnits.unitsAsOf(entry.getKey(), entry.getValue(), businessDate));
    }
    Map<HoldingKey, List<AllotmentLot>> lotsByHolding = lotsFor(page, businessDate, postedBefore);

    for (HoldingKey holding : page) {
      HoldingAccounts accounts = accountsByHolding.get(holding);
      long unitsScaled =
          Math.addExact(
              unitsByAccount.getOrDefault(accounts.vested(), 0L),
              unitsByAccount.getOrDefault(accounts.unvested(), 0L));
      if (unitsScaled == 0L) {
        // Not an open position. A holding fully redeemed, or one whose allotment the projection has
        // not caught up with — and the second is not silently tolerated: the units are still in
        // issue on the ledger, so the fund's tie fails and the pass halts.
        continue;
      }
      SnapshotNav nav = navByFund.get(holding.fundId());
      if (nav == null) {
        throw new SnapshotFailedException(
            businessDate,
            "fund "
                + holding.fundId().value()
                + " holds positions but has no NAV struck on or before this date, so it cannot be"
                + " valued");
      }
      Long unitLedgerId = ledgerIdByFund.get(holding.fundId());
      if (unitLedgerId == null) {
        throw new SnapshotFailedException(
            businessDate,
            "fund " + holding.fundId().value() + " holds positions but is not a registered fund");
      }
      writer.write(
          SnapshotPosition.of(
              holding.organizationId(),
              holding.participantInternalRef(),
              holding.fundId(),
              unitLedgerId,
              Units.of(unitsScaled),
              nav.nav(),
              nav.navVersionId(),
              costBasisOf(lotsByHolding.get(holding), nav)));
    }
  }

  /**
   * Both of a holding's ledger accounts, and the tenant partition they live in.
   *
   * <p>Vested and unvested: a {@code VESTING} transfer moves units between them without changing
   * what the participant owns, so either alone would understate the position by exactly the amount
   * that had vested.
   */
  private HoldingAccounts accountsOf(HoldingKey holding) {
    String scope = holding.scope();
    return new HoldingAccounts(
        tenantRefs.derive(holding.organizationId()).asUnsignedDecimal(),
        accountIds
            .derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_VESTED, scope)
            .asUnsignedDecimal(),
        accountIds
            .derive(LedgerCodes.AccountCode.PARTICIPANT_HOLDINGS_UNVESTED, scope)
            .asUnsignedDecimal());
  }

  /**
   * The page's lots, filtered to those posted on or before the business date.
   *
   * <p>The instant bound narrows the scan; {@code BusinessCalendar} decides membership. Which
   * business date an instant falls in is the calendar's question — cut-off time, zone and DST are
   * all in it — and answering it in SQL would be a second implementation of {@code businessDateOf}
   * that could disagree with the one {@code projection.participant_units.effective_date} was
   * written with. See {@link CostBasisSource} for why the bound is on posting and not on valuation
   * date.
   */
  private Map<HoldingKey, List<AllotmentLot>> lotsFor(
      List<HoldingKey> page, LocalDate businessDate, Instant postedBefore) {
    Map<HoldingKey, List<AllotmentLot>> byHolding = new HashMap<>();
    for (AllotmentLot lot :
        costBasis.lotsFor(page.get(0), page.get(page.size() - 1), postedBefore)) {
      if (calendar.businessDateOf(lot.allottedAt()).isAfter(businessDate)) {
        continue;
      }
      byHolding.computeIfAbsent(lot.holding(), ignored -> new ArrayList<>()).add(lot);
    }
    return byHolding;
  }

  /**
   * A position's cost basis: each lot valued at the price it was issued at, summed.
   *
   * <p>Per lot and then summed, never the total units at an average price — those differ by the
   * rounding of every lot, and only the first is reproducible from what {@code workflow.allotment}
   * stores. A position with no lots on or before the date has a zero basis, which makes its whole
   * value unrealised gain; that is the honest answer when nothing recorded what it cost.
   */
  private static Money costBasisOf(List<AllotmentLot> lots, SnapshotNav nav) {
    Money basis = Money.of(0L, nav.nav().currency());
    if (lots == null) {
      return basis;
    }
    for (AllotmentLot lot : lots) {
      basis = basis.plus(Money.value(lot.units(), Nav.of(lot.navScaled(), nav.nav().currency())));
    }
    return basis;
  }

  /**
   * An instant certainly after the end of {@code businessDate}, whatever the calendar's zone and
   * cut-off. Two days of UTC covers every zone offset and every cut-off time; the exact cut is the
   * calendar's, applied per lot.
   */
  private static Instant safelyAfter(LocalDate businessDate) {
    return businessDate.plusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
  }

  /** A holding's two ledger accounts and its tenant partition, derived once. */
  private record HoldingAccounts(String tenantId, String vested, String unvested) {}
}
