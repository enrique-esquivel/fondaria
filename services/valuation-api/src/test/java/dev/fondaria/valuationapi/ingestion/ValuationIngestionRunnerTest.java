package dev.fondaria.valuationapi.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import dev.fondaria.common.BusinessCalendar;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Units;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerAccount;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.rules.FundNav;
import dev.fondaria.valuation.InMemoryNavCache;
import dev.fondaria.valuation.NavSnapshotStore;
import dev.fondaria.valuationapi.config.ValuationIngestionProperties;
import dev.fondaria.valuationapi.objectstore.ObjectStore;
import dev.fondaria.valuationapi.objectstore.PresignedUpload;
import dev.fondaria.valuationapi.strike.NavStrikeService;
import dev.fondaria.valuationapi.support.InMemoryAcceptedValuationRepository;
import dev.fondaria.valuationapi.support.InMemoryFundDirectory;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Task 20.8's own regression coverage: one fund failing to strike must not stop the rest of the
 * same batch's funds from being struck.
 *
 * <p>Not reachable through a realistic bad file — {@link ValuationBatchValidator} already rejects
 * the whole file if any row names a fund it cannot resolve ({@code
 * ValuationIngestionIntegrationTest#rejectsTheWholeFileWhenOneRowFails}), so by the time {@code
 * accept} runs, every fund in the batch is one {@link NavStrikeService#strike} would also resolve.
 * The failure this suite proves resilience against is therefore injected at the strike itself — a
 * transient fault (a {@code LedgerPort} blip, most plausibly) after a batch's own valuations are
 * already durably committed — via {@link ThrowingNavStrikeService}, the one seam {@code
 * NavStrikeService} being a concrete rather than sealed class makes available.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class ValuationIngestionRunnerTest {

  private static final AccountIdDeriver ACCOUNT_ID_DERIVER =
      new AccountIdDeriver(UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-03-02T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final LocalDate DATE = LocalDate.of(2026, 3, 2);
  private static final String SOURCE = "ACME-CUSTODY";

  @Test
  void one_fund_failing_to_strike_does_not_stop_the_rest_of_the_same_batch_from_being_struck() {
    InMemoryFundDirectory funds = new InMemoryFundDirectory();
    FundId strikesFine = funds.register(LocalDate.of(2020, 1, 1));
    FundId failsToStrike = funds.register(LocalDate.of(2020, 1, 1));

    FakeLedgerPort ledger = new FakeLedgerPort();
    ledger.givenUnitsInIssue(strikesFine, Units.of(1_000 * Units.SCALE));
    ledger.givenUnitsInIssue(failsToStrike, Units.of(1_000 * Units.SCALE));
    FakeFundNavRepository fundNavRepository = new FakeFundNavRepository();
    InMemoryAcceptedValuationRepository valuations = new InMemoryAcceptedValuationRepository();

    ThrowingNavStrikeService navStrikeService =
        new ThrowingNavStrikeService(
            funds,
            new StoredReportedBalanceSource(valuations),
            ledger,
            fundNavRepository,
            new InMemoryNavCache(
                fundNavRepository, NavSnapshotStore.NONE, CLOCK, Duration.ofHours(26)),
            failsToStrike);

    InMemoryValuationBatchRepository batches = new InMemoryValuationBatchRepository();
    StubObjectStore objectStore = new StubObjectStore();
    ValuationBatchValidator validator =
        new ValuationBatchValidator(
            funds,
            valuations,
            new BusinessCalendar(ZoneOffset.UTC, LocalTime.MAX, Set.of()),
            CLOCK);

    String csv = file(row(strikesFine, DATE, 105_000_000L), row(failsToStrike, DATE, 48_250_075L));
    String objectKey = "acme.csv";
    objectStore.put(objectKey, csv);
    ValuationBatch submitted =
        batches.submit(ValuationBatch.received(SOURCE, objectKey, sha256Hex(csv), 2, NOW));

    ValuationIngestionRunner runner =
        new ValuationIngestionRunner(
            batches,
            valuations,
            navStrikeService,
            objectStore,
            validator,
            new ValuationIngestionProperties(null, 0, null, 0, 0, null),
            new TransactionTemplate(new DirectTransactionManager()),
            CLOCK);

    int resolved = runner.sweep();

    assertThat(resolved).isEqualTo(1);
    ValuationBatch outcome = batches.find(submitted.batchId()).orElseThrow();
    assertThat(outcome.status())
        .as("both valuations were recorded and the batch accepted regardless of the strike fault")
        .isEqualTo(ValuationBatchStatus.ACCEPTED);
    assertThat(outcome.acceptedValuationCount()).hasValue(2);

    assertThat(fundNavRepository.findOriginal(strikesFine, DATE))
        .as("the healthy fund in the same batch was struck despite the other fund's failure")
        .isPresent();
    assertThat(fundNavRepository.findOriginal(failsToStrike, DATE))
        .as("the injected failure genuinely did not strike")
        .isEmpty();
  }

  private static String file(String... rows) {
    return ValuationFileParser.columnHeaderLine() + "\n" + String.join("\n", rows) + "\n";
  }

  private static String row(FundId fund, LocalDate date, long cents) {
    return fund.value() + "," + date + "," + cents + ",ACME-" + UUID.randomUUID();
  }

  private static String sha256Hex(String body) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /**
   * A real {@link NavStrikeService} for every fund except one, which always fails — the one seam
   * available for fault injection, since {@code NavStrikeService} is a concrete class with no port
   * of its own between it and {@link ValuationIngestionRunner}. Every dependency is the genuine
   * article; only the outcome for {@code alwaysFails} is overridden, so a fund that is meant to
   * strike successfully exercises the real strike arithmetic end to end.
   */
  private static final class ThrowingNavStrikeService extends NavStrikeService {
    private final FundId alwaysFails;

    ThrowingNavStrikeService(
        dev.fondaria.valuationapi.fund.FundDirectory funds,
        dev.fondaria.valuation.ReportedBalanceSource reportedBalances,
        dev.fondaria.ledger.LedgerPort ledger,
        dev.fondaria.rules.FundNavRepository fundNavRepository,
        dev.fondaria.valuation.NavCache navCache,
        FundId alwaysFails) {
      super(
          funds, reportedBalances, ledger, ACCOUNT_ID_DERIVER, fundNavRepository, navCache, CLOCK);
      this.alwaysFails = alwaysFails;
    }

    @Override
    public FundNav strike(FundId fundId, LocalDate valuationDate) {
      if (fundId.equals(alwaysFails)) {
        throw new IllegalStateException("simulated transient strike failure for " + fundId);
      }
      return super.strike(fundId, valuationDate);
    }
  }

  /** In-memory only — no {@code DataSource}, cannot reach PostgreSQL. */
  private static final class FakeLedgerPort implements dev.fondaria.ledger.LedgerPort {
    private final Map<AccountId, LedgerAccount> accounts = new HashMap<>();

    void givenUnitsInIssue(FundId fundId, Units balance) {
      AccountId accountId =
          ACCOUNT_ID_DERIVER.derive(LedgerCodes.AccountCode.UNITS_IN_ISSUE, fundId.toString());
      accounts.put(
          accountId,
          new LedgerAccount.Unit(
              accountId, LedgerCodes.AccountCode.UNITS_IN_ISSUE, false, 1001L, balance));
    }

    @Override
    public Optional<LedgerAccount> lookupAccount(AccountId id) {
      return Optional.ofNullable(accounts.get(id));
    }

    @Override
    public AccountId createAccount(dev.fondaria.ledger.CreateAccountCommand cmd) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public dev.fondaria.ledger.TransferResult createTransfer(
        dev.fondaria.ledger.CreateTransferCommand cmd) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public List<dev.fondaria.ledger.TransferResult> createTransfers(
        List<dev.fondaria.ledger.CreateTransferCommand> cmds) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public List<dev.fondaria.ledger.TransferResult> createLinkedChain(
        List<dev.fondaria.ledger.CreateTransferCommand> cmds) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public Optional<dev.fondaria.ledger.LedgerTransfer> lookupTransfer(
        dev.fondaria.ledger.TransferId id) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public List<dev.fondaria.ledger.LedgerTransfer> accountTransfers(
        AccountId id, dev.fondaria.ledger.TransferQuery query) {
      throw new UnsupportedOperationException("not used by this path");
    }
  }

  /**
   * In-memory only. Mirrors the real {@code JdbcFundNavRepository}'s idempotency: the first strike
   * for a {@code (fundId, valuationDate)} wins.
   */
  private static final class FakeFundNavRepository implements dev.fondaria.rules.FundNavRepository {
    private final Map<FundId, Map<LocalDate, FundNav>> originals = new HashMap<>();
    private long nextId = 1;

    @Override
    public Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate) {
      return Optional.ofNullable(originals.getOrDefault(fundId, Map.of()).get(valuationDate));
    }

    @Override
    public Optional<FundNav> findLatestLive(FundId fundId) {
      return originals.getOrDefault(fundId, Map.of()).values().stream()
          .max(java.util.Comparator.comparing(FundNav::valuationDate));
    }

    @Override
    public Optional<FundNav> findLive(FundId fundId, LocalDate valuationDate) {
      return Optional.ofNullable(originals.getOrDefault(fundId, Map.of()).get(valuationDate));
    }

    @Override
    public FundNav correct(FundNav candidate) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public List<FundNav> findLatestLivePerFund() {
      return originals.keySet().stream()
          .map(this::findLatestLive)
          .flatMap(Optional::stream)
          .toList();
    }

    @Override
    public FundNav strike(FundNav candidate) {
      Map<LocalDate, FundNav> byDate =
          originals.computeIfAbsent(candidate.fundId(), k -> new HashMap<>());
      FundNav existing = byDate.get(candidate.valuationDate());
      if (existing != null) {
        return existing;
      }
      FundNav persisted =
          new FundNav(
              nextId++,
              candidate.fundId(),
              candidate.valuationDate(),
              candidate.nav(),
              candidate.unitsInIssue(),
              candidate.source(),
              candidate.supersedesId(),
              candidate.reason(),
              candidate.publishedAt());
      byDate.put(candidate.valuationDate(), persisted);
      return persisted;
    }
  }

  /** In-memory only, keyed on object key — no object store. */
  private static final class StubObjectStore implements ObjectStore {
    private final Map<String, byte[]> objects = new HashMap<>();

    void put(String objectKey, String body) {
      objects.put(objectKey, body.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public PresignedUpload presignUpload(String objectKey) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public Optional<byte[]> fetch(String objectKey) {
      return Optional.ofNullable(objects.get(objectKey)).map(bytes -> bytes.clone());
    }
  }

  /** In-memory only, no leasing subtlety exercised — one batch, one sweep. */
  private static final class InMemoryValuationBatchRepository implements ValuationBatchRepository {
    private final Map<UUID, ValuationBatch> batches = new HashMap<>();

    @Override
    public ValuationBatch submit(ValuationBatch batch) {
      return batches.computeIfAbsent(batch.batchId(), id -> batch);
    }

    @Override
    public Optional<ValuationBatch> find(UUID batchId) {
      return Optional.ofNullable(batches.get(batchId));
    }

    @Override
    public List<ValuationBatch> claimForValidation(Instant now, Instant staleBefore, int limit) {
      return batches.values().stream()
          .filter(b -> b.status() == ValuationBatchStatus.RECEIVED)
          .limit(limit)
          .toList();
    }

    @Override
    public boolean resolve(ValuationBatch batch) {
      batches.put(batch.batchId(), batch);
      return true;
    }
  }
}
