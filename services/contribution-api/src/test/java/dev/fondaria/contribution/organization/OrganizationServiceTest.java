package dev.fondaria.contribution.organization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import dev.fondaria.common.OrganizationId;
import dev.fondaria.common.TaxIdScheme;
import dev.fondaria.common.TaxIdentifier;
import dev.fondaria.common.TenantRef;
import dev.fondaria.contribution.config.IdentityProperties;
import dev.fondaria.ledger.AccountId;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.CreateAccountCommand;
import dev.fondaria.ledger.LedgerCodes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** Task 2b.1's acceptance criteria, at the level where the decisions are actually made. */
class OrganizationServiceTest {

  private static final UUID ORGANIZATION_NAMESPACE =
      UUID.fromString("6ba7b810-9dad-11d1-80b4-00c04fd430c8");
  private static final UUID LEDGER_NAMESPACE =
      UUID.fromString("6ba7b811-9dad-11d1-80b4-00c04fd430c8");

  private InMemoryOrganizationRepository repository;
  private RecordingLedgerPort ledger;
  private AccountIdDeriver accountIds;
  private MutableClock clock;
  private OrganizationService service;
  private Level rootLevelBefore;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void setUp() {
    repository = new InMemoryOrganizationRepository();
    ledger = new RecordingLedgerPort();
    accountIds = new AccountIdDeriver(LEDGER_NAMESPACE);
    clock = new MutableClock(Instant.parse("2026-08-18T10:00:00Z"));
    service =
        new OrganizationService(
            repository,
            ledger,
            accountIds,
            new IdentityProperties(ORGANIZATION_NAMESPACE, LEDGER_NAMESPACE),
            clock);
    logs = new ListAppender<>();
    logs.setContext((LoggerContext) LoggerFactory.getILoggerFactory());
    logs.start();
    rootLogger().addAppender(logs);
    rootLevelBefore = rootLogger().getLevel();
    rootLogger().setLevel(Level.TRACE);
  }

  @AfterEach
  void tearDown() {
    rootLogger().detachAppender(logs);
    // The root logger is JVM-wide. Left at TRACE, every later test class logs framework request
    // bodies too, and OrganizationRegistrationIntegrationTest then finds an RFC in a log line
    // that only exists because of this class.
    rootLogger().setLevel(rootLevelBefore);
  }

  @Test
  @DisplayName("registering the same tax identifier twice yields one organization")
  void registeringTwiceYieldsOneOrganization() {
    TaxIdentifier taxId = FreshTaxIdentifiers.fresh();

    OrganizationRegistration first = service.register(command(taxId, "Acme S.A. de C.V."));
    OrganizationRegistration second = service.register(command(taxId, "Acme S.A. de C.V."));

    assertThat(first.created()).isTrue();
    assertThat(second.created()).isFalse();
    assertThat(second.organization().organizationId())
        .isEqualTo(first.organization().organizationId());
    assertThat(repository.findAll(100, 0)).hasSize(1);
  }

  @Test
  @DisplayName("registering the same tax identifier twice yields one ledger account")
  void registeringTwiceYieldsOneLedgerAccount() {
    TaxIdentifier taxId = FreshTaxIdentifiers.fresh();

    OrganizationId organizationId =
        service.register(command(taxId, "Acme")).organization().organizationId();
    service.register(command(taxId, "Acme"));

    AccountId collections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    assertThat(ledger.accounts()).contains(collections);
    assertThat(
            ledger.calls().stream()
                .filter(cmd -> cmd.id().equals(collections))
                .map(CreateAccountCommand::accountCode))
        .as("the second attempt is idempotent, not a second account")
        .containsExactly(
            LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS,
            LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS);
    assertThat(ledger.accounts().stream().filter(id -> id.equals(collections)).count())
        .isEqualTo(1);
  }

  @Test
  @DisplayName("registration opens the platform's BANK_CLEARING account if it is absent")
  void registrationOpensBankClearing() {
    service.register(command(FreshTaxIdentifiers.fresh(), "Acme"));

    assertThat(ledger.accounts())
        .contains(accountIds.derive(LedgerCodes.AccountCode.BANK_CLEARING));
    assertThat(ledger.calls())
        .allSatisfy(cmd -> assertThat(cmd.ledgerId()).isEqualTo(LedgerCodes.CASH_LEDGER_ID));
  }

  @Test
  @DisplayName(
      "EMPLOYER_COLLECTIONS carries this organization's tenant ref; BANK_CLEARING carries none")
  void collectionsCarriesTheTenantRefAndBankClearingDoesNot() {
    OrganizationId organizationId =
        service
            .register(command(FreshTaxIdentifiers.fresh(), "Acme"))
            .organization()
            .organizationId();
    TenantRef expected = TenantRef.derive(LEDGER_NAMESPACE, organizationId);

    AccountId collections =
        accountIds.derive(LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope());
    AccountId bankClearing = accountIds.derive(LedgerCodes.AccountCode.BANK_CLEARING);

    assertThat(ledger.calls())
        .filteredOn(cmd -> cmd.id().equals(collections))
        .allSatisfy(cmd -> assertThat(cmd.tenant()).isEqualTo(expected));
    assertThat(ledger.calls())
        .filteredOn(cmd -> cmd.id().equals(bankClearing))
        .allSatisfy(cmd -> assertThat(cmd.tenant().isNone()).isTrue());
  }

  @Test
  @DisplayName("spelling variants of one RFC are one employer, not two")
  void spellingVariantsAreOneEmployer() {
    String rfc = FreshTaxIdentifiers.freshRfc();

    OrganizationRegistration upper =
        service.register(command(TaxIdentifier.of(TaxIdScheme.MX_RFC, rfc), "Acme"));
    OrganizationRegistration punctuated =
        service.register(
            command(
                TaxIdentifier.of(
                    TaxIdScheme.MX_RFC,
                    rfc.substring(0, 3).toLowerCase(java.util.Locale.ROOT)
                        + "-"
                        + rfc.substring(3, 9)
                        + "-"
                        + rfc.substring(9)),
                "Acme"));

    assertThat(punctuated.organization().organizationId())
        .isEqualTo(upper.organization().organizationId());
    assertThat(punctuated.created()).isFalse();
  }

  @Test
  @DisplayName("updating the legal name leaves the id and the ledger accounts unchanged")
  void updateLeavesIdAndLedgerAlone() {
    TaxIdentifier taxId = FreshTaxIdentifiers.fresh();
    Organization registered = service.register(command(taxId, "Acme S.A. de C.V.")).organization();
    List<AccountId> afterRegistration = List.copyOf(ledger.accounts());
    int callsAfterRegistration = ledger.calls().size();
    clock.advance(Duration.ofMinutes(5));

    Organization updated =
        service.update(
            registered.organizationId(),
            new OrganizationChange(
                Optional.of("Acme Servicios S.A. de C.V."),
                Optional.empty(),
                Optional.empty(),
                Optional.empty()));

    assertThat(updated.organizationId()).isEqualTo(registered.organizationId());
    assertThat(updated.legalName()).isEqualTo("Acme Servicios S.A. de C.V.");
    assertThat(ledger.accounts()).containsExactlyInAnyOrderElementsOf(afterRegistration);
    assertThat(ledger.calls()).hasSize(callsAfterRegistration);
  }

  @Test
  @DisplayName("an update appends a revision; the superseded one stays readable")
  void updateAppendsRevision() {
    Organization registered =
        service.register(command(FreshTaxIdentifiers.fresh(), "Acme")).organization();
    clock.advance(Duration.ofMinutes(1));

    service.update(
        registered.organizationId(),
        new OrganizationChange(
            Optional.of("Acme Servicios"), Optional.empty(), Optional.empty(), Optional.empty()));

    List<OrganizationRevision> revisions = service.revisions(registered.organizationId());
    assertThat(revisions).extracting(OrganizationRevision::revision).containsExactly(1, 2);
    assertThat(revisions)
        .extracting(OrganizationRevision::legalName)
        .containsExactly("Acme", "Acme Servicios");
  }

  @Test
  @DisplayName("an update that changes nothing does not manufacture a revision")
  void emptyUpdateIsANoOp() {
    Organization registered =
        service.register(command(FreshTaxIdentifiers.fresh(), "Acme")).organization();

    Organization unchanged =
        service.update(
            registered.organizationId(),
            new OrganizationChange(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()));

    assertThat(unchanged.revision()).isEqualTo(1);
    assertThat(service.revisions(registered.organizationId())).hasSize(1);
  }

  @Test
  @DisplayName("an organization can be looked up by id, and an unknown id is rejected")
  void lookupById() {
    Organization registered =
        service.register(command(FreshTaxIdentifiers.fresh(), "Acme")).organization();

    assertThat(service.get(registered.organizationId())).isEqualTo(registered);
    assertThatThrownBy(() -> service.get(new OrganizationId(UUID.randomUUID())))
        .isInstanceOf(OrganizationNotFoundException.class);
  }

  @Test
  @DisplayName("a registration interrupted before provisioning converges on retry")
  void retryAfterAnInterruptedRegistrationProvisionsTheAccounts() {
    TaxIdentifier taxId = FreshTaxIdentifiers.fresh();
    OrganizationId organizationId = OrganizationId.derive(ORGANIZATION_NAMESPACE, taxId);
    // The state a crash between the two writes leaves behind: the row exists,
    // the ledger accounts do not (ADR-0011 — the row is written first).
    Instant now = clock.instant();
    repository.insertIfAbsent(
        new Organization(
            organizationId,
            taxId,
            "Acme",
            Optional.empty(),
            Optional.empty(),
            OrganizationStatus.ACTIVE,
            1,
            now,
            now));
    assertThat(ledger.accounts()).isEmpty();

    service.register(command(taxId, "Acme"));

    assertThat(ledger.accounts())
        .contains(
            accountIds.derive(
                LedgerCodes.AccountCode.EMPLOYER_COLLECTIONS, organizationId.scope()));
  }

  @Test
  @DisplayName("the tax identifier appears in no log produced by a full registration")
  void registrationLogsNoTaxIdentifier() {
    String rfc = FreshTaxIdentifiers.freshRfc();
    TaxIdentifier taxId = TaxIdentifier.of(TaxIdScheme.MX_RFC, rfc);

    Organization registered = service.register(command(taxId, "Acme")).organization();
    service.update(
        registered.organizationId(),
        new OrganizationChange(
            Optional.of("Acme Servicios"), Optional.empty(), Optional.empty(), Optional.empty()));

    assertThat(logs.list).isNotEmpty();
    for (ILoggingEvent event : logs.list) {
      assertThat(event.getFormattedMessage())
          .as("formatted log line must not carry the RFC")
          .doesNotContain(rfc)
          .doesNotContain(rfc.toLowerCase(java.util.Locale.ROOT));
      // Structured sinks render the raw arguments, not only the formatted line.
      if (event.getArgumentArray() != null) {
        for (Object argument : event.getArgumentArray()) {
          assertThat(String.valueOf(argument)).doesNotContain(rfc);
        }
      }
    }
  }

  private static RegisterOrganizationCommand command(TaxIdentifier taxId, String legalName) {
    return new RegisterOrganizationCommand(
        taxId, legalName, Optional.of("CT-2026-0001"), Optional.of("BRACKET_A"));
  }

  private static ch.qos.logback.classic.Logger rootLogger() {
    return (ch.qos.logback.classic.Logger)
        LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
  }

  /** A clock a test can move, so "updated later" is a fact rather than a race. */
  private static final class MutableClock extends Clock {

    private Instant now;

    private MutableClock(Instant now) {
      this.now = now;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public java.time.ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }
}
