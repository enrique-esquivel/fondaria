package dev.fondaria.contribution.participant.identity;

import dev.fondaria.common.OrganizationId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link ParticipantIdentityRepository} over {@code master.participant_identity}, using {@code
 * JdbcClient} (docs/ENGINEERING-RULES.md §5 — no ORM).
 */
@Repository
class JdbcParticipantIdentityRepository implements ParticipantIdentityRepository {

  private static final String SELECT_IDENTITY =
      """
      SELECT organization_id, participant_internal_ref, wrapped_data_key, encrypted_rfc,
             encrypted_curp, encrypted_name, encrypted_date_of_birth, date_of_birth_required,
             identity_captured_as_of, created_at, updated_at, key_destroyed_at
        FROM master.participant_identity
      """;

  private final JdbcClient jdbc;

  JdbcParticipantIdentityRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void upsert(ParticipantIdentity identity) {
    jdbc.sql(
            """
            INSERT INTO master.participant_identity (
                organization_id, participant_internal_ref, wrapped_data_key, encrypted_rfc,
                encrypted_curp, encrypted_name, encrypted_date_of_birth, date_of_birth_required,
                identity_captured_as_of, created_at, updated_at, key_destroyed_at)
            VALUES (:organizationId, :internalRef, :wrappedDataKey, :encryptedRfc, :encryptedCurp,
                    :encryptedName, :encryptedDateOfBirth, :dateOfBirthRequired,
                    :identityCapturedAsOf, :createdAt, :updatedAt, NULL)
            ON CONFLICT (organization_id, participant_internal_ref) DO UPDATE SET
                wrapped_data_key = EXCLUDED.wrapped_data_key,
                encrypted_rfc = EXCLUDED.encrypted_rfc,
                encrypted_curp = EXCLUDED.encrypted_curp,
                encrypted_name = EXCLUDED.encrypted_name,
                encrypted_date_of_birth = EXCLUDED.encrypted_date_of_birth,
                date_of_birth_required = EXCLUDED.date_of_birth_required,
                identity_captured_as_of = EXCLUDED.identity_captured_as_of,
                updated_at = EXCLUDED.updated_at,
                key_destroyed_at = NULL
            """)
        .param("organizationId", identity.organizationId().value())
        .param("internalRef", identity.internalRef())
        .param("wrappedDataKey", identity.wrappedDataKey().orElseThrow())
        .param("encryptedRfc", identity.encryptedRfc())
        .param("encryptedCurp", identity.encryptedCurp())
        .param("encryptedName", identity.encryptedName())
        .param("encryptedDateOfBirth", identity.encryptedDateOfBirth().orElse(null))
        .param("dateOfBirthRequired", identity.dateOfBirthRequired())
        .param("identityCapturedAsOf", identity.identityCapturedAsOf())
        .param("createdAt", Timestamp.from(identity.createdAt()))
        .param("updatedAt", Timestamp.from(identity.updatedAt()))
        .update();
  }

  @Override
  public Optional<ParticipantIdentity> find(OrganizationId organizationId, String internalRef) {
    return jdbc.sql(
            SELECT_IDENTITY
                + " WHERE organization_id = :organizationId AND participant_internal_ref = :ref")
        .param("organizationId", organizationId.value())
        .param("ref", internalRef)
        .query(JdbcParticipantIdentityRepository::mapIdentity)
        .optional();
  }

  @Override
  public boolean destroyKey(
      OrganizationId organizationId, String internalRef, Instant destroyedAt) {
    int updated =
        jdbc.sql(
                """
                UPDATE master.participant_identity
                   SET wrapped_data_key = NULL, key_destroyed_at = :destroyedAt,
                       updated_at = :destroyedAt
                 WHERE organization_id = :organizationId AND participant_internal_ref = :ref
                   AND wrapped_data_key IS NOT NULL
                """)
            .param("destroyedAt", Timestamp.from(destroyedAt))
            .param("organizationId", organizationId.value())
            .param("ref", internalRef)
            .update();
    return updated > 0;
  }

  private static ParticipantIdentity mapIdentity(ResultSet rs, int rowNum) throws SQLException {
    return new ParticipantIdentity(
        new OrganizationId(rs.getObject("organization_id", UUID.class)),
        rs.getString("participant_internal_ref"),
        Optional.ofNullable(rs.getBytes("wrapped_data_key")),
        rs.getBytes("encrypted_rfc"),
        rs.getBytes("encrypted_curp"),
        rs.getBytes("encrypted_name"),
        Optional.ofNullable(rs.getBytes("encrypted_date_of_birth")),
        rs.getBoolean("date_of_birth_required"),
        rs.getObject("identity_captured_as_of", LocalDate.class),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        Optional.ofNullable(rs.getTimestamp("key_destroyed_at")).map(Timestamp::toInstant));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return rs.getTimestamp(column).toInstant();
  }
}
