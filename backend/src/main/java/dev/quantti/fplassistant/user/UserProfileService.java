package dev.quantti.fplassistant.user;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Reads and writes the caller's own profile row, keyed on the JWT {@code sub} claim.
 *
 * <p>Every method takes the user id as its first argument and filters on it. There is no method
 * that reads a profile by anything else — the authorization contract is that {@code sub} is the
 * only source of a user id, and a service that cannot be asked for someone else's row cannot
 * accidentally hand one over.
 */
@Service
public class UserProfileService {

    private final JdbcTemplate jdbc;
    private final String profileTable;

    public UserProfileService(JdbcTemplate jdbc,
                              @Value("${fpl.user.profile-table-name}") String profileTable) {
        this.jdbc = jdbc;
        this.profileTable = profileTable;
    }

    /** Empty when the user has no profile row yet, or has not set a team id. */
    public Optional<Integer> teamIdOf(UUID userId) {
        List<Integer> found = jdbc.queryForList(
                "SELECT fpl_team_id FROM " + profileTable + " WHERE user_id = ?",
                Integer.class, userId);
        return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.getFirst());
    }

    /**
     * {@code null} clears the id. The row is created if the user has never had one.
     *
     * <p>No {@code updated_at = NOW()} here: a BEFORE UPDATE trigger owns that column, and a
     * second mechanism writing it would only mislead the next caller who tries to preserve it.
     */
    public void setTeamId(UUID userId, Integer teamId) {
        jdbc.update("""
                INSERT INTO %s (user_id, fpl_team_id) VALUES (?, ?)
                ON CONFLICT (user_id) DO UPDATE SET fpl_team_id = EXCLUDED.fpl_team_id
                """.formatted(profileTable), userId, teamId);
    }
}
