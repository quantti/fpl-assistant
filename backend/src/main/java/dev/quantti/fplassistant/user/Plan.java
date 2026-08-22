package dev.quantti.fplassistant.user;

/**
 * Which tier an account is on, derived on every request and never stored.
 *
 * <p>A {@code plan} column would be a second cache of Stripe's state sitting beside the
 * subscription table Phase 5b adds, which is itself a cache — and two caches of one truth drift.
 * The plan is therefore resolved from what is known at the time: today every account is free,
 * and 5b adds the single branch that asks whether an entitling subscription exists.
 *
 * <p>One constant is still worth an enum. Free and paid tell the user different things — the
 * free limit is the offer and is stated, the paid limit is a cost-control number and is not —
 * so both {@link UsageController} and {@link QuotaExceededException} have to branch, and they
 * must branch on the same value rather than on a local assumption each.
 */
public enum Plan {
    FREE
}
