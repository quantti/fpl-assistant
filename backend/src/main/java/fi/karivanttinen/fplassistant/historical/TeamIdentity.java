package fi.karivanttinen.fplassistant.historical;

/**
 * A club's persistent FPL {@code code} (stable across seasons) and its name as of a
 * particular season (club names are otherwise stable too, but this keeps the two concerns
 * separate rather than assuming it).
 */
public record TeamIdentity(int code, String name) {
}
