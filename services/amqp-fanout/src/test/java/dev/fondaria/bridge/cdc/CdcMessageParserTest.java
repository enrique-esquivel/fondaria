package dev.fondaria.bridge.cdc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * The parser against the exact JSON shape {@code tigerbeetle amqp} 0.17.4 emits — a numeric form
 * (values that fit a double) and a string form (values that do not), taken from the connector's own
 * {@code "amqp: JSON message"} unit test in {@code src/cdc/runner.zig}.
 */
class CdcMessageParserTest {

  private final CdcMessageParser parser = new CdcMessageParser(new ObjectMapper());

  // Values-as-numbers form, straight from the pinned connector's own test vector.
  private static final String NUMERIC_FORM =
      "{\"timestamp\":0,\"type\":\"single_phase\",\"ledger\":0,"
          + "\"transfer\":{\"id\":0,\"amount\":0,\"pending_id\":0,\"user_data_128\":0,"
          + "\"user_data_64\":0,\"user_data_32\":0,\"timeout\":0,\"code\":0,\"flags\":0,"
          + "\"timestamp\":0},"
          + "\"debit_account\":{\"id\":0,\"debits_pending\":0,\"debits_posted\":0,"
          + "\"credits_pending\":0,\"credits_posted\":0,\"user_data_128\":0,\"user_data_64\":0,"
          + "\"user_data_32\":0,\"code\":0,\"flags\":0,\"timestamp\":0},"
          + "\"credit_account\":{\"id\":0,\"debits_pending\":0,\"debits_posted\":0,"
          + "\"credits_pending\":0,\"credits_posted\":0,\"user_data_128\":0,\"user_data_64\":0,"
          + "\"user_data_32\":0,\"code\":0,\"flags\":0,\"timestamp\":0}}";

  // Values-as-strings form (u128 maxima), same connector test vector, "two_phase_pending".
  private static final String STRING_FORM =
      "{\"timestamp\":\"18446744073709551615\",\"type\":\"two_phase_pending\",\"ledger\":484,"
          + "\"transfer\":{\"id\":\"340282366920938463463374607431768211455\",\"amount\":\"1000\","
          + "\"pending_id\":\"340282366920938463463374607431768211455\","
          + "\"user_data_128\":\"340282366920938463463374607431768211455\","
          + "\"user_data_64\":\"18446744073709551615\",\"user_data_32\":4294967295,"
          + "\"timeout\":4294967295,\"code\":65535,\"flags\":65535,"
          + "\"timestamp\":\"18446744073709551615\"},"
          + "\"debit_account\":{\"id\":\"111111111111111111111111111111\","
          + "\"debits_pending\":\"0\",\"debits_posted\":\"1000\",\"credits_pending\":\"0\","
          + "\"credits_posted\":\"0\",\"user_data_128\":\"0\",\"user_data_64\":\"0\","
          + "\"user_data_32\":0,\"code\":100,\"flags\":0,\"timestamp\":\"1\"},"
          + "\"credit_account\":{\"id\":\"222222222222222222222222222222\","
          + "\"debits_pending\":\"0\",\"debits_posted\":\"0\",\"credits_pending\":\"0\","
          + "\"credits_posted\":\"1000\",\"user_data_128\":\"0\",\"user_data_64\":\"0\","
          + "\"user_data_32\":0,\"code\":101,\"flags\":0,\"timestamp\":\"2\"}}";

  @Test
  void parsesTheNumericForm() {
    CdcMessage message = parser.parse(NUMERIC_FORM.getBytes(StandardCharsets.UTF_8));

    assertThat(message.type()).isEqualTo(ChangeEventType.SINGLE_PHASE);
    assertThat(message.ledger()).isZero();
    assertThat(message.transfer().id()).isEqualTo("0");
    assertThat(message.transfer().amount()).isEqualTo("0");
    assertThat(message.debitAccount().id()).isEqualTo("0");
    assertThat(message.creditAccount().id()).isEqualTo("0");
  }

  @Test
  void parsesTheStringFormWithoutLosingPrecision() {
    CdcMessage message = parser.parse(STRING_FORM.getBytes(StandardCharsets.UTF_8));

    assertThat(message.type()).isEqualTo(ChangeEventType.TWO_PHASE_PENDING);
    assertThat(message.ledger()).isEqualTo(484);
    assertThat(message.transfer().id()).isEqualTo("340282366920938463463374607431768211455");
    assertThat(message.transfer().amount()).isEqualTo("1000");
    assertThat(message.transfer().code()).isEqualTo(65535);
    assertThat(message.debitAccount().id()).isEqualTo("111111111111111111111111111111");
    assertThat(message.debitAccount().code()).isEqualTo(100);
    assertThat(message.debitAccount().debitsPosted()).isEqualTo("1000");
    assertThat(message.creditAccount().id()).isEqualTo("222222222222222222222222222222");
    assertThat(message.creditAccount().code()).isEqualTo(101);
    assertThat(message.creditAccount().creditsPosted()).isEqualTo("1000");
  }

  @Test
  void rejectsMalformedJson() {
    assertThatThrownBy(() -> parser.parse("{not json".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(CdcMessageException.class);
  }

  @Test
  void rejectsAMissingField() {
    String missingCreditAccount =
        NUMERIC_FORM.substring(0, NUMERIC_FORM.lastIndexOf(",\"credit_account\"")) + "}";
    assertThatThrownBy(() -> parser.parse(missingCreditAccount.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(CdcMessageException.class)
        .hasMessageContaining("credit_account");
  }

  @Test
  void rejectsAnUnknownEventType() {
    String unknownType = NUMERIC_FORM.replace("\"single_phase\"", "\"triple_phase\"");
    assertThatThrownBy(() -> parser.parse(unknownType.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(CdcMessageException.class)
        .hasMessageContaining("triple_phase");
  }

  @Test
  void rejectsAFractionalAmount() {
    String fractional = STRING_FORM.replace("\"amount\":\"1000\"", "\"amount\":10.5");
    assertThatThrownBy(() -> parser.parse(fractional.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(CdcMessageException.class)
        .hasMessageContaining("amount");
  }

  @Test
  void rejectsAFractionalAmountInStringForm() {
    String fractional = STRING_FORM.replace("\"amount\":\"1000\"", "\"amount\":\"10.5\"");
    assertThatThrownBy(() -> parser.parse(fractional.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(CdcMessageException.class)
        .hasMessageContaining("amount");
  }

  @Test
  void rejectsANegativeValue() {
    String negative = STRING_FORM.replace("\"amount\":\"1000\"", "\"amount\":-1");
    assertThatThrownBy(() -> parser.parse(negative.getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(CdcMessageException.class);
  }
}
