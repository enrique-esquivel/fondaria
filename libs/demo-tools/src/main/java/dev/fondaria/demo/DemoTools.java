package dev.fondaria.demo;

import dev.fondaria.demo.advance.AdvanceCommand;
import dev.fondaria.demo.close.CloseCommand;
import dev.fondaria.demo.contribution.ContributionsCommand;
import dev.fondaria.demo.http.DemoHttpException;
import dev.fondaria.demo.onboard.OnboardCommand;
import dev.fondaria.demo.seed.SeedCommand;
import dev.fondaria.demo.statement.StatementsCommand;
import dev.fondaria.demo.valuation.ValuationsCommand;

/**
 * Entry point for every {@code demo-tools} command (`phase-D-demo.md` Task D.2): {@code seed},
 * {@code contributions}, {@code valuations}, {@code onboard}, {@code advance}, and the two that
 * make the daily close visible: {@code close} and {@code statements}. Run with:
 *
 * <pre>
 * java -cp libs/demo-tools/target/classes:libs/platform-common/target/classes dev.fondaria.demo.DemoTools &lt;command&gt; [--flag value ...]
 * </pre>
 */
public final class DemoTools {

  private DemoTools() {}

  public static void main(String[] args) {
    // DemoHttpClient.putBytes needs to set the Host header explicitly when it reaches the object
    // store
    // through a port-forward (Task D.3) -- the presigned URL's AWS SigV4 signature covers the
    // original Host, not localhost. Must be set before any java.net.http.HttpClient is
    // constructed anywhere in this process, hence first line of main.
    System.setProperty("jdk.httpclient.allowRestrictedHeaders", "host");

    if (args.length == 0) {
      System.err.println(
          "usage: demo-tools <seed|contributions|valuations|onboard|advance|close|statements>"
              + " [--flag value ...]");
      System.exit(1);
      return;
    }

    String command = args[0];
    Args parsed = Args.parse(args, 1);
    DemoPaths paths = DemoPaths.defaultLocation();

    try {
      switch (command) {
        case "seed" -> SeedCommand.run(parsed, paths);
        case "contributions" -> ContributionsCommand.run(parsed, paths);
        case "valuations" -> ValuationsCommand.run(parsed, paths);
        case "onboard" -> OnboardCommand.run(parsed, paths);
        case "advance" -> AdvanceCommand.run(parsed, paths);
        case "close" -> CloseCommand.run(parsed, paths);
        case "statements" -> StatementsCommand.run(parsed, paths);
        case "token" -> System.out.println(new dev.fondaria.demo.auth.DemoAuth().bearerToken());
        default -> throw new DemoToolsException("unknown command: " + command);
      }
    } catch (DemoHttpException e) {
      System.err.println("HTTP call failed: " + e.method() + " " + e.uri());
      if (e.status() >= 0) {
        System.err.println("  status " + e.status() + ": " + e.body());
      } else {
        System.err.println("  " + e.getMessage());
      }
      System.exit(1);
    } catch (DemoToolsException e) {
      System.err.println(e.getMessage());
      System.exit(1);
    }
  }
}
