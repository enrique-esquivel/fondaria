package dev.fondaria.common.security;

/**
 * Least-privilege separated roles for the internal operations staff population:
 *
 * <ul>
 *   <li>{@link #SUPPORT}: Read-only customer and operations support.
 *   <li>{@link #OPERATIONS}: Operations personnel initiating changes and actions.
 *   <li>{@link #APPROVER}: Distinct four-eyes approver. Cannot also request the action it approves.
 * </ul>
 */
public enum StaffRole {
  SUPPORT("internal-support"),
  OPERATIONS("internal-operations"),
  APPROVER("internal-approver");

  private final String roleName;

  StaffRole(String roleName) {
    this.roleName = roleName;
  }

  public String roleName() {
    return roleName;
  }

  public static StaffRole fromRoleName(String name) {
    if (name == null) {
      return null;
    }
    for (StaffRole role : values()) {
      if (role.roleName.equalsIgnoreCase(name) || role.name().equalsIgnoreCase(name)) {
        return role;
      }
    }
    return null;
  }
}
