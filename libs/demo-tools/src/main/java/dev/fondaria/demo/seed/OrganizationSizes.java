package dev.fondaria.demo.seed;

/**
 * The 12 organization sizes `seed` registers (`phase-D-demo.md` Task D.2: "20 to 60,000 employees,
 * log-distributed, ~300,000 participants in total"). Deterministic and seed-independent -- the
 * portfolio shape is a demo-script property, not something worth varying per run, unlike the
 * per-participant salaries and dates {@code --seed} does control.
 *
 * <p>A pure geometric sequence from {@code min} to {@code max} undershoots the target total (the
 * top few organizations dominate the sum), so the raw geometric weights are scaled up to hit {@code
 * targetTotal} before being clamped back into range -- a portfolio realistically dominated by its
 * largest few clients, rather than a strict unclamped geometric ladder.
 */
public final class OrganizationSizes {

  private OrganizationSizes() {}

  public static final int COUNT = 12;
  public static final int MIN_EMPLOYEES = 20;
  public static final int MAX_EMPLOYEES = 60_000;
  public static final int TARGET_TOTAL = 300_000;

  public static int[] generate() {
    return generate(COUNT, MIN_EMPLOYEES, MAX_EMPLOYEES, TARGET_TOTAL);
  }

  static int[] generate(int count, int min, int max, int targetTotal) {
    if (count < 2) {
      throw new IllegalArgumentException("count must be at least 2");
    }
    double ratio = Math.pow((double) max / min, 1.0 / (count - 1));
    double[] raw = new double[count];
    for (int i = 0; i < count; i++) {
      raw[i] = min * Math.pow(ratio, i);
    }

    // Water-filling: scaling every raw weight to hit targetTotal in one pass pushes the largest
    // few past `max`; capping those at `max` without redistributing their share would leave the
    // total short (a pure clamp-after-scale undershoots by exactly what the clamp removed). So
    // this caps one organization at a time and rescales what is left of the target across the
    // remaining, uncapped weights, repeating until nothing new needs capping.
    int[] sizes = new int[count];
    boolean[] capped = new boolean[count];
    double remainingTotal = targetTotal;
    while (true) {
      double freeRawSum = 0;
      for (int i = 0; i < count; i++) {
        if (!capped[i]) {
          freeRawSum += raw[i];
        }
      }
      double scale = remainingTotal / freeRawSum;
      boolean newlyCapped = false;
      for (int i = 0; i < count; i++) {
        if (capped[i]) {
          continue;
        }
        double scaledValue = raw[i] * scale;
        if (scaledValue > max) {
          sizes[i] = max;
          capped[i] = true;
          remainingTotal -= max;
          newlyCapped = true;
        }
      }
      if (!newlyCapped) {
        for (int i = 0; i < count; i++) {
          if (!capped[i]) {
            sizes[i] = (int) Math.max(min, Math.round(raw[i] * scale));
          }
        }
        break;
      }
    }
    return sizes;
  }
}
