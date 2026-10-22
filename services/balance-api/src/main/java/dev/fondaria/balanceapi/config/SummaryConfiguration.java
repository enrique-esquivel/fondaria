package dev.fondaria.balanceapi.config;

import dev.fondaria.balanceapi.summary.ContributionTotalRepository;
import dev.fondaria.balanceapi.summary.OrganizationRoster;
import dev.fondaria.balanceapi.summary.OrganizationSummaryService;
import dev.fondaria.ledger.AccountIdDeriver;
import dev.fondaria.ledger.LedgerPort;
import dev.fondaria.ledger.TenantRefDeriver;
import dev.fondaria.valuation.NavCache;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the organization summary (Task 6.5). Constructor injection only (AGENTS.md §4).
 *
 * <p>No environment variable of its own. It reuses the ledger, the NAV cache, the database pool and
 * its {@code POSTGRES_QUERY_TIMEOUT}, the identity namespace behind {@code AccountIdDeriver}, and
 * the {@code TenantRefDeriver} bean {@code MovementConfiguration} defines — shared for the same
 * reason Task 6.4 shares it: two beans built from two readings of one property is how two endpoints
 * start deriving different {@code tenant_id}s for one organization.
 *
 * <p>The one property it reads is {@code CALENDAR_ZONE}, which already exists and is already
 * documented; it decides which calendar month "this period" is. See {@link CalendarProperties}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CalendarProperties.class)
public class SummaryConfiguration {

  @Bean
  public OrganizationSummaryService organizationSummaryService(
      OrganizationRoster roster,
      LedgerPort ledger,
      NavCache navCache,
      ContributionTotalRepository contributions,
      AccountIdDeriver accountIds,
      TenantRefDeriver tenantRefs,
      Clock clock,
      CalendarProperties calendar) {
    return new OrganizationSummaryService(
        roster,
        ledger,
        navCache,
        contributions,
        accountIds,
        tenantRefs,
        clock,
        calendar.requireZone());
  }
}
