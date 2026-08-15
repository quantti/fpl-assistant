package fi.karivanttinen.fplassistant.tools;

/**
 * LIKE treats {@code %}, {@code _} and {@code \} as metacharacters even inside a bound
 * parameter; unescaped, a literal "%" from the model would match every row.
 */
public final class LikePatterns {

    private LikePatterns() {
    }

    public static String escape(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
