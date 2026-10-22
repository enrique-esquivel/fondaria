package dev.fondaria.contribution.organization.web;

import dev.fondaria.common.TaxIdScheme;
import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/** Shared request-mapping helpers. */
final class Requests {

  private Requests() {}

  /** Blank and absent mean the same thing in a request body: "no value". */
  static Optional<String> optionalText(String value) {
    return value == null || value.isBlank() ? Optional.empty() : Optional.of(value.trim());
  }

  /** Safe to render: scheme names are structural, not anybody's data. */
  static String supportedSchemes() {
    return Arrays.stream(TaxIdScheme.values()).map(Enum::name).collect(Collectors.joining(", "));
  }
}
