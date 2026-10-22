package dev.fondaria.balanceapi.movement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.fondaria.common.FundId;
import dev.fondaria.common.ParticipantId;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * "Tampered cursor → 400" is one of Task 6.3's acceptance criteria, and a cursor has more ways to
 * be tampered with than one. Each of them is checked here.
 */
class CursorCodecTest {

  private static final String SECRET = "a-test-signing-key-of-adequate-length";
  private static final ParticipantId PARTICIPANT = ParticipantId.of(UUID.randomUUID());

  private final CursorCodec codec = new CursorCodec(SECRET);

  private static MovementQuery query() {
    return new MovementQuery(
        PARTICIPANT,
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        50,
        Optional.empty());
  }

  private static MovementCursor position() {
    return new MovementCursor(
        new BigInteger("1735689600123456789"),
        new BigInteger("193428719834719834719834719834"),
        MovementDirection.CREDIT.leg());
  }

  @Test
  @DisplayName("a cursor round-trips exactly, including a u128 no long could hold")
  void roundTrips() {
    MovementCursor cursor = position();
    assertThat(codec.decode(codec.encode(query(), cursor), query())).isEqualTo(cursor);
  }

  @Test
  @DisplayName("both legs of one transfer produce distinct cursors")
  void theLegIsPartOfThePosition() {
    MovementCursor debit =
        new MovementCursor(BigInteger.TEN, BigInteger.ONE, MovementDirection.DEBIT.leg());
    MovementCursor credit =
        new MovementCursor(BigInteger.TEN, BigInteger.ONE, MovementDirection.CREDIT.leg());

    assertThat(codec.encode(query(), debit)).isNotEqualTo(codec.encode(query(), credit));
    assertThat(codec.decode(codec.encode(query(), credit), query())).isEqualTo(credit);
  }

  @Test
  @DisplayName("an edited position is refused, not obeyed")
  void anEditedPositionIsRefused() {
    String token = codec.encode(query(), position());
    String signature = token.substring(token.indexOf('.'));
    String forged =
        Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString("110".getBytes(StandardCharsets.UTF_8))
            + signature;

    assertThatThrownBy(() -> codec.decode(forged, query()))
        .isInstanceOf(TamperedCursorException.class);
  }

  /**
   * The edited character is the first of the signature, not the last: a 16-byte MAC is 22 base64
   * characters and the last one carries only two significant bits, so editing it can decode to the
   * same bytes and prove nothing.
   */
  @Test
  @DisplayName("an edited signature is refused")
  void anEditedSignatureIsRefused() {
    String token = codec.encode(query(), position());
    int signature = token.indexOf('.') + 1;
    String mangled =
        token.substring(0, signature)
            + (token.charAt(signature) == 'A' ? 'B' : 'A')
            + token.substring(signature + 1);

    assertThatThrownBy(() -> codec.decode(mangled, query()))
        .isInstanceOf(TamperedCursorException.class);
  }

  @Test
  @DisplayName("a truncated or unstructured token is refused rather than parsed")
  void garbageIsRefused() {
    assertThatThrownBy(() -> codec.decode("", query())).isInstanceOf(TamperedCursorException.class);
    assertThatThrownBy(() -> codec.decode("not-a-cursor", query()))
        .isInstanceOf(TamperedCursorException.class);
    assertThatThrownBy(() -> codec.decode("abc.", query()))
        .isInstanceOf(TamperedCursorException.class);
    assertThatThrownBy(() -> codec.decode("!!!.???", query()))
        .isInstanceOf(TamperedCursorException.class);
  }

  /**
   * The reason the MAC covers the query and not only the position: without it a caller's own cursor
   * would page happily through a different participant's history.
   */
  @Test
  @DisplayName("a cursor issued for one participant does not verify for another")
  void aCursorDoesNotTravelBetweenParticipants() {
    String token = codec.encode(query(), position());
    MovementQuery somebodyElse =
        new MovementQuery(
            ParticipantId.of(UUID.randomUUID()),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            50,
            Optional.empty());

    assertThatThrownBy(() -> codec.decode(token, somebodyElse))
        .isInstanceOf(TamperedCursorException.class);
  }

  @Test
  @DisplayName("a cursor issued under one set of filters does not verify under another")
  void aCursorDoesNotTravelBetweenFilters() {
    MovementQuery narrow =
        new MovementQuery(
            PARTICIPANT,
            Optional.of(LocalDate.of(2026, 8, 1)),
            Optional.of(LocalDate.of(2026, 8, 31)),
            Optional.of(FundId.of(UUID.randomUUID())),
            Optional.of(MovementTransferCode.VESTING),
            50,
            Optional.empty());
    String token = codec.encode(narrow, position());

    assertThatThrownBy(() -> codec.decode(token, query()))
        .isInstanceOf(TamperedCursorException.class);
  }

  /**
   * An absent {@code from} with a present {@code to} must not fingerprint as its own mirror image.
   * Concatenating the fields without a separator would make exactly that pair collide.
   */
  @Test
  @DisplayName("swapping which date bound is present invalidates the cursor")
  void theFingerprintSeparatesItsFields() {
    LocalDate date = LocalDate.of(2026, 8, 1);
    MovementQuery onlyFrom =
        new MovementQuery(
            PARTICIPANT,
            Optional.of(date),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            50,
            Optional.empty());
    MovementQuery onlyTo =
        new MovementQuery(
            PARTICIPANT,
            Optional.empty(),
            Optional.of(date),
            Optional.empty(),
            Optional.empty(),
            50,
            Optional.empty());

    assertThatThrownBy(() -> codec.decode(codec.encode(onlyFrom, position()), onlyTo))
        .isInstanceOf(TamperedCursorException.class);
  }

  /** Page size is deliberately not bound: resizing mid-pagination is harmless under keyset. */
  @Test
  @DisplayName("changing the page size keeps a cursor valid")
  void theLimitIsNotBound() {
    MovementQuery resized =
        new MovementQuery(
            PARTICIPANT,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            200,
            Optional.empty());

    assertThat(codec.decode(codec.encode(query(), position()), resized)).isEqualTo(position());
  }

  @Test
  @DisplayName("a cursor signed with a different secret is refused")
  void rotatingTheSecretInvalidatesOutstandingCursors() {
    String token = codec.encode(query(), position());
    CursorCodec rotated = new CursorCodec("a-different-signing-key-of-adequate-length");

    assertThatThrownBy(() -> rotated.decode(token, query()))
        .isInstanceOf(TamperedCursorException.class);
  }
}
