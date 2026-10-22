package dev.fondaria.valuationapi.strike;

import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.FundId;
import dev.fondaria.common.Nav;
import dev.fondaria.common.Units;
import dev.fondaria.rules.FundNav;
import dev.fondaria.rules.FundNavRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link FundNavRepository} over {@code reference.fund_nav}, using {@code JdbcClient} (AGENTS.md §5
 * — no ORM).
 *
 * <p><b>Neither {@code strike} nor {@code correct} ever issues an {@code UPDATE}.</b> A conflict on
 * the partial unique index {@code fund_nav_fund_date_original_unique} means an original strike
 * already exists for this fund and date; the existing row is read back and returned rather than
 * overwritten. A correction is always a new row, never an edit to the one it supersedes.
 */
@Repository
public class JdbcFundNavRepository implements FundNavRepository {

  private static final String SELECT_ORIGINAL =
      """
      SELECT n.nav_version_id, n.fund_id, n.valuation_date, n.nav_scaled, n.units_in_issue,
             n.source, n.supersedes_id, n.reason, n.published_at, i.base_currency
        FROM reference.fund_nav n
        JOIN reference.fund_identity i ON i.fund_id = n.fund_id
       WHERE n.fund_id = :fundId
         AND n.valuation_date = :valuationDate
         AND n.supersedes_id IS NULL
      """;

  /**
   * The live row for one fund: most recent valuation date, and within it the row nothing
   * supersedes.
   *
   * <p><b>{@code NOT EXISTS}, not {@code supersedes_id IS NULL}</b> — see {@link
   * FundNavRepository#findLatestLive}. The correction carries the pointer, so the original is the
   * row holding null and filtering on it would serve a price already corrected.
   */
  private static final String SELECT_LATEST_LIVE =
      """
      SELECT n.nav_version_id, n.fund_id, n.valuation_date, n.nav_scaled, n.units_in_issue,
             n.source, n.supersedes_id, n.reason, n.published_at, i.base_currency
        FROM reference.fund_nav n
        JOIN reference.fund_identity i ON i.fund_id = n.fund_id
       WHERE n.fund_id = :fundId
         AND NOT EXISTS (SELECT 1
                           FROM reference.fund_nav s
                          WHERE s.supersedes_id = n.nav_version_id)
       ORDER BY n.valuation_date DESC, n.nav_version_id DESC
       LIMIT 1
      """;

  /** {@link #SELECT_LATEST_LIVE}'s same rule, pinned to one date instead of the fund's latest. */
  private static final String SELECT_LIVE_FOR_DATE =
      """
      SELECT n.nav_version_id, n.fund_id, n.valuation_date, n.nav_scaled, n.units_in_issue,
             n.source, n.supersedes_id, n.reason, n.published_at, i.base_currency
        FROM reference.fund_nav n
        JOIN reference.fund_identity i ON i.fund_id = n.fund_id
       WHERE n.fund_id = :fundId
         AND n.valuation_date = :valuationDate
         AND NOT EXISTS (SELECT 1
                           FROM reference.fund_nav s
                          WHERE s.supersedes_id = n.nav_version_id)
      """;

  /** The same rule as {@link #SELECT_LATEST_LIVE}, one row per fund, in one round trip. */
  private static final String SELECT_LATEST_LIVE_PER_FUND =
      """
      SELECT DISTINCT ON (n.fund_id)
             n.nav_version_id, n.fund_id, n.valuation_date, n.nav_scaled, n.units_in_issue,
             n.source, n.supersedes_id, n.reason, n.published_at, i.base_currency
        FROM reference.fund_nav n
        JOIN reference.fund_identity i ON i.fund_id = n.fund_id
       WHERE NOT EXISTS (SELECT 1
                           FROM reference.fund_nav s
                          WHERE s.supersedes_id = n.nav_version_id)
       ORDER BY n.fund_id, n.valuation_date DESC, n.nav_version_id DESC
      """;

  private final JdbcClient jdbc;

  public JdbcFundNavRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<FundNav> findOriginal(FundId fundId, LocalDate valuationDate) {
    return jdbc.sql(SELECT_ORIGINAL)
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .query(JdbcFundNavRepository::mapFundNav)
        .optional();
  }

  /**
   * {@code ON CONFLICT ... DO NOTHING} against the partial unique index, not an {@code
   * insertIfAbsent}-then-check race: two concurrent strikes for the same fund and date resolve to
   * exactly one row regardless of interleaving, and the loser reads that row back rather than
   * retrying.
   */
  @Override
  public FundNav strike(FundNav candidate) {
    if (candidate.supersedesId() != null) {
      throw new IllegalArgumentException(
          "strike does not accept a correction candidate; see correct");
    }

    Optional<Long> insertedId =
        jdbc.sql(
                """
                INSERT INTO reference.fund_nav (
                    fund_id, valuation_date, nav_scaled, units_in_issue, source, published_at)
                VALUES (:fundId, :valuationDate, :navScaled, :unitsInIssue, :source, :publishedAt)
                ON CONFLICT (fund_id, valuation_date) WHERE supersedes_id IS NULL DO NOTHING
                RETURNING nav_version_id
                """)
            .param("fundId", candidate.fundId().value())
            .param("valuationDate", candidate.valuationDate())
            .param("navScaled", candidate.nav().scaled())
            .param("unitsInIssue", candidate.unitsInIssue().scaled())
            .param("source", candidate.source())
            .param("publishedAt", Timestamp.from(candidate.publishedAt()))
            .query(Long.class)
            .optional();

    if (insertedId.isPresent()) {
      return withNavVersionId(candidate, insertedId.get());
    }
    return findOriginal(candidate.fundId(), candidate.valuationDate())
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Strike conflicted but no original row is readable for "
                        + candidate.fundId()
                        + " on "
                        + candidate.valuationDate()));
  }

  @Override
  public Optional<FundNav> findLive(FundId fundId, LocalDate valuationDate) {
    return jdbc.sql(SELECT_LIVE_FOR_DATE)
        .param("fundId", fundId.value())
        .param("valuationDate", valuationDate)
        .query(JdbcFundNavRepository::mapFundNav)
        .optional();
  }

  /**
   * {@code INSERT ... WHERE EXISTS}, not a read-then-write: the row {@code candidate.supersedesId}
   * names must exist, match {@code candidate}'s fund and date, and still be live at the instant of
   * the insert, or nothing is written. {@code fund_nav_supersedes_id_unique} (V18) closes the
   * remaining race between two corrections that both pass this check for the same row — the loser
   * conflicts on that index and this method surfaces it as {@link IllegalStateException}, the same
   * way a stale candidate does.
   */
  @Override
  public FundNav correct(FundNav candidate) {
    if (candidate.supersedesId() == null) {
      throw new IllegalArgumentException("correct requires candidate.supersedesId() to be set");
    }

    Optional<Long> insertedId;
    try {
      insertedId =
          jdbc.sql(
                  """
                  INSERT INTO reference.fund_nav (
                      fund_id, valuation_date, nav_scaled, units_in_issue, source,
                      supersedes_id, reason, published_at)
                  SELECT :fundId, :valuationDate, :navScaled, :unitsInIssue, :source,
                         :supersedesId, :reason, :publishedAt
                    FROM reference.fund_nav n
                   WHERE n.nav_version_id = :supersedesId
                     AND n.fund_id = :fundId
                     AND n.valuation_date = :valuationDate
                     AND NOT EXISTS (SELECT 1
                                       FROM reference.fund_nav s
                                      WHERE s.supersedes_id = n.nav_version_id)
                  RETURNING nav_version_id
                  """)
              .param("fundId", candidate.fundId().value())
              .param("valuationDate", candidate.valuationDate())
              .param("navScaled", candidate.nav().scaled())
              .param("unitsInIssue", candidate.unitsInIssue().scaled())
              .param("source", candidate.source())
              .param("supersedesId", candidate.supersedesId())
              .param("reason", candidate.reason())
              .param("publishedAt", Timestamp.from(candidate.publishedAt()))
              .query(Long.class)
              .optional();
    } catch (DataIntegrityViolationException e) {
      throw new IllegalStateException(
          "A correction already exists for nav_version_id " + candidate.supersedesId(), e);
    }

    return insertedId
        .map(id -> withNavVersionId(candidate, id))
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "nav_version_id "
                        + candidate.supersedesId()
                        + " is not a live strike for "
                        + candidate.fundId()
                        + " on "
                        + candidate.valuationDate()));
  }

  @Override
  public Optional<FundNav> findLatestLive(FundId fundId) {
    return jdbc.sql(SELECT_LATEST_LIVE)
        .param("fundId", fundId.value())
        .query(JdbcFundNavRepository::mapFundNav)
        .optional();
  }

  @Override
  public List<FundNav> findLatestLivePerFund() {
    return jdbc.sql(SELECT_LATEST_LIVE_PER_FUND).query(JdbcFundNavRepository::mapFundNav).list();
  }

  private static FundNav withNavVersionId(FundNav candidate, long navVersionId) {
    return new FundNav(
        navVersionId,
        candidate.fundId(),
        candidate.valuationDate(),
        candidate.nav(),
        candidate.unitsInIssue(),
        candidate.source(),
        candidate.supersedesId(),
        candidate.reason(),
        candidate.publishedAt());
  }

  private static FundNav mapFundNav(ResultSet rs, int rowNum) throws SQLException {
    CurrencyCode currency = CurrencyCode.valueOf(rs.getString("base_currency"));
    Long supersedesId = (Long) rs.getObject("supersedes_id");
    return new FundNav(
        rs.getLong("nav_version_id"),
        new FundId(rs.getObject("fund_id", UUID.class)),
        rs.getObject("valuation_date", LocalDate.class),
        Nav.of(rs.getLong("nav_scaled"), currency),
        Units.of(rs.getLong("units_in_issue")),
        rs.getString("source"),
        supersedesId,
        rs.getString("reason"),
        rs.getTimestamp("published_at").toInstant());
  }
}
