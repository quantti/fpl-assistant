package fi.karivanttinen.fplassistant.historical;

import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Fetches vaastav CSVs from the public GitHub repository. No key, no rate limit. */
@Component
public class HttpVaastavSource implements VaastavSource {

    private static final String BASE_URL =
            "https://raw.githubusercontent.com/vaastav/Fantasy-Premier-League/master/data";

    private final RestClient restClient;

    public HttpVaastavSource() {
        this.restClient = RestClient.create();
    }

    @Override
    public String fetch(String season, String fileName) {
        String body = get(season, fileName);
        if (body == null) {
            throw new IllegalStateException(
                    season + "/" + fileName + ": empty or missing at " + url(season, fileName));
        }
        return body;
    }

    @Override
    public String fetchIfPresent(String season, String fileName) {
        return get(season, fileName);
    }

    /**
     * @return the body, or null when the file is absent (404) or empty — e.g. the current
     *         season's {@code gws/merged_gw.csv} before GW1. Any other failure throws.
     */
    private String get(String season, String fileName) {
        String url = url(season, fileName);
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            return (body == null || body.isBlank()) ? null : body;
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientException e) {
            throw new IllegalStateException(season + "/" + fileName + ": failed to fetch " + url, e);
        }
    }

    private static String url(String season, String fileName) {
        return BASE_URL + "/" + season + "/" + fileName;
    }
}
