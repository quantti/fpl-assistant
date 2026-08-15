package dev.quantti.fplassistant.tools;

import java.util.List;

/**
 * Substring name lookups can match more than one player or club — {@code web_name} is not unique
 * (2025-26 has two Onanas, two Andersons, two Barneses), and "man" matches both Manchester clubs.
 * Every tool picks the best match and answers for it; this renders the note that says so, rather
 * than letting the answer look like the only possible reading of the question.
 */
final class NameMatch {

    private NameMatch() {
    }

    /**
     * @param names the matched display names, best match first
     * @return an empty string when the match was unambiguous, otherwise a note naming the
     *         alternatives so the model can offer them
     */
    static String ambiguityNote(List<String> names) {
        if (names.size() <= 1) {
            return "";
        }
        return "\n(The name also matched: " + String.join(", ", names.subList(1, names.size()))
                + ". Answered for " + names.getFirst() + " — offer the others if that is not who"
                + " the user meant.)";
    }
}
