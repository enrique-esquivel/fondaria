package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class TransferQueryTest {

  @Test
  void firstPageHasNoCursorAndIsMostRecentFirst() {
    TransferQuery query = TransferQuery.firstPage(50);
    assertThat(query.limit()).isEqualTo(50);
    assertThat(query.after()).isEmpty();
    assertThat(query.direction()).isEqualTo(TransferQuery.SortDirection.DESCENDING);
  }

  @Test
  void afterCarriesTheGivenCursorAndDirection() {
    TransferId cursor = TransferId.of(1L, 2L);
    TransferQuery query = TransferQuery.after(cursor, 25, TransferQuery.SortDirection.ASCENDING);
    assertThat(query.after()).contains(cursor);
    assertThat(query.limit()).isEqualTo(25);
    assertThat(query.direction()).isEqualTo(TransferQuery.SortDirection.ASCENDING);
  }

  @Test
  void rejectsANonPositiveLimit() {
    assertThatThrownBy(
            () -> new TransferQuery(0, Optional.empty(), TransferQuery.SortDirection.DESCENDING))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit");
  }
}
