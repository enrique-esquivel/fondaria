package dev.fondaria.ledger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * {@link LedgerPort} decorator that resolves the physical TigerBeetle cluster for each command via
 * {@link LedgerClusterResolver} and dispatches to the matching per-cluster delegate (ADR-0005). The
 * TigerBeetle adapter (Task 2.4) "honors the resolved cluster" simply by being wrapped here as one
 * cluster's delegate — the adapter itself does not have to know clusters exist, and any future
 * adapter follows the same pattern.
 *
 * <p><b>Cross-cluster rejection.</b> Within one {@link CreateTransferCommand}, the debit and credit
 * account are always on the same ledger id ({@code Cash} = {@link LedgerCodes#CASH_LEDGER_ID},
 * {@code Unit} = one {@code fundLedgerId}) by construction, so a single command can never itself
 * straddle two clusters. The real risk is a {@link #createTransfers} batch or a {@link
 * #createLinkedChain} whose commands resolve to different clusters — for example a fund switch
 * (sell fund A units, buy fund B units) where funds A and B are pinned to different clusters. One
 * physical batch can only be sent to one TigerBeetle client/cluster connection, so such a call is
 * rejected wholesale — every element comes back {@link TransferResult.Rejected} with {@link
 * LedgerRejectionReason#CROSS_CLUSTER} — rather than silently split across clusters, which would
 * break the single-batch ordering guarantee the TigerBeetle adapter relies on.
 *
 * <p><b>Reads.</b> {@link AccountId} and {@link TransferId} are opaque — they don't carry the
 * ledger id they belong to — so a lookup can't be routed to one cluster up front. Reads fan out to
 * every configured cluster, in declaration order, and return the first match. For a single
 * configured cluster (v1) this is exactly one delegate call, identical to calling that adapter
 * directly. Once a second cluster is configured, {@link #accountTransfers} only paginates correctly
 * within whichever single cluster answers first; true cross-cluster paginated history is not
 * implemented here.
 */
public final class ClusterAwareLedgerPort implements LedgerPort {

  private final LedgerClusterResolver resolver;
  private final Map<ClusterId, LedgerPort> delegatesByCluster;

  public ClusterAwareLedgerPort(
      LedgerClusterResolver resolver, Map<ClusterId, LedgerPort> delegatesByCluster) {
    this.resolver = Objects.requireNonNull(resolver, "resolver must not be null");
    Objects.requireNonNull(delegatesByCluster, "delegatesByCluster must not be null");
    if (delegatesByCluster.isEmpty()) {
      throw new IllegalArgumentException("delegatesByCluster must not be empty");
    }
    this.delegatesByCluster = new LinkedHashMap<>(delegatesByCluster);
  }

  /** Convenience for a single-cluster deployment: every ledger id resolves to {@code delegate}. */
  public static ClusterAwareLedgerPort singleCluster(ClusterId cluster, LedgerPort delegate) {
    Objects.requireNonNull(delegate, "delegate must not be null");
    return new ClusterAwareLedgerPort(
        ConfiguredLedgerClusterResolver.singleCluster(cluster), Map.of(cluster, delegate));
  }

  @Override
  public AccountId createAccount(CreateAccountCommand cmd) {
    return delegateFor(cmd.ledgerId()).createAccount(cmd);
  }

  /**
   * Split by cluster, one delegate call per cluster, results put back in the caller's order. Unlike
   * a transfer batch, a list spanning clusters is not rejected: account creation carries no
   * atomicity a split could break ({@link LedgerPort#createAccounts}), and a caller routinely opens
   * a fund's pooled cash accounts and its unit accounts together.
   */
  @Override
  public List<AccountId> createAccounts(List<CreateAccountCommand> cmds) {
    if (cmds.isEmpty()) {
      return List.of();
    }
    Map<ClusterId, List<Integer>> indicesByCluster = new LinkedHashMap<>();
    for (int i = 0; i < cmds.size(); i++) {
      indicesByCluster
          .computeIfAbsent(resolver.resolveCluster(cmds.get(i).ledgerId()), c -> new ArrayList<>())
          .add(i);
    }
    AccountId[] out = new AccountId[cmds.size()];
    for (Map.Entry<ClusterId, List<Integer>> group : indicesByCluster.entrySet()) {
      List<CreateAccountCommand> groupCmds = new ArrayList<>(group.getValue().size());
      for (int index : group.getValue()) {
        groupCmds.add(cmds.get(index));
      }
      List<AccountId> created = requireDelegate(group.getKey()).createAccounts(groupCmds);
      if (created.size() != groupCmds.size()) {
        throw new IllegalStateException(
            "Cluster "
                + group.getKey()
                + " returned "
                + created.size()
                + " account ids for "
                + groupCmds.size()
                + " accounts");
      }
      for (int j = 0; j < created.size(); j++) {
        out[group.getValue().get(j)] = created.get(j);
      }
    }
    return List.of(out);
  }

  @Override
  public Optional<LedgerAccount> lookupAccount(AccountId id) {
    for (LedgerPort delegate : delegatesByCluster.values()) {
      Optional<LedgerAccount> found = delegate.lookupAccount(id);
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }

  @Override
  public List<LedgerAccount> lookupAccounts(List<AccountId> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    List<LedgerAccount> out = new ArrayList<>();
    for (LedgerPort delegate : delegatesByCluster.values()) {
      out.addAll(delegate.lookupAccounts(ids));
    }
    return List.copyOf(out);
  }

  @Override
  public TransferResult createTransfer(CreateTransferCommand cmd) {
    return createTransfers(List.of(cmd)).get(0);
  }

  @Override
  public List<TransferResult> createTransfers(List<CreateTransferCommand> cmds) {
    if (cmds.isEmpty()) {
      return List.of();
    }
    Set<ClusterId> clusters = resolveAll(cmds);
    if (clusters.size() > 1) {
      return rejectAll(cmds);
    }
    return requireDelegate(onlyElement(clusters)).createTransfers(cmds);
  }

  @Override
  public List<TransferResult> createLinkedChain(List<CreateTransferCommand> cmds) {
    if (cmds.isEmpty()) {
      return List.of();
    }
    Set<ClusterId> clusters = resolveAll(cmds);
    if (clusters.size() > 1) {
      return rejectAll(cmds);
    }
    return requireDelegate(onlyElement(clusters)).createLinkedChain(cmds);
  }

  @Override
  public Optional<LedgerTransfer> lookupTransfer(TransferId id) {
    for (LedgerPort delegate : delegatesByCluster.values()) {
      Optional<LedgerTransfer> found = delegate.lookupTransfer(id);
      if (found.isPresent()) {
        return found;
      }
    }
    return Optional.empty();
  }

  @Override
  public List<LedgerTransfer> accountTransfers(AccountId id, TransferQuery query) {
    for (LedgerPort delegate : delegatesByCluster.values()) {
      List<LedgerTransfer> found = delegate.accountTransfers(id, query);
      if (!found.isEmpty()) {
        return found;
      }
    }
    return List.of();
  }

  /**
   * The latest across every cluster. Each cluster stamps its own transfers, so this is a progress
   * marker (as the port says), never something to order transfers of different clusters by.
   */
  @Override
  public Optional<Instant> latestTransferTimestamp() {
    Optional<Instant> latest = Optional.empty();
    for (LedgerPort delegate : delegatesByCluster.values()) {
      Optional<Instant> head = delegate.latestTransferTimestamp();
      if (head.isPresent() && (latest.isEmpty() || head.get().isAfter(latest.get()))) {
        latest = head;
      }
    }
    return latest;
  }

  private LedgerPort delegateFor(long ledgerId) {
    return requireDelegate(resolver.resolveCluster(ledgerId));
  }

  private LedgerPort requireDelegate(ClusterId cluster) {
    LedgerPort delegate = delegatesByCluster.get(cluster);
    if (delegate == null) {
      throw new IllegalStateException(
          "Resolved cluster " + cluster + " has no configured delegate");
    }
    return delegate;
  }

  private Set<ClusterId> resolveAll(List<CreateTransferCommand> cmds) {
    Set<ClusterId> clusters = new LinkedHashSet<>();
    for (CreateTransferCommand cmd : cmds) {
      clusters.add(resolver.resolveCluster(cmd.ledgerId()));
    }
    return clusters;
  }

  private static ClusterId onlyElement(Set<ClusterId> clusters) {
    return clusters.iterator().next();
  }

  private static List<TransferResult> rejectAll(List<CreateTransferCommand> cmds) {
    List<TransferResult> out = new ArrayList<>(cmds.size());
    for (CreateTransferCommand cmd : cmds) {
      out.add(new TransferResult.Rejected(cmd.id(), LedgerRejectionReason.CROSS_CLUSTER));
    }
    return List.copyOf(out);
  }
}
