package fi.karivanttinen.fplassistant.sync;

/** Explicit owned-writer runtime state. Missing configuration is always OFF. */
public enum OwnedSyncMode {
    OFF, STAGING, ACTIVE
}
