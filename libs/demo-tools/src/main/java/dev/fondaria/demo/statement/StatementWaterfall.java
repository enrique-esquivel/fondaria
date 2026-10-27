package dev.fondaria.demo.statement;

import dev.fondaria.demo.Display;
import dev.fondaria.demo.json.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One rendered {@code ParticipantStatement}, read back from the statement-objects bucket exactly as
 * statement-worker stored it, and laid out as the waterfall {@code docs/DOMAIN.md} describes.
 *
 * <p>Every amount is the {@code long} cents the object carries. The one thing computed here is a
 * check, not a figure: the lines must add up to the closing balance to the centavo, and the output
 * says whether they do.
 */
record StatementWaterfall(
    String participantId,
    String period,
    long openingBalanceCents,
    long contributionsEmployeeCents,
    long contributionsEmployerCents,
    long contributionsVoluntaryCents,
    long withdrawalsCents,
    long feesCents,
    long taxWithheldCents,
    long marketMovementCents,
    long closingBalanceCents,
    String generatedAt) {

  static StatementWaterfall parse(String json) {
    Map<String, Object> o = JsonParser.parseObject(json);
    return new StatementWaterfall(
        JsonParser.str(o, "participantId"),
        JsonParser.str(o, "period"),
        JsonParser.longAt(o, "openingBalanceCents"),
        JsonParser.longAt(o, "contributionsEmployeeCents"),
        JsonParser.longAt(o, "contributionsEmployerCents"),
        JsonParser.longAt(o, "contributionsVoluntaryCents"),
        JsonParser.longAt(o, "withdrawalsCents"),
        JsonParser.longAt(o, "feesCents"),
        JsonParser.longAt(o, "taxWithheldCents"),
        JsonParser.longAt(o, "marketMovementCents"),
        JsonParser.longAt(o, "closingBalanceCents"),
        JsonParser.str(o, "generatedAt"));
  }

  /** {@code closing = opening + contributions - withdrawals - fees - tax + market movement}. */
  boolean reconciles() {
    long sum =
        openingBalanceCents
            + contributionsEmployeeCents
            + contributionsEmployerCents
            + contributionsVoluntaryCents
            - withdrawalsCents
            - feesCents
            - taxWithheldCents
            + marketMovementCents;
    return sum == closingBalanceCents;
  }

  List<String> render() {
    List<String> lines = new ArrayList<>();
    lines.add(line("Opening balance", openingBalanceCents));
    lines.add(line("+ Employee contributions", contributionsEmployeeCents));
    lines.add(line("+ Employer contributions", contributionsEmployerCents));
    lines.add(line("+ Voluntary contributions", contributionsVoluntaryCents));
    lines.add(line("- Withdrawals", withdrawalsCents));
    lines.add(line("- Fees", feesCents));
    lines.add(line("- ISR withheld", taxWithheldCents));
    lines.add(line("+ Market movement", marketMovementCents));
    lines.add("    " + "-".repeat(44));
    lines.add(line("Closing balance", closingBalanceCents));
    lines.add(
        reconciles()
            ? "    The lines add up to the closing balance to the centavo."
            : "    THE LINES DO NOT ADD UP TO THE CLOSING BALANCE -- report this statement.");
    return lines;
  }

  private static String line(String label, long cents) {
    return String.format("    %-28s %16s", label, Display.pesos(cents));
  }
}
