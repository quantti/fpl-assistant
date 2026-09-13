package dev.quantti.fplassistant.shortlist;

import java.util.List;
import java.util.UUID;

/** Player identity metadata transported with an answer, never with model history. */
public record PlayerCatalogueMetadata(UUID catalogueId, String season, List<Player> players) {

    public record Player(int elementId, String webName, String fullName, String teamShort,
                         String teamName, int elementType, List<String> aliases) {
        public Player {
            aliases = List.copyOf(aliases);
        }
    }

    public PlayerCatalogueMetadata {
        players = List.copyOf(players);
    }

    public static PlayerCatalogueMetadata from(PlayerCatalogueStore.Catalogue catalogue) {
        return new PlayerCatalogueMetadata(catalogue.id(), catalogue.season(),
                catalogue.players().stream().map(player -> new Player(
                        player.elementId(), player.webName(), player.fullName(), player.teamShort(),
                        player.teamName(), player.position(), player.aliases())).toList());
    }
}
