package dev.fondaria.common.logging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redaction layer scrubbing prohibited fields from log messages and metadata per {@code AGENTS.md}
 * §7:
 *
 * <ul>
 *   <li>PAN (credit / debit card numbers)
 *   <li>IBAN and Mexican CLABE (18 digits)
 *   <li>Email addresses
 *   <li>Tokens (Bearer, JWT, API tokens, passwords, secrets, access/refresh tokens)
 *   <li>Idempotency keys
 *   <li>Mexican Tax Identifiers (RFC, CURP)
 *   <li>Salaries and Balances
 *   <li>Document contents
 *   <li>Names (participant, employee, user, legal names)
 *   <li>Dates of birth (Task 9.5)
 * </ul>
 *
 * <p>Applied at the appender so it cannot be bypassed by a careless log statement.
 */
public final class LogRedactor {

  public static final String REDACTED = "[REDACTED]";

  // --- 1. Labeled patterns: replaces the sensitive value with [REDACTED] ---
  // Handles compound labels (camelCase, snake_case, kebab-case) and both quoted ("..." or '...')
  // and unquoted values.
  // Note: rfc and curp require [:=] delimiter to prevent scrubbing technical specs like "RFC 9457".
  // Personal names are restricted to personal-name forms only to avoid scrubbing bare name=
  // queue/bean/metric names.
  private static final Pattern LABELED_PATTERN =
      Pattern.compile(
          "(?i)(\"?\\b(?:idempotency[_-]?key|token|api[_-]?key|secret|password|access[_-]?token|refresh[_-]?token"
              + "|clabe|iban|account[_-]?number|cuenta"
              + "|(?:base[_-]?)?salary|salario|(?:current[_-]?|ending[_-]?)?balance|saldo"
              + "|document[_-]?content|file[_-]?content"
              + "|pan|card[_-]?number|tarjeta"
              + "|(?:first|last|full|legal|participant|employee|user)[_-]?name|nombre"
              + "|rfc|curp|date[_-]?of[_-]?birth|birth[_-]?date|fecha[_-]?(?:de[_-]?)?nacimiento)"
              + "\"?\\s*[:=]\\s*(?:Bearer\\s+)?|\\bBearer\\s+)(?:\"([^\"]*)\"|'([^']*)'|([^\"'\\s,;}{]+))");

  // --- 2. Standalone token patterns: replaces entire matching token with [REDACTED] ---
  private static final List<Pattern> TOKEN_PATTERNS =
      List.of(
          // JWTs (three base64url segments starting with eyJ)
          Pattern.compile(
              "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]+\\b",
              Pattern.CASE_INSENSITIVE),
          // Email addresses
          Pattern.compile(
              "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b", Pattern.CASE_INSENSITIVE),
          // Mexican CURP (18 alphanumeric chars with specific format) - case insensitive
          Pattern.compile("\\b[A-Z]{4}\\d{6}[HM][A-Z]{5}[0-9A-Z]\\d\\b", Pattern.CASE_INSENSITIVE),
          // Credit card numbers (PAN): Visa, MasterCard, Amex, Discover, formatted or contiguous.
          // Uses case-insensitive negative lookbehind to ensure operational IDs (transfer_id,
          // correlation_id, account_id, tenant_id) in camelCase, snake_case or kebab-case are
          // never accidentally scrubbed.
          Pattern.compile(
              "(?i)(?<!(?:transfer[_-]?id|correlation[_-]?id|account[_-]?id|tenant[_-]?id)[\"':=\\s]{1,10})"
                  + "\\b(?:4[0-9]{12}(?:[0-9]{3})?|5[1-5][0-9]{14}|6(?:011|5[0-9]{2})[0-9]{12}|3[47][0-9]{13}|3(?:0[0-5]|[68][0-9])[0-9]{11}|[0-9]{4}[- ][0-9]{4}[- ][0-9]{4}[- ][0-9]{1,7})\\b"),
          // Mexican 18-digit bank CLABE.
          // Requires word boundary and must not be preceded by holding, account, id, participant,
          // tenant, or a [=:] delimiter following any *_id / *Id identifier.
          Pattern.compile(
              "(?i)(?<!(?:\\bholding|\\baccount|\\bid|\\bparticipant|\\btenant|[a-z0-9_]*id)[\\s\"':=]{1,10})"
                  + "\\b\\d{18}\\b"));

  private LogRedactor() {}

  /** Redacts any sensitive data from the given string. */
  public static String redact(String input) {
    if (input == null || input.isEmpty()) {
      return input;
    }

    // 1. Scrub labeled patterns
    Matcher matcher = LABELED_PATTERN.matcher(input);
    StringBuilder sb = new StringBuilder(input.length());
    while (matcher.find()) {
      String prefix = matcher.group(1);
      if (matcher.group(2) != null) {
        // Double quoted value
        matcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + "\"" + REDACTED + "\""));
      } else if (matcher.group(3) != null) {
        // Single quoted value
        matcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + "'" + REDACTED + "'"));
      } else {
        // Unquoted value
        matcher.appendReplacement(sb, Matcher.quoteReplacement(prefix + REDACTED));
      }
    }
    matcher.appendTail(sb);
    String result = sb.toString();

    // 2. Scrub standalone token patterns
    for (Pattern pattern : TOKEN_PATTERNS) {
      Matcher m = pattern.matcher(result);
      if (m.find()) {
        result = m.replaceAll(REDACTED);
      }
    }

    return result;
  }
}
