package fi.karivanttinen.fplassistant.price;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

final class PricePredictionFixtures {

    static final ObjectMapper JSON = new ObjectMapper();
    static final String SEASON = "2026-27";
    static final Instant READ = Instant.parse("2026-10-18T16:00:00Z");
    static final Instant UPDATED = Instant.parse("2026-10-18T15:50:00Z");
    static final Instant TARGET = Instant.parse("2026-10-18T23:00:00Z");

    private PricePredictionFixtures() { }

    static ObjectNode bootstrap() {
        ObjectNode root = JSON.createObjectNode();
        ObjectNode config = root.putObject("game_config");
        ObjectNode settings = config.putObject("settings");
        settings.put("static_content_url", "https://fantasy.premierleague.com/gcs/"
                + "plfpl-prod-static-content/plfpl-production/2026_27/");
        settings.putArray("price_change_deadlines").add(TARGET.toString())
                .add(TARGET.plus(1, ChronoUnit.DAYS).toString());
        config.putObject("status").put("price_change_last_updated", UPDATED.toString());
        ArrayNode events = root.putArray("events");
        for (int id = 1; id <= 38; id++) {
            events.addObject().put("id", id).put("deadline_time",
                    Instant.parse("2026-08-21T17:30:00Z")
                            .plus((id - 1) * 7L, ChronoUnit.DAYS).toString());
        }
        ArrayNode teams = root.putArray("teams");
        for (int id = 1; id <= 20; id++) {
            teams.addObject().put("id", id).put("name", "Club " + id)
                    .put("short_name", "C" + id);
        }
        root.putArray("elements").add(player(1)).add(player(2));
        return root;
    }

    static ObjectNode player(int id) {
        ObjectNode player = JSON.createObjectNode();
        player.put("id", id).put("team", 1).put("element_type", 3)
                .put("web_name", "Player " + id).put("first_name", "Test")
                .put("second_name", "Player " + id).put("now_cost", 75)
                .put("price_change_percent", "94.5").putNull("price_change_locked_until")
                .put("price_change_calibrating", false);
        player.putArray("price_change_projections").addObject().put("offset", 0)
                .put("projected_percent", "104.6").put("likelihood", 5);
        return player;
    }

    static ObjectNode firstPlayer(ObjectNode root) {
        return (ObjectNode) root.path("elements").get(0);
    }

    static ObjectNode projection(ObjectNode root) {
        return (ObjectNode) firstPlayer(root).path("price_change_projections").get(0);
    }

    static ArrayNode history() {
        ArrayNode history = JSON.createArrayNode();
        history.addObject().put("element", 1).put("team", 2).put("event", 7)
                .put("changed_at", "2026-10-17T23:00:08.123456Z")
                .put("cost_before", 74).put("cost_after", 75);
        return history;
    }

    static byte[] bytes(Object value) {
        return value.toString().getBytes(StandardCharsets.UTF_8);
    }

    static PricePredictionSnapshot parse(ObjectNode root) {
        return PricePredictionParser.parse(bytes(root), SEASON, READ);
    }
}
