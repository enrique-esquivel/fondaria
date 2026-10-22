package dev.fondaria.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link LedgerCodes} and {@code docs/chart-of-accounts.md} cannot silently drift apart:
 * every code in the enum must appear in the document with the same numeric value, and vice versa.
 */
class ChartOfAccountsParityTest {

  private static final Pattern CODE_ROW =
      Pattern.compile("(?m)^\\|\\s*`([A-Z0-9_]+)`\\s*\\|\\s*(\\d+)\\s*\\|");
  private static final Pattern SECTION_HEADING = Pattern.compile("(?m)^## \\d+\\..*$");

  @Test
  void cashAccountsMatchDocumentSection2() {
    Map<String, Integer> documented = codesInSection(2);
    Map<String, Integer> declared = new LinkedHashMap<>();
    for (LedgerCodes.AccountCode code : LedgerCodes.AccountCode.values()) {
      if (code.code() < 100) {
        declared.put(code.name(), code.code());
      }
    }
    assertThat(documented).isEqualTo(declared);
  }

  @Test
  void unitAccountsMatchDocumentSection3() {
    Map<String, Integer> documented = codesInSection(3);
    Map<String, Integer> declared = new LinkedHashMap<>();
    for (LedgerCodes.AccountCode code : LedgerCodes.AccountCode.values()) {
      if (code.code() >= 100) {
        declared.put(code.name(), code.code());
      }
    }
    assertThat(documented).isEqualTo(declared);
  }

  @Test
  void transferCodesMatchDocumentSection4() {
    Map<String, Integer> documented = codesInSection(4);
    Map<String, Integer> declared = new LinkedHashMap<>();
    for (LedgerCodes.TransferCode code : LedgerCodes.TransferCode.values()) {
      declared.put(code.name(), code.code());
    }
    assertThat(documented).isEqualTo(declared);
  }

  private Map<String, Integer> codesInSection(int sectionNumber) {
    String markdown = readChartOfAccounts();
    Matcher headings = SECTION_HEADING.matcher(markdown);
    String needle = "## " + sectionNumber + ".";
    int start = -1;
    int end = markdown.length();
    while (headings.find()) {
      if (start == -1 && markdown.startsWith(needle, headings.start())) {
        start = headings.end();
      } else if (start != -1) {
        end = headings.start();
        break;
      }
    }
    assertThat(start).as("section '%s' found in chart-of-accounts.md", needle).isNotEqualTo(-1);

    Map<String, Integer> codes = new LinkedHashMap<>();
    Matcher rows = CODE_ROW.matcher(markdown.substring(start, end));
    while (rows.find()) {
      codes.put(rows.group(1), Integer.valueOf(rows.group(2)));
    }
    return codes;
  }

  private String readChartOfAccounts() {
    Path file = findUpwardsFromWorkingDirectory("docs/chart-of-accounts.md");
    assertThat(file)
        .as("docs/chart-of-accounts.md found by walking up from the working directory")
        .isNotNull();
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Path findUpwardsFromWorkingDirectory(String relativePath) {
    Path dir = Paths.get("").toAbsolutePath();
    while (dir != null) {
      Path candidate = dir.resolve(relativePath);
      if (Files.exists(candidate)) {
        return candidate;
      }
      dir = dir.getParent();
    }
    return null;
  }
}
