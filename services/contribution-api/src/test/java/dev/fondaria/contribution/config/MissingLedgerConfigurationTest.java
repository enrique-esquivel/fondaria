package dev.fondaria.contribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import dev.fondaria.contribution.ContributionApiApplication;
import dev.fondaria.testing.PostgresFixture;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.core.NestedExceptionUtils;

/**
 * Which values are rejected is covered by {@code LedgerHealthIndicatorTest}. What this adds is the
 * one thing a unit test cannot show: the rejection actually escapes Spring's startup and stops the
 * service, rather than being swallowed into a context that comes up healthy-looking.
 *
 * <p>Every <i>other</i> required variable is supplied, so the failure under test is unambiguous:
 * the context must stop on the absent ledger addresses, not on a database or a cache that was never
 * configured either. A new required variable therefore has to be added to the list below, or this
 * test starts asserting the wrong absence.
 */
@Tag("integration")
class MissingLedgerConfigurationTest {

  @Test
  @DisplayName("an absent TIGERBEETLE_ADDRESSES stops startup, naming the variable")
  void absentAddressesStopsStartup() {
    Throwable thrown =
        catchThrowable(
            () ->
                new SpringApplicationBuilder(ContributionApiApplication.class)
                    .web(WebApplicationType.NONE)
                    // Args, not properties(...): defaults rank below application.yaml.
                    .run(
                        "--fondaria.ledger.addresses=",
                        "--fondaria.ledger.client.cluster-id=0",
                        "--fondaria.database.url=" + PostgresFixture.getInstance().getJdbcUrl(),
                        "--fondaria.database.username="
                            + PostgresFixture.getInstance().getUsername(),
                        "--fondaria.database.password="
                            + PostgresFixture.getInstance().getPassword(),
                        "--fondaria.identity.organization-namespace=" + UUID.randomUUID(),
                        "--fondaria.identity.ledger-namespace=" + UUID.randomUUID(),
                        // Required since Task 3.3, and supplied for the reason above: the
                        // failure under test must be the ledger's. Nothing ever connects to
                        // it — the Redis connection is lazy by design, so that a cache being
                        // down cannot stop this service from starting.
                        "--fondaria.cache.url=redis://localhost:6379",
                        "--fondaria.cache.password=unused",
                        // Required since Task 3.4, and supplied for the same reason.
                        // Presigning is offline and nothing here fetches an object, so
                        // the endpoint never has to resolve.
                        "--fondaria.object-store.endpoint=http://localhost:9000",
                        "--fondaria.object-store.access-key=unused",
                        "--fondaria.object-store.secret-key=unused",
                        "--fondaria.object-store.bucket=unused")
                    .close());

    Throwable root = NestedExceptionUtils.getMostSpecificCause(thrown);
    assertThat(root).isInstanceOf(ConfigurationException.class);
    assertThat(root.getMessage()).contains("TIGERBEETLE_ADDRESSES").contains("is not set");
    assertThat(((ConfigurationException) root).remedy()).contains("host:port");
  }
}
