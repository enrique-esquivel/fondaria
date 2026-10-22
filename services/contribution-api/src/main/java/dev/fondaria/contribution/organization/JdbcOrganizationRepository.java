package dev.fondaria.contribution.organization;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdScheme;
import dev.fondaria.common.TaxIdentifier;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OrganizationRepository} over {@code master.organization} and {@code
 * master.organization_revision}, using {@code JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 *
 * <p>Current state and history are written in one transaction, so an organization can never exist
 * with no history row, nor gain a history row describing a state it never held.
 *
 * <p><b>No balance is ever read here.</b> These tables hold who an organization is; what it owns is
 * read through {@code LedgerPort} and nowhere else.
 */
@Repository
public class JdbcOrganizationRepository implements OrganizationRepository {

  /** Bounds an accidental unbounded scan; the API's own maximum is smaller. */
  private static final int MAX_LIMIT = 500;

  /**
   * The revision comes from the history table rather than a column on the current-state row, so the
   * two can never disagree about how many revisions exist.
   */
  private static final String SELECT_ORGANIZATION =
      """
      SELECT o.organization_id, o.tax_id_scheme, o.tax_id, o.legal_name,
             o.contract_reference, o.fee_bracket, o.status, o.created_at, o.updated_at,
             (SELECT max(r.revision)
                FROM master.organization_revision r
               WHERE r.organization_id = o.organization_id) AS revision
        FROM master.organization o
      """;

  private final JdbcClient jdbc;

  public JdbcOrganizationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public boolean insertIfAbsent(Organization organization) {
    int inserted =
        jdbc.sql(
                """
                INSERT INTO master.organization (
                    organization_id, tax_id_scheme, tax_id, legal_name,
                    contract_reference, fee_bracket, status, created_at, updated_at)
                VALUES (:organizationId, :taxIdScheme, :taxId, :legalName,
                        :contractReference, :feeBracket, :status, :createdAt, :updatedAt)
                ON CONFLICT (organization_id) DO NOTHING
                """)
            .param("organizationId", organization.organizationId().value())
            .param("taxIdScheme", organization.taxIdentifier().scheme().name())
            .param("taxId", organization.taxIdentifier().value())
            .param("legalName", organization.legalName())
            .param("contractReference", organization.contractReference().orElse(null))
            .param("feeBracket", organization.feeBracket().orElse(null))
            .param("status", organization.status().name())
            .param("createdAt", Timestamp.from(organization.createdAt()))
            .param("updatedAt", Timestamp.from(organization.updatedAt()))
            .update();
    if (inserted == 0) {
      return false;
    }
    appendRevision(organization);
    return true;
  }

  @Override
  @Transactional
  public void update(Organization next) {
    // FOR UPDATE, so two concurrent updates cannot both read revision n and then
    // both try to append n+1 — the second waits and sees the first's result.
    Organization current =
        lockById(next.organizationId())
            .orElseThrow(() -> new OrganizationNotFoundException(next.organizationId()));
    if (next.revision() != current.revision() + 1) {
      throw new IllegalStateException(
          "Organization revision moved under this update; expected "
              + (current.revision() + 1)
              + " but was "
              + next.revision());
    }
    jdbc.sql(
            """
            UPDATE master.organization
               SET legal_name = :legalName,
                   contract_reference = :contractReference,
                   fee_bracket = :feeBracket,
                   status = :status,
                   updated_at = :updatedAt
             WHERE organization_id = :organizationId
            """)
        .param("organizationId", next.organizationId().value())
        .param("legalName", next.legalName())
        .param("contractReference", next.contractReference().orElse(null))
        .param("feeBracket", next.feeBracket().orElse(null))
        .param("status", next.status().name())
        .param("updatedAt", Timestamp.from(next.updatedAt()))
        .update();
    appendRevision(next);
  }

  @Override
  public Optional<Organization> findById(OrganizationId organizationId) {
    return jdbc.sql(SELECT_ORGANIZATION + " WHERE o.organization_id = :id")
        .param("id", organizationId.value())
        .query(JdbcOrganizationRepository::mapOrganization)
        .optional();
  }

  @Override
  public List<Organization> findAll(int limit, int offset) {
    return jdbc.sql(
            SELECT_ORGANIZATION
                + " ORDER BY o.created_at, o.organization_id LIMIT :limit OFFSET :offset")
        .param("limit", Math.min(Math.max(limit, 1), MAX_LIMIT))
        .param("offset", Math.max(offset, 0))
        .query(JdbcOrganizationRepository::mapOrganization)
        .list();
  }

  @Override
  public List<OrganizationRevision> findRevisions(OrganizationId organizationId) {
    return jdbc.sql(
            """
            SELECT organization_id, revision, legal_name, contract_reference,
                   fee_bracket, status, recorded_at
              FROM master.organization_revision
             WHERE organization_id = :id
             ORDER BY revision
            """)
        .param("id", organizationId.value())
        .query(
            (ResultSet rs, int rowNum) ->
                new OrganizationRevision(
                    new OrganizationId(rs.getObject("organization_id", UUID.class)),
                    rs.getInt("revision"),
                    rs.getString("legal_name"),
                    Optional.ofNullable(rs.getString("contract_reference")),
                    Optional.ofNullable(rs.getString("fee_bracket")),
                    OrganizationStatus.parse(rs.getString("status")),
                    instant(rs, "recorded_at")))
        .list();
  }

  private void appendRevision(Organization organization) {
    jdbc.sql(
            """
            INSERT INTO master.organization_revision (
                organization_id, revision, legal_name, contract_reference,
                fee_bracket, status, recorded_at)
            VALUES (:organizationId, :revision, :legalName, :contractReference,
                    :feeBracket, :status, :recordedAt)
            """)
        .param("organizationId", organization.organizationId().value())
        .param("revision", organization.revision())
        .param("legalName", organization.legalName())
        .param("contractReference", organization.contractReference().orElse(null))
        .param("feeBracket", organization.feeBracket().orElse(null))
        .param("status", organization.status().name())
        .param("recordedAt", Timestamp.from(organization.updatedAt()))
        .update();
  }

  private Optional<Organization> lockById(OrganizationId organizationId) {
    return jdbc.sql(SELECT_ORGANIZATION + " WHERE o.organization_id = :id FOR UPDATE OF o")
        .param("id", organizationId.value())
        .query(JdbcOrganizationRepository::mapOrganization)
        .optional();
  }

  private static Organization mapOrganization(ResultSet rs, int rowNum) throws SQLException {
    return new Organization(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        TaxIdentifier.of(
            TaxIdScheme.valueOf(rs.getString("tax_id_scheme")), rs.getString("tax_id")),
        rs.getString("legal_name"),
        Optional.ofNullable(rs.getString("contract_reference")),
        Optional.ofNullable(rs.getString("fee_bracket")),
        OrganizationStatus.parse(rs.getString("status")),
        rs.getInt("revision"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getTimestamp(column).toInstant();
  }
}
