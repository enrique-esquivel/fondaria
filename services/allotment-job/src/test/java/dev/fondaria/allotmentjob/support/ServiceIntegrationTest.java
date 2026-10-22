package dev.fondaria.allotmentjob.support;

import dev.fondaria.testing.PostgresFixture;
import dev.fondaria.testing.TigerBeetleFixture;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Points a Spring context at the shared Testcontainers fixtures, mirroring contribution-api's and
 * valuation-api's classes of the same name.
 *
 * <p>{@code LEDGER_NAMESPACE} is random per JVM, the same reason contribution-api's is: fixtures
 * are reused across local runs and TigerBeetle cannot be reset (AGENTS.md §4). This service never
 * registers an organization, so unlike contribution-api's version there is no {@code
 * ORGANIZATION_NAMESPACE} here — a test that needs an organization row derives its id with its own
 * namespace and writes {@code master.organization} directly, since organization registration is
 * contribution-api's endpoint, not this service's.
 */
@Tag("integration")
public abstract class ServiceIntegrationTest {

  protected static final UUID LEDGER_NAMESPACE = UUID.randomUUID();

  @DynamicPropertySource
  static void serviceProperties(DynamicPropertyRegistry registry) {
    PostgresFixture postgres = PostgresFixture.getInstance();
    registry.add("fondaria.database.url", postgres::getJdbcUrl);
    registry.add("fondaria.database.username", postgres::getUsername);
    registry.add("fondaria.database.password", postgres::getPassword);

    TigerBeetleFixture tigerBeetle = TigerBeetleFixture.getInstance();
    registry.add("fondaria.ledger.addresses", tigerBeetle::getAddresses);
    registry.add("fondaria.ledger.client.cluster-id", () -> 0L);

    // An hour, so the scheduled sweep never fires during a test — every test drives the
    // runner explicitly, the same reason contribution-api's base class does this.
    registry.add("fondaria.allotment.poll-interval", () -> "1h");
    // Fixtures are reused across runs (AGENTS.md §4): workflow.contribution_batch still
    // holds whatever earlier runs left STAGED. Claiming the leftovers too is harmless --
    // every assertion in this suite is over an id it created itself -- but the production
    // batch size could crowd a test's own batch out of its own sweep.
    registry.add("fondaria.allotment.batch-size", () -> 1_000_000);

    registry.add("fondaria.identity.ledger-namespace", () -> LEDGER_NAMESPACE);
  }
}
