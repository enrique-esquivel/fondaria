package dev.fondaria.demo;

/**
 * Where the platform's three externally reachable services live. Defaults to the local kind
 * cluster's NodePorts (`phase-D-demo.md`: "driven through the NodePorts ... 30080 contribution-api,
 * 30081 balance-api, 30082 valuation-api"); each is overridable so this module also works against a
 * port-forward or a differently addressed environment without a code change.
 */
public record DemoEndpoints(String contributionApi, String balanceApi, String valuationApi) {

  public static DemoEndpoints fromEnvironment() {
    return new DemoEndpoints(
        env("CONTRIBUTION_API_URL", "http://localhost:30080"),
        env("BALANCE_API_URL", "http://localhost:30081"),
        env("VALUATION_API_URL", "http://localhost:30082"));
  }

  private static String env(String variable, String defaultValue) {
    String value = System.getenv(variable);
    return (value == null || value.isBlank()) ? defaultValue : value;
  }
}
