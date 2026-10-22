package dev.fondaria.demo.k8s;

import dev.fondaria.demo.DemoToolsException;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Everything this module reaches through {@code kubectl} rather than the platform's own APIs --
 * originally just {@code advance}'s one deliberate exception to "everything through the APIs"
 * (`phase-D-demo.md` Task D.1, writing the demo clock's offset), and now also {@code DemoAuth}'s
 * transient tunnel to Keycloak (Task D.3), since Keycloak carries no NodePort either. {@code
 * kubectl} is not a new dependency: every other cluster-facing {@code Makefile} target ({@code
 * k8s-up}, {@code k8s-status}, {@code skaffold-run}) already requires it on the operator's machine.
 *
 * <p>Reaches things no API exposes on purpose (the {@code postgres-app} credential CloudNativePG
 * generates) and services with no public API at all ({@code allotment-job}, {@code eod-job}, {@code
 * statement-worker}, and now {@code keycloak}). All of it goes through non-interactive, single-shot
 * {@code kubectl exec}/{@code port-forward} calls -- never {@code -it} (`AGENTS.md` testing rules
 * ban interactive flags for this session, and there is no reason for a demo tool to need one
 * either).
 */
public final class Kubectl {

  private final String context;
  private final String namespace;

  public Kubectl(String context, String namespace) {
    this.context = Objects.requireNonNull(context, "context");
    this.namespace = Objects.requireNonNull(namespace, "namespace");
  }

  public record Credential(String username, String password) {}

  /**
   * Reads and base64-decodes {@code postgres-app}'s `username`/`password` keys (CNPG-generated).
   */
  public Credential readPostgresCredential() {
    String username = decode(getSecretField("postgres-app", "username"));
    String password = decode(getSecretField("postgres-app", "password"));
    return new Credential(username, password);
  }

  private String getSecretField(String secretName, String field) {
    return runCapturing(
            Duration.ofSeconds(15),
            "get",
            "secret",
            secretName,
            "-o",
            "jsonpath={.data." + field + "}")
        .strip();
  }

  private static String decode(String base64) {
    return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
  }

  /**
   * Runs one SQL statement against {@code postgres-rw} as the given role, non-interactively. {@code
   * ON_ERROR_STOP=1} turns a SQL error (e.g. the demo clock's forbid-decrease trigger) into a
   * non-zero exit this module surfaces, rather than psql printing an error and exiting 0.
   *
   * <p>The password reaches the remote {@code psql} process the only way a non-interactive {@code
   * kubectl exec} allows it to: as an {@code env VAR=value} prefix on the remote command. It is
   * therefore visible, for this call's brief lifetime, in {@code ps} output on both the operator's
   * machine and inside the pod -- an accepted, industry-common trade-off for a short-lived
   * operational command. What this class does control, and does: the password never appears in any
   * exception message or log line this module produces (see {@link #redacted}), which is the actual
   * `AGENTS.md` §7 boundary ("never log ... tokens").
   */
  public void execSql(Credential credential, String sql) {
    runCapturing(
        Duration.ofSeconds(20),
        "exec",
        "svc/postgres-rw",
        "--",
        "env",
        "PGPASSWORD=" + credential.password(),
        "psql",
        "-h",
        "postgres-rw",
        "-U",
        credential.username(),
        "-d",
        "funds",
        "-v",
        "ON_ERROR_STOP=1",
        "-c",
        sql);
  }

  /**
   * Forwards {@code localPort} to {@code service}'s {@code remotePort} until {@link
   * PortForward#close()}. Waits for kubectl's own "Forwarding from" line before returning, so a
   * caller never races the tunnel.
   */
  public PortForward portForward(String service, int localPort, int remotePort) {
    List<String> command = baseCommand();
    command.add("port-forward");
    command.add("svc/" + service);
    command.add(localPort + ":" + remotePort);
    try {
      Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
      BufferedReader reader =
          new BufferedReader(
              new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
      awaitForwardingReady(process, reader, Duration.ofSeconds(15));
      // kubectl writes "Handling connection for <port>" on every forwarded request. Closing this
      // reader (as the success path used to) closes the pipe's read end, and kubectl's next write
      // to it dies with SIGPIPE -- killing the tunnel on its very first poll. Draining it silently
      // for the tunnel's whole life, instead, is what keeps the process alive.
      Thread drainThread = new Thread(() -> drainQuietly(reader), "kubectl-port-forward-drain");
      drainThread.setDaemon(true);
      drainThread.start();
      return new PortForward(process, reader);
    } catch (IOException e) {
      throw new DemoToolsException("could not start kubectl port-forward for " + service, e);
    }
  }

  private static void drainQuietly(BufferedReader reader) {
    try {
      while (reader.readLine() != null) {
        // Discarded -- this thread exists only to keep kubectl's stdout pipe from filling and
        // blocking the port-forward process, not to report anything.
      }
    } catch (IOException ignored) {
      // The reader closes when PortForward#close() destroys the process; that is not a fault.
    }
  }

  public static final class PortForward implements AutoCloseable {
    private final Process process;
    private final BufferedReader stdout;

    private PortForward(Process process, BufferedReader stdout) {
      this.process = process;
      this.stdout = stdout;
    }

    @Override
    public void close() {
      process.destroy();
      try {
        process.waitFor(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      if (process.isAlive()) {
        process.destroyForcibly();
      }
      try {
        stdout.close();
      } catch (IOException ignored) {
        // Best-effort cleanup only.
      }
    }
  }

  private void awaitForwardingReady(Process process, BufferedReader reader, Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    try {
      while (System.nanoTime() < deadline) {
        if (!process.isAlive()) {
          throw new DemoToolsException("kubectl port-forward exited before it was ready");
        }
        if (reader.ready()) {
          String line = reader.readLine();
          if (line != null && line.contains("Forwarding from")) {
            return;
          }
        } else {
          Thread.sleep(100);
        }
      }
      throw new DemoToolsException("kubectl port-forward did not become ready within " + timeout);
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) {
        Thread.currentThread().interrupt();
      }
      throw new DemoToolsException("could not confirm kubectl port-forward readiness", e);
    }
  }

  /**
   * Runs {@code kubectl <args>}, bounded by {@code timeout} end to end. Draining stdout happens on
   * a background thread rather than inline: reading to EOF before calling {@code waitFor} would let
   * a hung {@code kubectl} (an unreachable API server, a firewalled cluster) block forever on the
   * read, past any {@code waitFor} timeout that never gets a chance to run (`AGENTS.md` §4: "every
   * external call has an explicit timeout").
   */
  private String runCapturing(Duration timeout, String... args) {
    List<String> command = baseCommand();
    for (String arg : args) {
      command.add(arg);
    }
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
    } catch (IOException e) {
      throw new DemoToolsException("could not run kubectl " + redacted(args), e);
    }

    CompletableFuture<String> captured =
        CompletableFuture.supplyAsync(
            () -> {
              try (BufferedReader reader =
                  new BufferedReader(
                      new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.lines().reduce("", (a, b) -> a + b + "\n");
              } catch (IOException e) {
                return "";
              }
            },
            Executors.newVirtualThreadPerTaskExecutor());

    String output;
    try {
      output = captured.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      process.destroyForcibly();
      throw new DemoToolsException("kubectl " + redacted(args) + " timed out after " + timeout);
    } catch (ExecutionException e) {
      process.destroyForcibly();
      throw new DemoToolsException("could not run kubectl " + redacted(args), e.getCause());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      throw new DemoToolsException("interrupted running kubectl " + redacted(args), e);
    }

    // Output already reached EOF above, which for a well-behaved process means it is exiting (or
    // has exited) right now -- this is a short grace period for that exit to register, not a
    // second full timeout window.
    Duration exitGracePeriod = Duration.ofSeconds(Math.min(5, timeout.toSeconds()));
    try {
      boolean finished = process.waitFor(exitGracePeriod.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        throw new DemoToolsException(
            "kubectl " + redacted(args) + " did not exit after its output closed");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new DemoToolsException("interrupted running kubectl " + redacted(args), e);
    }

    if (process.exitValue() != 0) {
      throw new DemoToolsException("kubectl " + redacted(args) + " failed: " + redacted(output));
    }
    return output;
  }

  /**
   * Never lets a secret (e.g. {@code execSql}'s {@code PGPASSWORD=...}) reach an exception message
   * or log line.
   */
  private static String redacted(String... args) {
    StringBuilder joined = new StringBuilder();
    for (String arg : args) {
      if (!joined.isEmpty()) {
        joined.append(' ');
      }
      joined.append(redacted(arg));
    }
    return joined.toString();
  }

  private static String redacted(String text) {
    return text.replaceAll("(?i)(PGPASSWORD|PASSWORD)=\\S*", "$1=[REDACTED]");
  }

  private List<String> baseCommand() {
    List<String> command = new ArrayList<>();
    command.add("kubectl");
    command.add("--context");
    command.add(context);
    command.add("-n");
    command.add(namespace);
    return command;
  }
}
