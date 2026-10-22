package dev.fondaria.ledger;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.fondaria.common.CurrencyCode;
import dev.fondaria.common.Money;
import dev.fondaria.common.Units;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Decodes a JSON message from {@code ledger.transfers.v1} or {@code ledger.entries.v1} into a
 * typed, sealed domain record — {@link LedgerTransferEvent.Cash}/{@code .Unit} or {@link
 * LedgerEntryEvent.Cash}/{@code .Unit} — routing on the {@code ledger} id so a cash quantity
 * decodes to {@link Money} and a unit quantity to {@link Units}, and the two can never be confused.
 *
 * <p>Strict on purpose (phase-04-cdc.md Task 4.4):
 *
 * <ul>
 *   <li>{@code schemaVersion} is checked <b>first</b>. Anything but {@code "v1"} is an {@link
 *       UnknownLedgerEventVersionException} and <i>no other field is read</i> — an unknown version
 *       is never best-effort parsed.
 *   <li>Every quantity field is an unsigned base-10 integer. A fractional value — a JSON float, or
 *       a string containing a {@code .} — is a {@link MalformedLedgerEventException}, never rounded
 *       or truncated.
 *   <li>An amount or balance counter that does not fit a signed 64-bit {@code long} is a {@link
 *       MalformedLedgerEventException}. Our cash and unit quantities are {@code long}; a value that
 *       does not fit must reach a human, not be silently narrowed.
 *   <li>A {@code ledger} id that is neither {@code 484} (cash) nor {@code >= 1001} (a unit ledger)
 *       is a {@link MalformedLedgerEventException}.
 * </ul>
 *
 * <p>Both exception types are for the consumer to route to its dead-letter topic. This class is
 * stateless and thread-safe.
 */
public final class LedgerEventDecoder {

  /** The one schema version this decoder understands. */
  public static final String SCHEMA_VERSION = "v1";

  /**
   * The lowest per-fund unit ledger id. Cash is {@link LedgerCodes#CASH_LEDGER_ID}; unit ledgers
   * are assigned from {@code 1001} at fund creation (see {@link LedgerCodes}).
   */
  private static final long FIRST_UNIT_LEDGER_ID = 1001L;

  private static final Pattern UNSIGNED_BASE_10 = Pattern.compile("(0|[1-9][0-9]*)");

  private final ObjectMapper mapper;

  public LedgerEventDecoder() {
    this(new ObjectMapper());
  }

  public LedgerEventDecoder(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public LedgerTransferEvent decodeTransfer(String json) {
    return decodeTransfer(json.getBytes(StandardCharsets.UTF_8));
  }

  public LedgerTransferEvent decodeTransfer(byte[] json) {
    JsonNode root = readObject(json);
    requireVersion(root);

    ChangeEventType eventType = ChangeEventType.fromWire(text(root, "eventType"));
    long ledger = bounded(root, "ledger", 0L, 4_294_967_295L);
    String timestamp = uint(root, "timestamp");
    String transferId = uint(root, "transferId");
    Optional<String> pendingId = optionalId(uint(root, "pendingId"));
    int transferCode = (int) bounded(root, "transferCode", 0L, 65_535L);
    int transferFlags = (int) bounded(root, "transferFlags", 0L, 65_535L);
    String transferTimestamp = uint(root, "transferTimestamp");
    String userData128 = uint(root, "userData128");
    String userData64 = uint(root, "userData64");
    long userData32 = bounded(root, "userData32", 0L, 4_294_967_295L);
    String debitAccountId = uint(root, "debitAccountId");
    String creditAccountId = uint(root, "creditAccountId");
    int debitAccountCode = (int) bounded(root, "debitAccountCode", 0L, 65_535L);
    int creditAccountCode = (int) bounded(root, "creditAccountCode", 0L, 65_535L);
    long amount = quantity(root, "amount");

    if (ledger == LedgerCodes.CASH_LEDGER_ID) {
      return new LedgerTransferEvent.Cash(
          SCHEMA_VERSION,
          eventType,
          ledger,
          timestamp,
          transferId,
          pendingId,
          transferCode,
          transferFlags,
          transferTimestamp,
          userData128,
          userData64,
          userData32,
          debitAccountId,
          creditAccountId,
          debitAccountCode,
          creditAccountCode,
          Money.of(amount, CurrencyCode.MXN));
    }
    if (ledger >= FIRST_UNIT_LEDGER_ID) {
      return new LedgerTransferEvent.Unit(
          SCHEMA_VERSION,
          eventType,
          ledger,
          timestamp,
          transferId,
          pendingId,
          transferCode,
          transferFlags,
          transferTimestamp,
          userData128,
          userData64,
          userData32,
          debitAccountId,
          creditAccountId,
          debitAccountCode,
          creditAccountCode,
          ledger,
          Units.of(amount));
    }
    throw unrecognisedLedger(ledger);
  }

  public LedgerEntryEvent decodeEntry(String json) {
    return decodeEntry(json.getBytes(StandardCharsets.UTF_8));
  }

  public LedgerEntryEvent decodeEntry(byte[] json) {
    JsonNode root = readObject(json);
    requireVersion(root);

    ChangeEventType eventType = ChangeEventType.fromWire(text(root, "eventType"));
    long ledger = bounded(root, "ledger", 0L, 4_294_967_295L);
    String timestamp = uint(root, "timestamp");
    String transferId = uint(root, "transferId");
    int transferCode = (int) bounded(root, "transferCode", 0L, 65_535L);
    String transferTimestamp = uint(root, "transferTimestamp");
    EntryDirection direction = EntryDirection.fromWire(text(root, "direction"));
    String accountId = uint(root, "accountId");
    int accountCode = (int) bounded(root, "accountCode", 0L, 65_535L);
    int accountFlags = (int) bounded(root, "accountFlags", 0L, 65_535L);
    String accountUserData128 = uint(root, "accountUserData128");
    String accountUserData64 = uint(root, "accountUserData64");
    long accountUserData32 = bounded(root, "accountUserData32", 0L, 4_294_967_295L);
    Optional<String> pendingId = optionalId(uint(root, "pendingId"));

    long amount = quantity(root, "amount");
    long debitsPending = quantity(root, "debitsPending");
    long debitsPosted = quantity(root, "debitsPosted");
    long creditsPending = quantity(root, "creditsPending");
    long creditsPosted = quantity(root, "creditsPosted");

    if (ledger == LedgerCodes.CASH_LEDGER_ID) {
      return new LedgerEntryEvent.Cash(
          SCHEMA_VERSION,
          eventType,
          ledger,
          timestamp,
          transferId,
          transferCode,
          transferTimestamp,
          direction,
          accountId,
          accountCode,
          accountFlags,
          accountUserData128,
          accountUserData64,
          accountUserData32,
          pendingId,
          Money.of(amount, CurrencyCode.MXN),
          Money.of(debitsPending, CurrencyCode.MXN),
          Money.of(debitsPosted, CurrencyCode.MXN),
          Money.of(creditsPending, CurrencyCode.MXN),
          Money.of(creditsPosted, CurrencyCode.MXN));
    }
    if (ledger >= FIRST_UNIT_LEDGER_ID) {
      return new LedgerEntryEvent.Unit(
          SCHEMA_VERSION,
          eventType,
          ledger,
          timestamp,
          transferId,
          transferCode,
          transferTimestamp,
          direction,
          accountId,
          accountCode,
          accountFlags,
          accountUserData128,
          accountUserData64,
          accountUserData32,
          pendingId,
          ledger,
          Units.of(amount),
          Units.of(debitsPending),
          Units.of(debitsPosted),
          Units.of(creditsPending),
          Units.of(creditsPosted));
    }
    throw unrecognisedLedger(ledger);
  }

  private JsonNode readObject(byte[] json) {
    JsonNode root;
    try {
      root = mapper.readTree(json);
    } catch (JsonProcessingException e) {
      throw new MalformedLedgerEventException(
          "body is not valid JSON: " + e.getOriginalMessage(), e);
    } catch (IOException e) {
      throw new MalformedLedgerEventException("could not read message body", e);
    }
    if (root == null || !root.isObject()) {
      throw new MalformedLedgerEventException("body is not a JSON object");
    }
    return root;
  }

  private static void requireVersion(JsonNode root) {
    JsonNode node = root.get("schemaVersion");
    if (node == null || node.isNull() || !node.isTextual()) {
      throw new UnknownLedgerEventVersionException(null);
    }
    if (!SCHEMA_VERSION.equals(node.asText())) {
      throw new UnknownLedgerEventVersionException(node.asText());
    }
  }

  private static String text(JsonNode parent, String field) {
    JsonNode node = parent.get(field);
    if (node == null || !node.isTextual() || node.asText().isBlank()) {
      throw new MalformedLedgerEventException("missing or non-string field: " + field);
    }
    return node.asText();
  }

  /** The canonical base-10 form of an unsigned integer field, accepting a JSON number or string. */
  private static String uint(JsonNode parent, String field) {
    return unsignedValue(parent, field).toString();
  }

  /**
   * An unsigned integer field that must fit a signed 64-bit {@code long} — a transfer amount or a
   * balance counter. A value outside {@code [0, Long.MAX_VALUE]} is a decode error, never
   * truncated.
   */
  private static long quantity(JsonNode parent, String field) {
    BigInteger value = unsignedValue(parent, field);
    if (value.bitLength() > 63) {
      throw new MalformedLedgerEventException(
          "field " + field + " does not fit a signed 64-bit range: " + value);
    }
    return value.longValue();
  }

  /** A small unsigned integer field ({@code ledger}, a code, a flag word), range-checked. */
  private static long bounded(JsonNode parent, String field, long min, long max) {
    BigInteger value = unsignedValue(parent, field);
    if (value.compareTo(BigInteger.valueOf(min)) < 0
        || value.compareTo(BigInteger.valueOf(max)) > 0) {
      throw new MalformedLedgerEventException(
          "field " + field + " is outside [" + min + ", " + max + "]: " + value);
    }
    return value.longValue();
  }

  private static BigInteger unsignedValue(JsonNode parent, String field) {
    JsonNode node = parent.get(field);
    if (node == null || node.isNull()) {
      throw new MalformedLedgerEventException("missing field: " + field);
    }
    BigInteger value;
    if (node.isIntegralNumber()) {
      value = node.bigIntegerValue();
    } else if (node.isFloatingPointNumber()) {
      throw new MalformedLedgerEventException(
          "field " + field + " has a fractional value: " + node.asText());
    } else if (node.isTextual()) {
      String raw = node.asText().trim();
      if (!UNSIGNED_BASE_10.matcher(raw).matches()) {
        throw new MalformedLedgerEventException(
            "field " + field + " is not an unsigned base-10 integer: " + node.asText());
      }
      value = new BigInteger(raw);
    } else {
      throw new MalformedLedgerEventException(
          "field " + field + " is not a number or numeric string");
    }
    if (value.signum() < 0) {
      throw new MalformedLedgerEventException("field " + field + " is negative: " + value);
    }
    return value;
  }

  /** A {@code "0"} id means "none" (no pending transfer). */
  private static Optional<String> optionalId(String canonicalId) {
    return "0".equals(canonicalId) ? Optional.empty() : Optional.of(canonicalId);
  }

  private static MalformedLedgerEventException unrecognisedLedger(long ledger) {
    return new MalformedLedgerEventException(
        "unrecognised ledger id: "
            + ledger
            + " (cash is "
            + LedgerCodes.CASH_LEDGER_ID
            + ", unit ledgers are >= "
            + FIRST_UNIT_LEDGER_ID
            + ")");
  }
}
