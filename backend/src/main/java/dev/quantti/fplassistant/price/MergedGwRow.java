package dev.quantti.fplassistant.price;

/**
 * One row from vaastav's {@code gws/merged_gw.csv}: the price a player was listed at for a
 * given gameweek. {@code elementId} is the season-scoped FPL element id (== tapas
 * {@code player.id} for the same season); {@code price} is in 0.1m units.
 */
record MergedGwRow(int elementId, int gameweek, int price, String name) {
}
