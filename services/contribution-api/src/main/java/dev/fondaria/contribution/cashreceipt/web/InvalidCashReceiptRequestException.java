package dev.fondaria.contribution.cashreceipt.web;

import java.io.Serial;

/**
 * A cash-receipt request could not be turned into a domain command.
 *
 * <p>Exists so that only <i>vetted</i> messages reach a response body, exactly as {@code
 * InvalidOrganizationRequestException} does for the organization endpoints. Each message names the
 * field and the rule and never the value: an amount is a monetary figure and a bank reference
 * identifies a real payment, and neither belongs in an error a proxy will log
 * (docs/ENGINEERING-RULES.md §7).
 */
public final class InvalidCashReceiptRequestException extends RuntimeException {

  @Serial private static final long serialVersionUID = 1L;

  public InvalidCashReceiptRequestException(String message) {
    super(message);
  }
}
