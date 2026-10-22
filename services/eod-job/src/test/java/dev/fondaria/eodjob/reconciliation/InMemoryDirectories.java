package dev.fondaria.eodjob.reconciliation;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.eodjob.fund.FundDirectory;
import dev.fondaria.eodjob.fund.RegisteredFund;
import dev.fondaria.eodjob.organization.OrganizationDirectory;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The read-only sources the reconciliation pass draws its candidate sets from, in memory.
 *
 * <p>Held together in one file because every one of them is two lines of state — a test that had to
 * assemble five separate doubles before it could assert anything would say less about the pass than
 * about the wiring.
 */
final class InMemoryDirectories {

  private InMemoryDirectories() {}

  static final class Funds implements FundDirectory {
    private final List<RegisteredFund> funds = new ArrayList<>();

    Funds with(RegisteredFund fund) {
      funds.add(fund);
      return this;
    }

    @Override
    public List<RegisteredFund> allFunds() {
      return List.copyOf(funds);
    }
  }

  static final class Organizations implements OrganizationDirectory {
    private final List<OrganizationId> organizations = new ArrayList<>();

    Organizations with(OrganizationId organizationId) {
      organizations.add(organizationId);
      return this;
    }

    @Override
    public List<OrganizationId> allOrganizations() {
      return List.copyOf(organizations);
    }
  }

  /** Pages an in-memory list the way the SQL keyset scan pages the table. */
  static final class Holdings implements HoldingAccountSource {
    private final List<HoldingKey> keys = new ArrayList<>();

    Holdings with(HoldingKey key) {
      keys.add(key);
      return this;
    }

    private static final Comparator<HoldingKey> ORDER =
        Comparator.comparing((HoldingKey k) -> k.organizationId().value())
            .thenComparing(HoldingKey::participantInternalRef)
            .thenComparing(k -> k.fundId().value());

    @Override
    public List<HoldingKey> nextPage(Optional<HoldingKey> after, int limit) {
      return keys.stream()
          .sorted(ORDER)
          .filter(key -> after.isEmpty() || ORDER.compare(key, after.get()) > 0)
          .limit(limit)
          .toList();
    }
  }

  static final class Projection implements ProjectionTotals {
    private final Map<Long, Long> units = new HashMap<>();
    private final Map<String, Long> cash = new HashMap<>();

    Projection withUnits(long fundLedgerId, long unitsScaled) {
      units.put(fundLedgerId, unitsScaled);
      return this;
    }

    Projection withCash(String accountId, long cents) {
      cash.put(accountId, cents);
      return this;
    }

    @Override
    public Map<Long, Long> unitsByFundLedger() {
      return Map.copyOf(units);
    }

    @Override
    public Map<String, Long> cashByAccount() {
      return Map.copyOf(cash);
    }
  }

  /** Remembers what a pass recorded, so a test can assert on the row the gate would read. */
  static final class Runs implements ReconciliationRepository {
    private final Map<UUID, ReconciliationRun> byId = new HashMap<>();
    private final Map<UUID, List<ReconciliationBreak>> breaks = new HashMap<>();

    @Override
    public void open(UUID runId, LocalDate businessDate, Instant startedAt) {
      byId.put(
          runId,
          new ReconciliationRun(
              runId,
              businessDate,
              RunStatus.RUNNING,
              startedAt,
              Optional.empty(),
              Optional.empty(),
              0,
              0L,
              0,
              0));
    }

    @Override
    public void complete(ReconciliationRun run, List<ReconciliationBreak> raised) {
      byId.put(run.runId(), run);
      breaks.put(run.runId(), List.copyOf(raised));
    }

    @Override
    public void fail(UUID runId, Instant failedAt, long durationMillis) {
      ReconciliationRun open = byId.get(runId);
      byId.put(
          runId,
          new ReconciliationRun(
              runId,
              open.businessDate(),
              RunStatus.FAILED,
              open.startedAt(),
              Optional.of(failedAt),
              Optional.of(durationMillis),
              0,
              0L,
              0,
              0));
    }

    /**
     * The same ordering {@code JdbcReconciliationRepository} applies: latest to <b>settle</b>, with
     * an unfinished run behind every settled one. A double that ordered by start time would let the
     * gate's tests pass against a repository that had the concurrency defect.
     */
    @Override
    public Optional<ReconciliationRun> latestFor(LocalDate businessDate) {
      Comparator<ReconciliationRun> settledLast =
          Comparator.comparing(
                  (ReconciliationRun run) -> run.completedAt().orElse(Instant.MIN),
                  Comparator.naturalOrder())
              .thenComparing(ReconciliationRun::startedAt);
      return byId.values().stream()
          .filter(run -> run.businessDate().equals(businessDate))
          .max(settledLast);
    }

    @Override
    public List<ReconciliationBreak> breaksOf(UUID runId) {
      return breaks.getOrDefault(runId, List.of());
    }
  }
}
