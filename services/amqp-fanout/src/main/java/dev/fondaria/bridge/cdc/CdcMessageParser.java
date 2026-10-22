package dev.fondaria.bridge.cdc;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigInteger;
import org.springframework.stereotype.Component;

/**
 * Parses the JSON body of a {@code bridge.ledger} message into a {@link CdcMessage}.
 *
 * <p>Strict on purpose. Anything that is not exactly a well-formed change event — bad JSON, a
 * missing field, a field of the wrong JSON type, a non-integral or negative numeric field, an
 * unknown {@code type} — is a {@link CdcMessageException}, which the listener routes to the DLQ.
 * There is no partial parse and no default value: a change event the bridge only half-understands
 * must reach a human, not a projection.
 *
 * <p>Numeric fields arrive as either a JSON number (small values) or a JSON string (values a double
 * cannot hold exactly); {@link #integral} accepts both and rejects a fractional value in either
 * form.
 */
@Component
public class CdcMessageParser {

  private final ObjectMapper mapper;

  public CdcMessageParser(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public CdcMessage parse(byte[] body) {
    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (JsonProcessingException e) {
      throw new CdcMessageException("body is not valid JSON: " + e.getOriginalMessage(), e);
    } catch (java.io.IOException e) {
      throw new CdcMessageException("could not read message body", e);
    }
    if (root == null || !root.isObject()) {
      throw new CdcMessageException("body is not a JSON object");
    }

    return new CdcMessage(
        integral(root, "timestamp"),
        ChangeEventType.fromWire(text(root, "type")),
        Long.parseLong(integral(root, "ledger")),
        transfer(require(root, "transfer")),
        account(require(root, "debit_account")),
        account(require(root, "credit_account")));
  }

  private CdcMessage.Transfer transfer(JsonNode node) {
    return new CdcMessage.Transfer(
        integral(node, "id"),
        integral(node, "amount"),
        integral(node, "pending_id"),
        integral(node, "user_data_128"),
        integral(node, "user_data_64"),
        Long.parseLong(integral(node, "user_data_32")),
        Long.parseLong(integral(node, "timeout")),
        Integer.parseInt(integral(node, "code")),
        Integer.parseInt(integral(node, "flags")),
        integral(node, "timestamp"));
  }

  private CdcMessage.Account account(JsonNode node) {
    return new CdcMessage.Account(
        integral(node, "id"),
        integral(node, "debits_pending"),
        integral(node, "debits_posted"),
        integral(node, "credits_pending"),
        integral(node, "credits_posted"),
        integral(node, "user_data_128"),
        integral(node, "user_data_64"),
        Long.parseLong(integral(node, "user_data_32")),
        Integer.parseInt(integral(node, "code")),
        Integer.parseInt(integral(node, "flags")),
        integral(node, "timestamp"));
  }

  private static JsonNode require(JsonNode parent, String field) {
    JsonNode child = parent.get(field);
    if (child == null || child.isNull() || !child.isObject()) {
      throw new CdcMessageException("missing or non-object field: " + field);
    }
    return child;
  }

  private static String text(JsonNode parent, String field) {
    JsonNode child = parent.get(field);
    if (child == null || !child.isTextual() || child.asText().isBlank()) {
      throw new CdcMessageException("missing or non-string field: " + field);
    }
    return child.asText();
  }

  /**
   * Returns the field's value as a canonical, non-negative decimal string, accepting either a JSON
   * integer or a JSON string of digits. Rejects a floating-point number, a non-numeric string, and
   * a negative value — the change-event schema is unsigned integers throughout.
   */
  private static String integral(JsonNode parent, String field) {
    JsonNode child = parent.get(field);
    if (child == null || child.isNull()) {
      throw new CdcMessageException("missing field: " + field);
    }
    BigInteger value;
    if (child.isIntegralNumber()) {
      value = child.bigIntegerValue();
    } else if (child.isFloatingPointNumber()) {
      throw new CdcMessageException(
          "field " + field + " has a fractional value: " + child.asText());
    } else if (child.isTextual()) {
      try {
        value = new BigInteger(child.asText().trim());
      } catch (NumberFormatException e) {
        throw new CdcMessageException(
            "field " + field + " is not an integer: " + child.asText(), e);
      }
    } else {
      throw new CdcMessageException("field " + field + " is not a number");
    }
    if (value.signum() < 0) {
      throw new CdcMessageException("field " + field + " is negative: " + value);
    }
    return value.toString();
  }
}
