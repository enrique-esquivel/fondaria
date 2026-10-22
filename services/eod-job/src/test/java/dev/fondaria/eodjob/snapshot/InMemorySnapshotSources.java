package dev.fondaria.eodjob.snapshot;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.eodjob.custodian.CustodianReconciliationRepository;
import dev.fondaria.eodjob.custodian.CustodianReconciliationRun;
import dev.fondaria.eodjob.fund.FundDirectory;
import dev.fondaria.eodjob.fund.RegisteredFund;
import dev.fondaria.eodjob.reconciliation.HoldingAccountSource;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import dev.fondaria.eodjob.reconciliation.ReconciliationBreak;
import dev.fondaria.eodjob.reconciliation.ReconciliationRepository;
import dev.fondaria.eodjob.reconciliation.ReconciliationRun;
import dev.fondaria.eodjob.reconciliation.RunStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The read-only sources and sinks the snapshot pass draws on, in memory.
 *
 * <p>Held together in one file for the reason {@code InMemoryDirectories} is: each is a few lines
 * of state, and a test that had to assemble eight separate doubles before it could assert anything
 * would say more about the wiring than about the pass.
 *
 * <p><b>{@link Units} models sparseness, and that is the point.</b> A holding gets a row only on
 * the day it changed, and {@code unitsAsOf} returns the latest row at or before the date asked for
 * — exactly what {@code JdbcPositionUnitsSource}'s {@code DISTINCT ON} does against the real table.
 * A double that stored one number per account would make the density criterion untestable here, and
 * "a dormant participant has a row every day" would pass without ever having been exercised.
 */
final class InMemorySnapshotSources {

  private InMemorySnapshotSources() {}

  static final class Funds implements FundDirectory {
    private final List<RegisteredFund> funds = new ArrayList<>();

    Funds with(FundId fundId, long unitLedgerId) {
      funds.add(new RegisteredFund(fundId, unitLedgerId));
      return this;
    }

    @Override
    public List<RegisteredFund> allFunds() {
      return List.copyOf(funds);
    }
  }

  /** Pages an in-memory list the way the SQL keyset scan pages the table. */
  static final class Holdings implements HoldingAccountSource {
    static final Comparator<HoldingKey> ORDER =
        Comparator.comparing((HoldingKey k) -> k.organizationId().value())
            .thenComparing(HoldingKey::participantInternalRef)
            .thenComparing(k -> k.fundId().value());

    private final List<HoldingKey> keys = new ArrayList<>();
    private final List<Integer> pageSizes = new ArrayList<>();

    Holdings with(HoldingKey key) {
      keys.add(key);
      return this;
    }

    /** How many keys each page actually returned — proves the scan really paged. */
    List<Integer> pageSizes() {
      return List.copyOf(pageSizes);
    }

    @Override
    public List<HoldingKey> nextPage(Optional<HoldingKey> after, int limit) {
      List<HoldingKey> page =
          keys.stream()
              .sorted(ORDER)
              .filter(key -> after.isEmpty() || ORDER.compare(key, after.get()) > 0)
              .limit(limit)
              .toList();
      pageSizes.add(page.size());
      return page;
    }
  }

  /** Sparse holdings: one row per change, read as-of. */
  static final class UnitRows implements PositionUnitsSource {
    private record Row(String tenantId, String accountId, LocalDate effectiveDate, long units) {}

    private final List<Row> rows = new ArrayList<>();

    UnitRows with(String tenantId, String accountId, LocalDate effectiveDate, long unitsScaled) {
      rows.add(new Row(tenantId, accountId, effectiveDate, unitsScaled));
      return this;
    }

    @Override
    public Map<String, Long> unitsAsOf(
        String tenantId, List<String> accountIds, LocalDate asOfDate) {
      Map<String, Long> byAccount = new HashMap<>();
      for (String accountId : accountIds) {
        rows.stream()
            .filter(
                row ->
                    row.tenantId().equals(tenantId)
                        && row.accountId().equals(accountId)
                        && !row.effectiveDate().isAfter(asOfDate))
            .max(Comparator.comparing(Row::effectiveDate))
            .ifPresent(row -> byAccount.put(accountId, row.units()));
      }
      return byAccount;
    }
  }

  /**
   * Lots, keyed on <b>when they were posted</b> — which is what decides membership of a snapshot,
   * and is deliberately a different input from the NAV's valuation date.
   *
   * <p>An earlier version of this double took only a valuation date and filtered on it, which
   * faithfully reproduced the production bug it was supposed to be able to catch: every fixture set
   * the two to the same day, so no test could express a lot dealt on Monday and posted on Tuesday.
   */
  static final class Lots implements CostBasisSource {
    private final List<AllotmentLot> lots = new ArrayList<>();

    Lots with(HoldingKey holding, long unitsScaled, long navScaled, Instant allottedAt) {
      lots.add(new AllotmentLot(holding, Units.of(unitsScaled), navScaled, allottedAt));
      return this;
    }

    @Override
    public List<AllotmentLot> lotsFor(HoldingKey from, HoldingKey to, Instant postedBefore) {
      return lots.stream()
          .filter(lot -> lot.allottedAt().isBefore(postedBefore))
          .filter(
              lot ->
                  Holdings.ORDER.compare(lot.holding(), from) >= 0
                      && Holdings.ORDER.compare(lot.holding(), to) <= 0)
          .toList();
    }
  }

  /** One struck price per fund per date; the pass reads the latest at or before its own date. */
  static final class Navs implements SnapshotNavSource {
    private record Struck(FundId fundId, long navVersionId, long navScaled, LocalDate date) {}

    private final List<Struck> struck = new ArrayList<>();

    Navs with(FundId fundId, long navVersionId, long navScaled, LocalDate valuationDate) {
      struck.add(new Struck(fundId, navVersionId, navScaled, valuationDate));
      return this;
    }

    @Override
    public Map<FundId, SnapshotNav> liveNavsAsOf(LocalDate businessDate) {
      Map<FundId, SnapshotNav> byFund = new HashMap<>();
      for (Struck row : struck) {
        if (row.date().isAfter(businessDate)) {
          continue;
        }
        SnapshotNav candidate =
            new SnapshotNav(
                row.fundId(),
                row.navVersionId(),
                Nav.of(row.navScaled(), CurrencyCode.MXN),
                row.date());
        SnapshotNav existing = byFund.get(row.fundId());
        if (existing == null || existing.valuationDate().isBefore(row.date())) {
          byFund.put(row.fundId(), candidate);
        }
      }
      return byFund;
    }
  }

  /** Remembers every object written, so a test can assert what was stored and what was not. */
  static final class Objects implements SnapshotObjectStore {
    private final Map<String, byte[]> stored = new LinkedHashMap<>();
    private RuntimeException failure;

    Objects failingWith(RuntimeException failure) {
      this.failure = failure;
      return this;
    }

    Map<String, byte[]> stored() {
      return Map.copyOf(stored);
    }

    List<String> keys() {
      return List.copyOf(stored.keySet());
    }

    @Override
    public void put(String objectKey, byte[] bytes, String contentType) {
      if (failure != null) {
        throw failure;
      }
      stored.put(objectKey, bytes);
    }
  }

  /** Records snapshot runs the way the real table does, including its completion ordering. */
  static final class Runs implements SnapshotRepository {
    private final Map<UUID, SnapshotRun> byId = new LinkedHashMap<>();
    private final Map<UUID, List<SnapshotFundTotal>> totals = new HashMap<>();
    private final Map<UUID, String> intents = new LinkedHashMap<>();

    @Override
    public void open(UUID runId, LocalDate businessDate, Instant startedAt) {
      byId.put(
          runId,
          new SnapshotRun(
              runId,
              businessDate,
              SnapshotStatus.RUNNING,
              startedAt,
              Optional.empty(),
              Optional.empty(),
              0L,
              0,
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty()));
    }

    @Override
    public void recordIntent(UUID runId, String positionsObjectKey, String positionsSha256) {
      SnapshotRun open = byId.get(runId);
      if (open == null || open.status() != SnapshotStatus.RUNNING) {
        throw new IllegalStateException("snapshot run " + runId + " was no longer RUNNING");
      }
      intents.put(runId, positionsSha256);
    }

    /**
     * Mirrors {@code snapshot_run_one_of_record_per_date} (V28). Without it this double would let
     * two passes both become the snapshot of record, which is exactly the state the index exists to
     * make impossible — and a test suite that allowed it here would pass against a schema that had
     * lost the index.
     */
    @Override
    public void complete(SnapshotRun run, List<SnapshotFundTotal> fundTotals) {
      boolean alreadyOfRecord =
          byId.values().stream()
              .anyMatch(
                  other ->
                      other.isComplete()
                          && other.businessDate().equals(run.businessDate())
                          && !other.runId().equals(run.runId()));
      if (alreadyOfRecord) {
        throw new IllegalStateException(
            "a COMPLETED snapshot already exists for " + run.businessDate());
      }
      byId.put(run.runId(), run);
      totals.put(run.runId(), List.copyOf(fundTotals));
    }

    /** What each run said it was about to store, whether or not it got there. */
    Map<UUID, String> intents() {
      return Map.copyOf(intents);
    }

    @Override
    public void fail(UUID runId, Instant failedAt, long durationMillis, String reason) {
      SnapshotRun open = byId.get(runId);
      byId.put(
          runId,
          new SnapshotRun(
              runId,
              open.businessDate(),
              SnapshotStatus.FAILED,
              open.startedAt(),
              Optional.of(failedAt),
              Optional.of(durationMillis),
              0L,
              0,
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              Optional.of(reason)));
    }

    @Override
    public Optional<SnapshotRun> latestCompletedFor(LocalDate businessDate) {
      return byId.values().stream()
          .filter(run -> run.businessDate().equals(businessDate) && run.isComplete())
          .max(Comparator.comparing(run -> run.completedAt().orElseThrow()));
    }

    @Override
    public Optional<SnapshotRun> latestCompletedBefore(LocalDate businessDate) {
      return byId.values().stream()
          .filter(run -> run.businessDate().isBefore(businessDate) && run.isComplete())
          .max(
              Comparator.comparing(SnapshotRun::businessDate)
                  .thenComparing(run -> run.completedAt().orElseThrow()));
    }

    @Override
    public List<SnapshotFundTotal> fundTotalsOf(UUID runId) {
      return totals.getOrDefault(runId, List.of());
    }

    @Override
    public List<SnapshotRun> completedRunsAscending() {
      return byId.values().stream()
          .filter(SnapshotRun::isComplete)
          .sorted(Comparator.comparing(SnapshotRun::businessDate))
          .toList();
    }

    SnapshotRun run(UUID runId) {
      return byId.get(runId);
    }

    List<SnapshotRun> all() {
      return List.copyOf(byId.values());
    }
  }

  /**
   * The two upstream gates, as repositories holding one settled run each.
   *
   * <p>The real {@code ReconciliationGate} and {@code CustodianReconciliationGate} are used
   * unchanged — only what they read is faked. A double for the gate itself would let the snapshot's
   * tests pass against a gate that had stopped failing closed.
   */
  static final class Gates {

    private Gates() {}

    static ReconciliationRepository internal(LocalDate businessDate, RunStatus status) {
      return new ReconciliationRepository() {
        @Override
        public void open(UUID runId, LocalDate date, Instant startedAt) {
          throw new AssertionError("the snapshot must not open a reconciliation run");
        }

        @Override
        public void complete(ReconciliationRun run, List<ReconciliationBreak> breaks) {
          throw new AssertionError("the snapshot must not settle a reconciliation run");
        }

        @Override
        public void fail(UUID runId, Instant failedAt, long durationMillis) {
          throw new AssertionError("the snapshot must not fail a reconciliation run");
        }

        @Override
        public Optional<ReconciliationRun> latestFor(LocalDate date) {
          if (!date.equals(businessDate)) {
            return Optional.empty();
          }
          Instant settled = Instant.parse("2026-01-01T00:00:00Z");
          return Optional.of(
              new ReconciliationRun(
                  UUID.nameUUIDFromBytes(("internal:" + date).getBytes()),
                  date,
                  status,
                  settled,
                  status == RunStatus.RUNNING ? Optional.empty() : Optional.of(settled),
                  status == RunStatus.RUNNING ? Optional.empty() : Optional.of(1L),
                  0,
                  0L,
                  0,
                  status == RunStatus.FAILED ? 1 : 0));
        }

        @Override
        public List<ReconciliationBreak> breaksOf(UUID runId) {
          return List.of();
        }
      };
    }

    static CustodianReconciliationRepository custodian(LocalDate businessDate, RunStatus status) {
      return new CustodianReconciliationRepository() {
        @Override
        public void open(UUID runId, LocalDate date, Instant startedAt) {
          throw new AssertionError("the snapshot must not open a custodian run");
        }

        @Override
        public void complete(CustodianReconciliationRun run) {
          throw new AssertionError("the snapshot must not settle a custodian run");
        }

        @Override
        public void fail(UUID runId, Instant failedAt, long durationMillis) {
          throw new AssertionError("the snapshot must not fail a custodian run");
        }

        @Override
        public Optional<CustodianReconciliationRun> latestFor(LocalDate date) {
          if (!date.equals(businessDate)) {
            return Optional.empty();
          }
          Instant settled = Instant.parse("2026-01-01T00:00:00Z");
          return Optional.of(
              new CustodianReconciliationRun(
                  UUID.nameUUIDFromBytes(("custodian:" + date).getBytes()),
                  date,
                  status,
                  settled,
                  status == RunStatus.RUNNING ? Optional.empty() : Optional.of(settled),
                  status == RunStatus.RUNNING ? Optional.empty() : Optional.of(1L),
                  0,
                  0,
                  0,
                  status == RunStatus.FAILED ? 1 : 0,
                  0));
        }
      };
    }
  }
}
