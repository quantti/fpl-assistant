package fi.karivanttinen.fplassistant.historical;

import org.springframework.stereotype.Component;
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
        String url = BASE_URL + "/" + season + "/" + fileName;
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            if (body == null || body.isBlank()) {
                throw new IllegalStateException(season + "/" + fileName + ": empty response from " + url);
            }
            return body;
        } catch (RestClientException e) {
            throw new IllegalStateException(season + "/" + fileName + ": failed to fetch " + url, e);
        }
    }
}
