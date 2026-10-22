package dev.fondaria.common.security;

import java.util.Optional;

/**
 * Thread-local context for holding the authenticated {@link CallerIdentity} during request
 * handling.
 */
public final class IdentityContext {

  private static final ThreadLocal<CallerIdentity> CURRENT_CALLER = new ThreadLocal<>();

  private IdentityContext() {}

  public static void set(CallerIdentity identity) {
    if (identity == null) {
      CURRENT_CALLER.remove();
    } else {
      CURRENT_CALLER.set(identity);
    }
  }

  public static Optional<CallerIdentity> get() {
    return Optional.ofNullable(CURRENT_CALLER.get());
  }

  public static CallerIdentity require() {
    CallerIdentity caller = CURRENT_CALLER.get();
    if (caller == null) {
      throw new AccessDeniedProblemException("Unauthenticated: caller identity is missing");
    }
    return caller;
  }

  public static void clear() {
    CURRENT_CALLER.remove();
  }
}
