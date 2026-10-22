package dev.fondaria.valuationapi.correction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import dev.fondaria.valuation.InMemoryNavCache;
import dev.fondaria.valuation.NavSnapshotStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.DisplayNameGenerator;
import org.junit.jupiter.api.Test;

/**
 * Exercises {@link NavCorrectionService} against an in-memory {@link FakeFundNavRepository} only —
 * the SQL and the schema are proven separately by {@code JdbcFundNavRepositoryIntegrationTest}
 * against a real PostgreSQL, the same split {@code NavStrikeServiceTest} uses.
 */
@DisplayNameGeneration(DisplayNameGenerator.ReplaceUnderscores.class)
class NavCorrectionServiceTest {

  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-03-05T00:00:00Z"), ZoneOffset.UTC);
  private static final LocalDate DATE = LocalDate.of(2026, 3, 2);

  @Test
  void a_correction_supersedes_the_live_row_and_the_cache_reflects_it_immediately() {
    FundId fundId = FundId.of(UUID.randomUUID());
    FakeFundNavRepository repository = new FakeFundNavRepository();
    FundNav original =
        repository.seedLive(
            fundId, DATE, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN), Units.of(1_000 * Units.SCALE));
    InMemoryNavCache cache =
        new InMemoryNavCache(repository, NavSnapshotStore.NONE, CLOCK, Duration.ofHours(26));
    cache.refresh(fundId); // the cache is holding the original price, as it would in production
    NavCorrectionService service = new NavCorrectionService(repository, cache, CLOCK);

    FundNav corrected =
        service.correct(
            fundId, DATE, Nav.of(9 * Nav.SCALE, CurrencyCode.MXN), "provider restated the balance");

    assertThat(corrected.supersedesId()).isEqualTo(original.navVersionId());
    assertThat(corrected.nav()).isEqualTo(Nav.of(9 * Nav.SCALE, CurrencyCode.MXN));
    assertThat(corrected.unitsInIssue()).isEqualTo(original.unitsInIssue());
    // The cache must not still be serving the price this correction just superseded.
    assertThat(cache.current(fundId).orElseThrow().nav()).isEqualTo(corrected.nav());
  }

  @Test
  void a_blank_reason_is_refused() {
    FundId fundId = FundId.of(UUID.randomUUID());
    FakeFundNavRepository repository = new FakeFundNavRepository();
    repository.seedLive(fundId, DATE, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN), Units.of(1));
    NavCorrectionService service =
        new NavCorrectionService(
            repository,
            new InMemoryNavCache(repository, NavSnapshotStore.NONE, CLOCK, Duration.ofHours(26)),
            CLOCK);

    assertThatThrownBy(
            () -> service.correct(fundId, DATE, Nav.of(9 * Nav.SCALE, CurrencyCode.MXN), " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void a_date_with_no_live_nav_is_refused() {
    FundId fundId = FundId.of(UUID.randomUUID());
    FakeFundNavRepository repository = new FakeFundNavRepository();
    NavCorrectionService service =
        new NavCorrectionService(
            repository,
            new InMemoryNavCache(repository, NavSnapshotStore.NONE, CLOCK, Duration.ofHours(26)),
            CLOCK);

    assertThatThrownBy(
            () -> service.correct(fundId, DATE, Nav.of(9 * Nav.SCALE, CurrencyCode.MXN), "why"))
        .isInstanceOf(NoSuchElementException.class);
  }

  @Test
  void a_second_correction_of_an_already_corrected_row_is_refused() {
    FundId fundId = FundId.of(UUID.randomUUID());
    FakeFundNavRepository repository = new FakeFundNavRepository();
    repository.seedLive(fundId, DATE, Nav.of(10 * Nav.SCALE, CurrencyCode.MXN), Units.of(1));
    NavCorrectionService service =
        new NavCorrectionService(
            repository,
            new InMemoryNavCache(repository, NavSnapshotStore.NONE, CLOCK, Duration.ofHours(26)),
            CLOCK);
    service.correct(fundId, DATE, Nav.of(9 * Nav.SCALE, CurrencyCode.MXN), "first fix");

    // The original is no longer live — the first correction already supersedes it. A second
    // candidate naming the same original id must be refused, not silently accepted as a
    // second correction of the same row.
    assertThatThrownBy(
            () -> repository.correct(directCorrectionOf(repository, fundId, "second fix")))
        .isInstanceOf(IllegalStateException.class);
  }

  private FundNav directCorrectionOf(
      FakeFundNavRepository repository, FundId fundId, String reason) {
    FundNav original = repository.originalFor(fundId, DATE);
    return new FundNav(
        0L,
        fundId,
        DATE,
        Nav.of(8 * Nav.SCALE, CurrencyCode.MXN),
        original.unitsInIssue(),
        original.source(),
        original.navVersionId(),
        reason,
        CLOCK.instant());
  }

  /**
   * In-memory only — no {@code DataSource}, cannot reach PostgreSQL. Holds "live" the same way the
   * real table does: the row nothing else's {@code supersedesId} points at.
   */
  private static final class FakeFundNavRepository implements FundNavRepository {
    private final Map<FundId, List<FundNav>> rows = new HashMap<>();
    private long nextId = 1;

    FundNav seedLive(FundId fundId, LocalDate date, Nav nav, Units unitsInIssue) {
      FundNav row =
          new FundNav(
              nextId++, fundId, date, nav, unitsInIssue, "MODEL_B", null, null, CLOCK.instant());
      rows.computeIfAbsent(fundId, k -> new ArrayList<>()).add(row);
      return row;
    }

    FundNav originalFor(FundId fundId, LocalDate date) {
      return findOriginal(fundId, date).orElseThrow();
    }

    @Override
    public Optional<FundNav> findOriginal(FundId fundId, LocalDate date) {
      return rows.getOrDefault(fundId, List.of()).stream()
          .filter(row -> row.valuationDate().equals(date) && row.supersedesId() == null)
          .findFirst();
    }

    @Override
    public FundNav strike(FundNav candidate) {
      throw new UnsupportedOperationException("not used by this path");
    }

    @Override
    public Optional<FundNav> findLive(FundId fundId, LocalDate date) {
      List<FundNav> all = rows.getOrDefault(fundId, List.of());
      return all.stream()
          .filter(row -> row.valuationDate().equals(date) && isLive(all, row))
          .findFirst();
    }

    @Override
    public FundNav correct(FundNav candidate) {
      List<FundNav> all = rows.getOrDefault(candidate.fundId(), List.of());
      boolean stillLive =
          all.stream()
              .anyMatch(
                  row ->
                      row.navVersionId() == candidate.supersedesId()
                          && row.valuationDate().equals(candidate.valuationDate())
                          && isLive(all, row));
      if (!stillLive) {
        throw new IllegalStateException(
            "nav_version_id " + candidate.supersedesId() + " is not live");
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
      rows.computeIfAbsent(candidate.fundId(), k -> new ArrayList<>()).add(persisted);
      return persisted;
    }

    @Override
    public Optional<FundNav> findLatestLive(FundId fundId) {
      List<FundNav> all = rows.getOrDefault(fundId, List.of());
      return all.stream()
          .filter(row -> isLive(all, row))
          .max(Comparator.comparing(FundNav::valuationDate).thenComparing(FundNav::navVersionId));
    }

    @Override
    public List<FundNav> findLatestLivePerFund() {
      return rows.keySet().stream().map(this::findLatestLive).flatMap(Optional::stream).toList();
    }

    private static boolean isLive(List<FundNav> all, FundNav row) {
      return all.stream().noneMatch(s -> Long.valueOf(row.navVersionId()).equals(s.supersedesId()));
    }
  }
}
