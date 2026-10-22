package dev.fondaria.eodjob.custodian;

import dev.fondaria.common.FundId;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.eodjob.reconciliation.HoldingKey;
import dev.fondaria.eodjob.reconciliation.RunStatus;
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
 * The read/write sources a custodian-reconciliation test drives, in memory — the {@code custodian}
 * package's counterpart of {@code InMemoryDirectories} (Task 7.2's tests).
 */
final class InMemoryCustodianDirectories {

  private InMemoryCustodianDirectories() {}

  static final class ProviderAccounts implements ProviderAccountDirectory {
    private final List<ProviderAccount> accounts = new ArrayList<>();

    ProviderAccounts with(ProviderAccount account) {
      accounts.add(account);
      return this;
    }

    @Override
    public List<ProviderAccount> activeAsOf(LocalDate businessDate) {
      Map<String, ProviderAccount> latestByKey = new HashMap<>();
      for (ProviderAccount account : accounts) {
        if (account.effectiveFrom().isAfter(businessDate)) {
          continue;
        }
        String key =
            account.fundId()
                + ":"
                + account.scopeType()
                + ":"
                + account.organizationId().map(Object::toString).orElse("");
        ProviderAccount current = latestByKey.get(key);
        if (current == null
            || account.effectiveFrom().isAfter(current.effectiveFrom())
            || (account.effectiveFrom().equals(current.effectiveFrom())
                && account.version() > current.version())) {
          latestByKey.put(key, account);
        }
      }
      return List.copyOf(latestByKey.values());
    }
  }

  static final class Statements implements CustodianStatementRepository {
    private final Map<String, CustodianStatement> byKey = new HashMap<>();

    private static String key(UUID providerAccountId, LocalDate businessDate) {
      return providerAccountId + ":" + businessDate;
    }

    Statements with(CustodianStatement statement) {
      byKey.put(key(statement.providerAccountId(), statement.businessDate()), statement);
      return this;
    }

    @Override
    public Optional<CustodianStatement> findFor(UUID providerAccountId, LocalDate businessDate) {
      return Optional.ofNullable(byKey.get(key(providerAccountId, businessDate)));
    }

    @Override
    public CustodianStatement insert(
        UUID statementId, CustodianStatementSubmission submission, Instant receivedAt) {
      CustodianStatement statement =
          new CustodianStatement(
              statementId,
              submission.providerAccountId(),
              submission.businessDate(),
              submission.reportedUnitsScaled(),
              submission.reportedCashCents(),
              submission.pendingTradeUnitsScaled(),
              submission.pendingTradeCashCents(),
              submission.source(),
              submission.checksumSha256(),
              receivedAt);
      byKey.put(key(statement.providerAccountId(), statement.businessDate()), statement);
      return statement;
    }
  }

  static final class Holdings implements CustodianHoldingSource {
    private final List<HoldingKey> keys = new ArrayList<>();

    Holdings with(HoldingKey key) {
      keys.add(key);
      return this;
    }

    @Override
    public List<HoldingKey> holdingsOf(OrganizationId organizationId, FundId fundId) {
      return keys.stream()
          .filter(k -> k.organizationId().equals(organizationId) && k.fundId().equals(fundId))
          .toList();
    }
  }

  /** Reproduces the DB's "at most one non-RESOLVED row per (account, category)" partial index. */
  static final class Breaks implements CustodianBreakRepository {
    private final Map<UUID, CustodianBreak> byId = new HashMap<>();

    @Override
    public Optional<CustodianBreak> findOpen(UUID providerAccountId, BreakCategory category) {
      return byId.values().stream()
          .filter(
              b ->
                  b.providerAccountId().equals(providerAccountId)
                      && b.category() == category
                      && b.status() != BreakStatus.RESOLVED)
          .findFirst();
    }

    @Override
    public CustodianBreak open(
        UUID providerAccountId,
        BreakCategory category,
        long driftScaled,
        LocalDate businessDate,
        Instant now) {
      if (findOpen(providerAccountId, category).isPresent()) {
        throw new IllegalStateException(
            "a non-resolved break already exists for this account and category");
      }
      CustodianBreak created =
          new CustodianBreak(
              UUID.randomUUID(),
              providerAccountId,
              category,
              BreakStatus.OPEN,
              driftScaled,
              businessDate,
              businessDate,
              Optional.empty(),
              now,
              now,
              Optional.empty(),
              Optional.empty(),
              Optional.empty());
      byId.put(created.breakId(), created);
      return created;
    }

    @Override
    public void confirmStillOpen(
        UUID breakId, long driftScaled, LocalDate businessDate, Instant now) {
      CustodianBreak existing = byId.get(breakId);
      if (existing == null || existing.status() == BreakStatus.RESOLVED) {
        return;
      }
      LocalDate lastConfirmed =
          businessDate.isAfter(existing.lastConfirmedBusinessDate())
              ? businessDate
              : existing.lastConfirmedBusinessDate();
      byId.put(
          breakId,
          new CustodianBreak(
              existing.breakId(),
              existing.providerAccountId(),
              existing.category(),
              existing.status(),
              driftScaled,
              existing.firstDetectedBusinessDate(),
              lastConfirmed,
              existing.assignedTo(),
              existing.openedAt(),
              now,
              existing.escalatedAt(),
              existing.resolvedAt(),
              existing.resolutionNote()));
    }

    @Override
    public void resolve(UUID breakId, Instant now, String resolutionNote) {
      CustodianBreak existing = byId.get(breakId);
      if (existing == null || existing.status() == BreakStatus.RESOLVED) {
        return;
      }
      byId.put(
          breakId,
          new CustodianBreak(
              existing.breakId(),
              existing.providerAccountId(),
              existing.category(),
              BreakStatus.RESOLVED,
              existing.driftScaled(),
              existing.firstDetectedBusinessDate(),
              existing.lastConfirmedBusinessDate(),
              existing.assignedTo(),
              existing.openedAt(),
              now,
              existing.escalatedAt(),
              Optional.of(now),
              Optional.of(resolutionNote)));
    }

    @Override
    public void assign(UUID breakId, String assignedTo, Instant now) {
      CustodianBreak existing = byId.get(breakId);
      if (existing == null || existing.status() == BreakStatus.RESOLVED) {
        return;
      }
      byId.put(
          breakId,
          new CustodianBreak(
              existing.breakId(),
              existing.providerAccountId(),
              existing.category(),
              BreakStatus.ASSIGNED,
              existing.driftScaled(),
              existing.firstDetectedBusinessDate(),
              existing.lastConfirmedBusinessDate(),
              Optional.of(assignedTo),
              existing.openedAt(),
              now,
              existing.escalatedAt(),
              existing.resolvedAt(),
              existing.resolutionNote()));
    }

    @Override
    public void investigate(UUID breakId, Instant now) {
      CustodianBreak existing = byId.get(breakId);
      if (existing == null || existing.status() == BreakStatus.RESOLVED) {
        return;
      }
      byId.put(
          breakId,
          new CustodianBreak(
              existing.breakId(),
              existing.providerAccountId(),
              existing.category(),
              BreakStatus.INVESTIGATING,
              existing.driftScaled(),
              existing.firstDetectedBusinessDate(),
              existing.lastConfirmedBusinessDate(),
              existing.assignedTo(),
              existing.openedAt(),
              now,
              existing.escalatedAt(),
              existing.resolvedAt(),
              existing.resolutionNote()));
    }

    @Override
    public int escalateAged(
        LocalDate businessDate, int realBreakThresholdDays, int timingThresholdDays, Instant now) {
      int count = 0;
      for (CustodianBreak existing : List.copyOf(byId.values())) {
        if (existing.status() == BreakStatus.RESOLVED
            || existing.status() == BreakStatus.ESCALATED) {
          continue;
        }
        int threshold =
            existing.category().isTimingDifference() ? timingThresholdDays : realBreakThresholdDays;
        if (existing.ageInDays(businessDate) < threshold) {
          continue;
        }
        byId.put(
            existing.breakId(),
            new CustodianBreak(
                existing.breakId(),
                existing.providerAccountId(),
                existing.category(),
                BreakStatus.ESCALATED,
                existing.driftScaled(),
                existing.firstDetectedBusinessDate(),
                existing.lastConfirmedBusinessDate(),
                existing.assignedTo(),
                existing.openedAt(),
                now,
                Optional.of(now),
                existing.resolvedAt(),
                existing.resolutionNote()));
        count++;
      }
      return count;
    }

    @Override
    public boolean hasOpenRealBreak() {
      return byId.values().stream()
          .anyMatch(b -> b.status() != BreakStatus.RESOLVED && !b.category().isTimingDifference());
    }
  }

  /** Same "latest to settle" ordering {@code JdbcCustodianReconciliationRepository} applies. */
  static final class Runs implements CustodianReconciliationRepository {
    private final Map<UUID, CustodianReconciliationRun> byId = new HashMap<>();

    @Override
    public void open(UUID runId, LocalDate businessDate, Instant startedAt) {
      byId.put(
          runId,
          new CustodianReconciliationRun(
              runId,
              businessDate,
              RunStatus.RUNNING,
              startedAt,
              Optional.empty(),
              Optional.empty(),
              0,
              0,
              0,
              0,
              0));
    }

    @Override
    public void complete(CustodianReconciliationRun run) {
      byId.put(run.runId(), run);
    }

    @Override
    public void fail(UUID runId, Instant failedAt, long durationMillis) {
      CustodianReconciliationRun open = byId.get(runId);
      byId.put(
          runId,
          new CustodianReconciliationRun(
              runId,
              open.businessDate(),
              RunStatus.FAILED,
              open.startedAt(),
              Optional.of(failedAt),
              Optional.of(durationMillis),
              0,
              0,
              0,
              0,
              0));
    }

    @Override
    public Optional<CustodianReconciliationRun> latestFor(LocalDate businessDate) {
      Comparator<CustodianReconciliationRun> settledLast =
          Comparator.comparing(
                  (CustodianReconciliationRun run) -> run.completedAt().orElse(Instant.MIN),
                  Comparator.naturalOrder())
              .thenComparing(CustodianReconciliationRun::startedAt);
      return byId.values().stream()
          .filter(run -> run.businessDate().equals(businessDate))
          .max(settledLast);
    }
  }
}
