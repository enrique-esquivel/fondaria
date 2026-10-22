package dev.fondaria.contribution.organization;

import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TenantRef;
import dev.fondaria.contribution.config.IdentityProperties;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.LedgerCodes;
import dev.fondaria.ledger.LedgerPort;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Registration and maintenance of client organizations (Task 2b.1).
 *
 * <p><b>Registration is idempotent without a lookup.</b> {@code organization_id} is derived from
 * the tax identifier, so registering the same employer twice converges on one row and one set of
 * ledger accounts — the {@code INSERT} is conditional and {@code createAccount} is idempotent on a
 * derived account id. Nothing here reads a table first to decide whether to write.
 *
 * <p><b>Write order, and why.</b> The PostgreSQL row is written before the ledger accounts, per
 * ADR-0011: a crash between them leaves an organization with no accounts, which the next
 * registration attempt repairs, whereas the reverse leaves ledger accounts with nothing recording
 * whose they are — and that is exactly the defect ADR-0011 exists to close. Provisioning therefore
 * runs on <i>every</i> registration call, not only the one that inserted the row.
 *
 * <p><b>An update never touches the ledger.</b> Correcting a legal name changes no id, so it
 * changes no account: the accounts are derived from {@code organization_id}, and that is derived
 * from a tax identifier no update can alter.
 */
@Service
public class OrganizationService {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrganizationService.class);

  private final OrganizationRepository repository;
  private final LedgerPort ledger;
  private final AccountIdDeriver accountIds;
  private final IdentityProperties identity;
  private final Clock clock;

  public OrganizationService(
      OrganizationRepository repository,
      LedgerPort ledger,
      AccountIdDeriver accountIds,
      IdentityProperties identity,
      Clock clock) {
    this.repository = repository;
    this.ledger = ledger;
    this.accountIds = accountIds;
    this.identity = identity;
    this.clock = clock;
    // Fails here rather than deriving an id under a null namespace, which would
    // produce ids nothing could ever look up again.
    identity.requireComplete();
  }

  public OrganizationRegistration register(RegisterOrganizationCommand command) {
    OrganizationId organizationId =
        OrganizationId.derive(identity.organizationNamespace(), command.taxIdentifier());
    Instant now = clock.instant();
    boolean created =
        repository.insertIfAbsent(
            new Organization(
                organizationId,
                command.taxIdentifier(),
                command.legalName(),
                command.contractReference(),
                command.feeBracket(),
                OrganizationStatus.ACTIVE,
                1,
                now,
                now));

    provisionLedgerAccounts(organizationId);

    Organization stored =
        repository
            .findById(organizationId)
            .orElseThrow(() -> new OrganizationNotFoundException(organizationId));
    // The derived id only; the tax identifier it came from never reaches a log (AGENTS.md §7).
    LOGGER.info(
        "Organization registration processed, organizationId={}, created={}",
        organizationId,
        created);
    return new OrganizationRegistration(stored, created);
  }

  public Organization update(OrganizationId organizationId, OrganizationChange change) {
    Organization current = get(organizationId);
    if (change.isEmpty()) {
      return current;
    }
    Organization next = change.applyTo(current, clock.instant());
    repository.update(next);
    LOGGER.info(
        "Organization updated, organizationId={}, revision={}", organizationId, next.revision());
    return next;
  }

  public Organization get(OrganizationId organizationId) {
    return repository
        .findById(organizationId)
        .orElseThrow(() -> new OrganizationNotFoundException(organizationId));
  }

  public List<Organization> list(int limit, int offset) {
    return repository.findAll(limit, offset);
  }

  public List<OrganizationRevision> revisions(OrganizationId organizationId) {
    Organization existing = get(organizationId);
    return repository.findRevisions(existing.organizationId());
  }

  /**
   * Opens the cash-ledger accounts this organization needs: its own {@code EMPLOYER_COLLECTIONS},
   * and the platform-wide {@code BANK_CLEARING} the receipt leg debits — the latter "if absent",
   * which needs no check, because a derived id makes a second attempt a no-op inside TigerBeetle.
   */
  private void provisionLedgerAccounts(OrganizationId organizationId) {
    AccountId bankClearing = accountIds.derive(LedgerCodes.AccountCode.BANK_CLEARING);
    AccountId employerCollections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    // BANK_CLEARING is a platform-wide singleton pooled across every organization — no tenant.
    ledger.createAccount(
        new CreateAccountCommand(
            bankClearing, LedgerCodes.CASH_LEDGER_ID, LedgerCodes.AccountCode.BANK_CLEARING));
    // EMPLOYER_COLLECTIONS is this organization's own account (ADR-0005: tenant-first).
    ledger.createAccount(
        new CreateAccountCommand(
                employerCollections,
                LedgerCodes.CASH_LEDGER_ID,
                LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS)
            .withTenant(TenantRef.derive(identity.ledgerNamespace(), organizationId)));
  }
}
