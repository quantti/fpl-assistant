package fi.karivanttinen.fplassistant.sync;

/** Explicit manual boundary for one coalesced import cycle; it owns no Spring schedule. */
@FunctionalInterface
public interface CoalescedPlayerHistoryImportEntrypoint {
    CoalescedPlayerHistoryCollector.CycleResult runCycle();
}
