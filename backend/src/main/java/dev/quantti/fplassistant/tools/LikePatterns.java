package dev.quantti.fplassistant.tools;

/**
 * LIKE treats {@code %}, {@code _} and {@code \} as metacharacters even inside a bound
 * parameter; unescaped, a literal "%" from the model would match every row.
 */
final class LikePatterns {

    private LikePatterns() {
    }

    static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
