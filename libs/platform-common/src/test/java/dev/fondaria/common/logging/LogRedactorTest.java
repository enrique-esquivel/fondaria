package dev.fondaria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class LogRedactorTest {

  @Test
  @DisplayName("redacts credit card PAN (raw and formatted)")
  void redactsPan() {
    String rawVisa = "Payment processed for card 4111222233334444 successfully";
    String formattedVisa = "Card 4111-2222-3333-4444 was charged";
    String rawMasterCard = "Charging 5123456789012345";
    String rawAmex = "Charging Amex 378282246310005";

    assertThat(LogRedactor.redact(rawVisa))
        .doesNotContain("4111222233334444")
        .contains(LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(formattedVisa))
        .doesNotContain("4111-2222-3333-4444")
        .contains(LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(rawMasterCard))
        .doesNotContain("5123456789012345")
        .contains(LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(rawAmex))
        .doesNotContain("378282246310005")
        .contains(LogRedactor.REDACTED);
  }

  @Test
  @DisplayName("redacts IBAN and Mexican CLABE (both formatted, labeled, and standalone 18 digits)")
  void redactsIbanAndClabe() {
    String mexicanIban = "Beneficiary iban=MX1234567890123456789012 verified";
    String germanIban = "Target account iban:DE89370400440532013000 confirmed";
    String labeledClabe = "Account details: clabe=123456789012345678 status=active";
    String jsonClabe = "{\"clabe\": \"123456789012345678\"}";
    String standaloneClabe = "Disbursement to 012180015678901234 executed";

    assertThat(LogRedactor.redact(mexicanIban))
        .doesNotContain("MX1234567890123456789012")
        .contains("iban=" + LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(germanIban))
        .doesNotContain("DE89370400440532013000")
        .contains("iban:" + LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(labeledClabe))
        .doesNotContain("123456789012345678")
        .contains("clabe=" + LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(jsonClabe))
        .doesNotContain("123456789012345678")
        .contains("\"clabe\": \"" + LogRedactor.REDACTED + "\"");
    assertThat(LogRedactor.redact(standaloneClabe))
        .doesNotContain("012180015678901234")
        .contains(LogRedactor.REDACTED);
  }

  @Test
  @DisplayName("redacts email addresses")
  void redactsEmail() {
    String msg = "Sent notification to user.name+tag@fondaria.example.mx for confirmation";
    assertThat(LogRedactor.redact(msg))
        .doesNotContain("user.name+tag@fondaria.example.mx")
        .contains(LogRedactor.REDACTED);
  }

  @Test
  @DisplayName("redacts tokens, passwords, and Bearer authorization headers")
  void redactsTokens() {
    String jwt =
        "Token:"
            + " eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.doNotLeakThisSignature"
            + " validated";
    String bearer = "Authorization: Bearer secret-bearer-token-abc";
    String labeledSecret = "Connecting with password=SuperSecretPassword! and apiKey=xyz-12345";
    String tokens = "access_token=token-12345 and refresh_token=refresh-67890";

    assertThat(LogRedactor.redact(jwt))
        .doesNotContain("eyJhbGciOiJIUzI1Ni")
        .contains(LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(bearer))
        .doesNotContain("secret-bearer-token-abc")
        .contains("Bearer " + LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(labeledSecret))
        .doesNotContain("SuperSecretPassword!")
        .doesNotContain("xyz-12345")
        .contains("password=" + LogRedactor.REDACTED)
        .contains("apiKey=" + LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(tokens))
        .doesNotContain("token-12345")
        .doesNotContain("refresh-67890")
        .contains("access_token=" + LogRedactor.REDACTED)
        .contains("refresh_token=" + LogRedactor.REDACTED);
  }

  @Test
  @DisplayName("redacts idempotency keys")
  void redactsIdempotencyKey() {
    String kv = "Incoming request idempotency-key=idem-key-8888-abcd accepted";
    String json = "{\"idempotencyKey\": \"idem-key-9999-efgh\", \"status\": \"PENDING\"}";

    assertThat(LogRedactor.redact(kv))
        .doesNotContain("idem-key-8888-abcd")
        .contains("idempotency-key=" + LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(json))
        .doesNotContain("idem-key-9999-efgh")
        .contains("\"idempotencyKey\": \"" + LogRedactor.REDACTED + "\"");
  }

  @Test
  @DisplayName("redacts Mexican tax identifiers (RFC and CURP) case-insensitively")
  void redactsRfcAndCurp() {
    String rfcMsg = "Registering taxpayer rfc=XAXX010101000";
    String lowerRfc = "Found taxpayer rfc:xaxx010101000 in database";
    String curpMsg = "Participant curp=ABCD900101HDFRRN01 verified";
    String lowerCurp = "Lookup for abcd900101hdfrrn01 complete";

    assertThat(LogRedactor.redact(rfcMsg))
        .doesNotContain("XAXX010101000")
        .contains("rfc=" + LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(lowerRfc))
        .doesNotContain("xaxx010101000")
        .contains("rfc:" + LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(curpMsg))
        .doesNotContain("ABCD900101HDFRRN01")
        .contains(LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(lowerCurp))
        .doesNotContain("abcd900101hdfrrn01")
        .contains(LogRedactor.REDACTED);
  }

  @Test
  @DisplayName("redacts date of birth in various labeled forms (Task 9.5)")
  void redactsDateOfBirth() {
    String camel = "Identity captured dateOfBirth=\"1990-05-14\" for participant";
    String snake = "Rejected identity capture: date_of_birth=1990-05-14 malformed";
    String spanish = "Captura de identidad fecha_nacimiento=1990-05-14 rechazada";

    assertThat(LogRedactor.redact(camel))
        .doesNotContain("1990-05-14")
        .contains("dateOfBirth=\"" + LogRedactor.REDACTED + "\"");
    assertThat(LogRedactor.redact(snake))
        .doesNotContain("1990-05-14")
        .contains("date_of_birth=" + LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(spanish))
        .doesNotContain("1990-05-14")
        .contains(LogRedactor.REDACTED);
  }

  @Test
  @DisplayName("does not over-redact technical RFC standards such as RFC 9457")
  void doesNotRedactRfcStandards() {
    String msg = "Returning RFC 9457 problem detail to client";
    assertThat(LogRedactor.redact(msg)).isEqualTo(msg);
  }

  @Test
  @DisplayName("redacts participant and legal names in various styles")
  void redactsNames() {
    String firstLast = "Enrollment: first_name=\"Juan\" last_name=\"Perez\"";
    String camel = "Participant fullName=\"Maria Gonzalez\" enrolled";
    String json = "{\"legalName\": \"Acme Mexico S.A.\"}";

    assertThat(LogRedactor.redact(firstLast))
        .doesNotContain("Juan")
        .doesNotContain("Perez")
        .contains("first_name=\"" + LogRedactor.REDACTED + "\"")
        .contains("last_name=\"" + LogRedactor.REDACTED + "\"");
    assertThat(LogRedactor.redact(camel))
        .doesNotContain("Maria Gonzalez")
        .contains("fullName=\"" + LogRedactor.REDACTED + "\"");
    assertThat(LogRedactor.redact(json))
        .doesNotContain("Acme Mexico S.A.")
        .contains("\"legalName\": \"" + LogRedactor.REDACTED + "\"");
  }

  @Test
  @DisplayName("redacts salary and balances")
  void redactsSalaryAndBalances() {
    String msg = "Calculated salary=75000.50 and balance=125000 for participant";
    String compound = "Current baseSalary=60000 and currentBalance=120000";

    assertThat(LogRedactor.redact(msg))
        .doesNotContain("75000.50")
        .doesNotContain("125000")
        .contains("salary=" + LogRedactor.REDACTED)
        .contains("balance=" + LogRedactor.REDACTED);
    assertThat(LogRedactor.redact(compound))
        .doesNotContain("60000")
        .doesNotContain("120000")
        .contains("baseSalary=" + LogRedactor.REDACTED)
        .contains("currentBalance=" + LogRedactor.REDACTED);
  }

  @Test
  @DisplayName("redacts document contents including multi-word strings")
  void redactsDocumentContents() {
    String json = "{\"documentContent\": \"Confidential terms of agreement and payout terms\"}";
    assertThat(LogRedactor.redact(json))
        .doesNotContain("Confidential terms of agreement")
        .contains("\"documentContent\": \"" + LogRedactor.REDACTED + "\"");
  }

  @Test
  @DisplayName(
      "Task 11.5 acceptance criterion: payload containing PAN, IBAN, email, token, and idempotency key asserts NONE appear in output")
  void acceptanceCriterionCombinedPayloadTest() {
    String pan = "4111222233334444";
    String iban = "MX1234567890123456789012";
    String email = "participant@company.com";
    String token = "jwt-secret-token-xyz123";
    String idempotencyKey = "idem-receipt-2026-09-08-001";

    String payload =
        String.format(
            "Payload: {\"pan\": \"%s\", \"iban\": \"%s\", \"email\": \"%s\", \"token\": \"%s\", \"idempotencyKey\": \"%s\"}",
            pan, iban, email, token, idempotencyKey);

    String redacted = LogRedactor.redact(payload);

    assertThat(redacted)
        .as("No sensitive fields must appear in redacted log output")
        .doesNotContain(pan)
        .doesNotContain(iban)
        .doesNotContain(email)
        .doesNotContain(token)
        .doesNotContain(idempotencyKey);
  }

  @Test
  @DisplayName(
      "preserves operational identifiers transfer_id, correlation_id, account_id, tenant_id (snake_case and camelCase)")
  void preservesOperationalIdentifiers() {
    String msg =
        "Transfer executed transfer_id=123456789012345678 correlation_id=trace-999-xyz"
            + " account_id=456789 tenant_id=org-uuid-1";
    String camelMsg = "Transfer transferId=4111222233334444 accountId=012180015678901234";

    String redacted = LogRedactor.redact(msg);
    String camelRedacted = LogRedactor.redact(camelMsg);

    assertThat(redacted)
        .contains("transfer_id=123456789012345678")
        .contains("correlation_id=trace-999-xyz")
        .contains("account_id=456789")
        .contains("tenant_id=org-uuid-1");

    assertThat(camelRedacted)
        .as("Operational IDs in camelCase must not be scrubbed as PAN or CLABE")
        .contains("transferId=4111222233334444")
        .contains("accountId=012180015678901234");
  }

  @Test
  @DisplayName(
      "Task 11F.2: operational identifiers (trace/span ids, image digests, queue names, run/job ids, holdings) pass through unchanged")
  void preservesOperationalLinesUnchanged() {
    String line1 = "trace ab12f3577b34da6a3ce929d0e0e47360 span cd34929d0e0e4736 linked";
    String line2 = "image otel/opentelemetry-collector-contrib@sha256:ae43fb1a1b1442628abf";
    String line3 = "declared queue name=projection.entries.v1.3 on exchange ledger.entries.v1";
    String line4 = "run 34427775300 job abc123456def failed";
    String line5 = "holding 123456789012345678 in fund 1001";

    assertThat(LogRedactor.redact(line1)).isEqualTo(line1);
    assertThat(LogRedactor.redact(line2)).isEqualTo(line2);
    assertThat(LogRedactor.redact(line3)).isEqualTo(line3);
    assertThat(LogRedactor.redact(line4)).isEqualTo(line4);
    assertThat(LogRedactor.redact(line5)).isEqualTo(line5);
  }

  @net.jqwik.api.Property(tries = 100)
  void randomHexIdentifiersSurviveUnchanged(
      @net.jqwik.api.ForAll("traceIds") String traceId,
      @net.jqwik.api.ForAll("spanIds") String spanId,
      @net.jqwik.api.ForAll("sha256Digests") String digest) {
    String msg = String.format("trace %s span %s image sha256:%s", traceId, spanId, digest);
    String redacted = LogRedactor.redact(msg);
    assertThat(redacted).isEqualTo(msg);
  }

  @net.jqwik.api.Provide
  net.jqwik.api.Arbitrary<String> traceIds() {
    return net.jqwik.api.Arbitraries.strings()
        .withCharRange('0', '9')
        .withCharRange('a', 'f')
        .ofLength(32);
  }

  /**
   * A span id that happens to be all digits (about 1 in 1,800) can be a card-shaped 16-digit number
   * — {@code 4000000000000000} is a Visa test PAN — and the redactor is right to mask it: it cannot
   * tell the two apart, and masking fails closed. Such ids are excluded here and pinned by {@link
   * #masksSpanIdIndistinguishableFromPan()}.
   */
  @net.jqwik.api.Provide
  net.jqwik.api.Arbitrary<String> spanIds() {
    return net.jqwik.api.Arbitraries.strings()
        .withCharRange('0', '9')
        .withCharRange('a', 'f')
        .ofLength(16)
        .filter(id -> id.chars().anyMatch(Character::isLetter));
  }

  @Test
  @DisplayName("an all-digit span id shaped like a card number is masked, failing closed")
  void masksSpanIdIndistinguishableFromPan() {
    assertThat(LogRedactor.redact("trace ab12f3577b34da6a3ce929d0e0e47360 span 4000000000000000"))
        .isEqualTo("trace ab12f3577b34da6a3ce929d0e0e47360 span " + LogRedactor.REDACTED);
  }

  @net.jqwik.api.Provide
  net.jqwik.api.Arbitrary<String> sha256Digests() {
    return net.jqwik.api.Arbitraries.strings()
        .withCharRange('0', '9')
        .withCharRange('a', 'f')
        .ofLength(64);
  }
}
