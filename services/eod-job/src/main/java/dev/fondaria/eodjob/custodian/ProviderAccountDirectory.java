package dev.fondaria.eodjob.custodian;

import java.time.LocalDate;
import java.util.List;

/** Every {@link ProviderAccount} the custodian pass must reconcile for a business date. */
public interface ProviderAccountDirectory {

  /**
   * One row per distinct scope key (fund, and organization for {@code ORGANIZATION} scope) — the
   * version resolved as of {@code businessDate}, never a history of every version ever published.
   */
  List<ProviderAccount> activeAsOf(LocalDate businessDate);
}
