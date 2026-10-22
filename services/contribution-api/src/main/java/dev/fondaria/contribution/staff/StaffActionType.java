package dev.fondaria.contribution.staff;

public enum StaffActionType {
  CORRECTION,
  NAV_SUPERSESSION,
  MANUAL_ADJUSTMENT,
  KILL_SWITCH;

  public static StaffActionType fromString(String value) {
    if (value == null) {
      throw new IllegalArgumentException("Staff action type must not be null");
    }
    return StaffActionType.valueOf(value.trim().toUpperCase());
  }
}
