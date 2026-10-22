package dev.fondaria.contribution.fund;

import dev.fondaria.common.FundId;
import dev.fondaria.rules.Fund;
import dev.fondaria.rules.FundRepository;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * In-memory {@link FundRepository}, mirroring the two constraints {@code
 * JdbcFundRepositoryIntegrationTest} proves against a real PostgreSQL: {@code
 * fund_identity.unit_ledger_id} is unique across every fund, and {@code (fund_id, effective_from,
 * version)} is the primary key of {@code reference.fund} — both surfaced as {@link
 * DataIntegrityViolationException}, exactly as the JDBC implementation's driver would.
 *
 * <p><b>{@link #publish} returns the candidate exactly as given, never a row re-read from
 * storage</b> — matching {@code JdbcFundRepository}, which does the identical {@code return
 * candidate;} even though its identity insert is {@code ON CONFLICT (fund_id) DO NOTHING} and so
 * may not have persisted the candidate's own identity fields at all. A fake that "repaired" the
 * response here would hide exactly the caller mistake that matters most: a controller that stops
 * reusing the identity already on record for a second version would pass this fake's tests while
 * echoing a unit ledger id no TigerBeetle cluster actually carries.
 */
public final class InMemoryFundRepository implements FundRepository {

  private final Map<FundId, Fund> identity = new HashMap<>();
  private final Map<Long, FundId> unitLedgerIds = new HashMap<>();
  private final List<Fund> versions = new ArrayList<>();

  @Override
  public Optional<Fund> resolveAsOf(FundId fundId, LocalDate asOfDate) {
    return versions.stream()
        .filter(f -> f.fundId().equals(fundId))
        .filter(f -> !f.effectiveFrom().isAfter(asOfDate))
        .max(Comparator.comparing(Fund::effectiveFrom).thenComparing(Fund::version));
  }

  @Override
  public Fund publish(Fund candidate) {
    if (!identity.containsKey(candidate.fundId())) {
      FundId claimedBy = unitLedgerIds.get(candidate.unitLedgerId());
      if (claimedBy != null && !claimedBy.equals(candidate.fundId())) {
        throw new DataIntegrityViolationException(
            "duplicate key value violates unique constraint \"fund_identity_unit_ledger_id_unique\"");
      }
      identity.put(candidate.fundId(), candidate);
      unitLedgerIds.put(candidate.unitLedgerId(), candidate.fundId());
    }

    boolean versionExists =
        versions.stream()
            .anyMatch(
                f ->
                    f.fundId().equals(candidate.fundId())
                        && f.effectiveFrom().equals(candidate.effectiveFrom())
                        && f.version() == candidate.version());
    if (versionExists) {
      throw new DataIntegrityViolationException(
          "duplicate key value violates unique constraint \"fund_pk\"");
    }

    versions.add(candidate);
    return candidate;
  }
}
