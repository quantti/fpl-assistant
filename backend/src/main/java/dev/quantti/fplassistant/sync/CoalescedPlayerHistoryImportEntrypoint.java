package dev.quantti.fplassistant.sync;

/** Callable boundary for one coalesced import cycle, used by the manual and hourly adapters. */
@FunctionalInterface
public interface CoalescedPlayerHistoryImportEntrypoint {
    CoalescedPlayerHistoryCollector.CycleResult runCycle();
}
