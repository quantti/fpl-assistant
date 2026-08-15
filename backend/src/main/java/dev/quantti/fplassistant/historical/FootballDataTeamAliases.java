package dev.quantti.fplassistant.historical;

import java.util.Map;

/**
 * football-data.org's full club names → persistent FPL {@code code}. A different alias set
 * from {@link VaastavTeamMapper}'s: football-data's API returns names like "Wolverhampton
 * Wanderers FC" where vaastav's {@code teams.csv} says "Wolves" — no substring or fuzzy match
 * bridges the two, so this is its own explicit table, covering every club that appeared in the
 * real 2019-20..2025-26 backfill (28 clubs — verified by querying the distinct names actually
 * in {@code pl_match_result} after Task 3's backfill, not guessed at).
 *
 * <p><b>Not verified against the live football-data.org API.</b> This project has no API key
 * and cannot register for one autonomously (see the Phase 3 spec/STATUS.md). The names below
 * follow football-data.org's publicly documented naming convention ("&lt;Club&gt; FC"/"AFC
 * &lt;Club&gt;") but have not been confirmed against a real response. {@link #resolve} fails
 * loudly on an unrecognised name rather than silently dropping the match — if the sync ever
 * throws "unknown football-data team name", that is this table needing a correction, and
 * exactly the failure mode this design is meant to surface rather than hide.
 */
final class FootballDataTeamAliases {

    private static final Map<String, Integer> CODE_BY_NAME = Map.ofEntries(
            Map.entry("Manchester United FC", 1),
            Map.entry("Leeds United FC", 2),
            Map.entry("Arsenal FC", 3),
            Map.entry("Newcastle United FC", 4),
            Map.entry("Tottenham Hotspur FC", 6),
            Map.entry("Aston Villa FC", 7),
            Map.entry("Chelsea FC", 8),
            Map.entry("Everton FC", 11),
            Map.entry("Leicester City FC", 13),
            Map.entry("Liverpool FC", 14),
            Map.entry("Nottingham Forest FC", 17),
            Map.entry("Southampton FC", 20),
            Map.entry("West Ham United FC", 21),
            Map.entry("Crystal Palace FC", 31),
            Map.entry("West Bromwich Albion FC", 35),
            Map.entry("Brighton & Hove Albion FC", 36),
            Map.entry("Wolverhampton Wanderers FC", 39),
            Map.entry("Ipswich Town FC", 40),
            Map.entry("Manchester City FC", 43),
            Map.entry("Norwich City FC", 45),
            Map.entry("Sheffield United FC", 49),
            Map.entry("Fulham FC", 54),
            Map.entry("Sunderland AFC", 56),
            Map.entry("Watford FC", 57),
            Map.entry("Burnley FC", 90),
            Map.entry("AFC Bournemouth", 91),
            Map.entry("Brentford FC", 94),
            Map.entry("Luton Town FC", 102));

    private FootballDataTeamAliases() {
    }

    /** @throws IllegalStateException naming the unrecognised name — never silently dropped */
    static int resolve(String footballDataName) {
        Integer code = CODE_BY_NAME.get(footballDataName);
        if (code == null) {
            throw new IllegalStateException("Unknown football-data team name: '" + footballDataName
                    + "' — add it to FootballDataTeamAliases (unverified against the live API;"
                    + " this is exactly the kind of gap that surfaces once the sync runs for real)");
        }
        return code;
    }
}
