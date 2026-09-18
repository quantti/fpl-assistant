package dev.quantti.fplassistant.tools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * The one place a player name typed by a user becomes SQL (KAR-57).
 *
 * <p>Three things the hand-written {@code LOWER(col) LIKE ?} lookups could not do: ignore accents
 * ("Muharemovic" is how Muharemović is typed on every keyboard that matters), match a name given
 * in more than one word against more than one column, and use the club — which is the very thing
 * the assistant asks for when a name is ambiguous. Every token must hit the name or the club; at
 * least one must hit the name, so "Newcastle" alone is not a player.
 *
 * <p>Folding removes accents and case. It never maps a name onto a similar-looking one: "konsa"
 * still does not find Konaté.
 *
 * <p>{@code unaccent} is ensured here rather than in a schema class because nothing else needs
 * it, and it is <b>probed, not assumed</b>: a startup statement that throws has taken production
 * down before. A refused {@code CREATE} is logged at WARN and the probe still runs — the
 * extension may exist already while the statement is refused — and if the function cannot be
 * called the search degrades to plain {@code lower()}, the pre-KAR-57 behaviour, and says so at
 * ERROR. Nothing here can fail startup: both steps catch {@code RuntimeException}.
 */
@Component
public class PlayerNameSearch {

    private static final Logger log = LoggerFactory.getLogger(PlayerNameSearch.class);

    static final int MAX_TOKENS = 6;

    private final boolean unaccent;

    @Autowired
    public PlayerNameSearch(JdbcTemplate jdbc) {
        this(probe(jdbc));
    }

    PlayerNameSearch(boolean unaccent) {
        this.unaccent = unaccent;
    }

    private static boolean probe(JdbcTemplate jdbc) {
        try {
            jdbc.execute("CREATE EXTENSION IF NOT EXISTS unaccent");
        } catch (RuntimeException e) {
            // A refused CREATE is not an unusable function: read-only mode, or a role without
            // CREATE, refuses it while the extension is already there and callable.
            log.warn("Player name search: could not create the unaccent extension, probing anyway", e);
        }
        try {
            String folded = jdbc.queryForObject("SELECT unaccent('ć')", String.class);
            boolean ok = "c".equals(folded);
            if (ok) {
                log.info("Player name search: accent folding ON");
            } else {
                log.error("Player name search: unaccent('ć') returned '{}', accent folding OFF", folded);
            }
            return ok;
        } catch (RuntimeException e) {
            log.error("Player name search: unaccent unavailable, accent folding OFF", e);
            return false;
        }
    }

    static List<String> tokens(String query) {
        if (query == null) {
            return List.of();
        }
        return Arrays.stream(query.split("[\\s,]+"))
                .filter(t -> !t.isBlank())
                .limit(MAX_TOKENS)
                .toList();
    }

    Fragment fragment(String query, String webNameExpr, String nameExpr, String clubExpr) {
        List<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return new Fragment("(1 = 0)", List.of(), "(1 = 0) DESC", List.of());
        }
        String name = fold(nameExpr);
        String club = fold(clubExpr);
        String web = fold(webNameExpr);
        String param = fold("?");

        List<String> all = new ArrayList<>();
        List<Object> whereArgs = new ArrayList<>();
        for (String token : tokens) {
            all.add("(" + name + " LIKE " + param + " OR " + club + " LIKE " + param + ")");
            whereArgs.add(pattern(token));
            whereArgs.add(pattern(token));
        }
        all.add("(" + tokens.stream().map(t -> name + " LIKE " + param)
                .collect(Collectors.joining(" OR ")) + ")");
        tokens.forEach(t -> whereArgs.add(pattern(t)));

        String exactFirst = "(" + web + " = " + param + ") DESC, ("
                + tokens.stream().map(t -> web + " = " + param).collect(Collectors.joining(" OR "))
                + ") DESC";
        List<Object> orderArgs = new ArrayList<>();
        orderArgs.add(query.trim());
        orderArgs.addAll(tokens);

        return new Fragment("(" + String.join(" AND ", all) + ")", List.copyOf(whereArgs),
                exactFirst, List.copyOf(orderArgs));
    }

    private String fold(String expr) {
        return unaccent ? "unaccent(lower(" + expr + "))" : "lower(" + expr + ")";
    }

    private static String pattern(String token) {
        return "%" + LikePatterns.escape(token) + "%";
    }

    record Fragment(String where, List<Object> whereArgs, String exactFirst, List<Object> orderArgs) {
    }
}
