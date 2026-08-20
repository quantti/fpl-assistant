package dev.quantti.fplassistant.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Client-side routes resolve to {@code index.html} so a deep link or a reload loads the
 * application instead of 404ing. Recorded as a known issue in Phase 1: the static resource
 * handler serves files, and a route that exists only in the browser's router is not a file.
 *
 * <p>What it must not swallow is the point of the patterns below. {@code /api/**} has to keep
 * answering JSON — a 404 turned into a 200 page of HTML is a client that parses markup as an
 * error object — and {@code /actuator/**} has to keep answering the health check. Anything with
 * a dot in the last segment is a file request: a missing asset must 404 rather than return the
 * index page with a JavaScript content type.
 */
@Controller
public class SpaForwardingController {

    /**
     * Shared with {@link SecurityConfig}, which has to permit exactly these paths: the chain
     * denies by default, and a client route the browser navigates to arrives without a token —
     * the token lives in JavaScript that has not loaded yet.
     */
    // A segment with no dot in it. Written out per level rather than as a trailing "/**" because
    // "/**" matches any number of segments including one with a file extension — which forwarded
    // /assets/index-abc123.js to the index page, under a JavaScript content type, and turned a
    // missing asset into a syntax error in the browser console.
    private static final String SEGMENT = "[^.]*";
    static final String ROOT_ROUTE = "/{one:(?!api$|actuator$)" + SEGMENT + "}";
    static final String SECOND_LEVEL_ROUTE = ROOT_ROUTE + "/{two:" + SEGMENT + "}";
    static final String THIRD_LEVEL_ROUTE = SECOND_LEVEL_ROUTE + "/{three:" + SEGMENT + "}";

    static final String[] CLIENT_ROUTES = {ROOT_ROUTE, SECOND_LEVEL_ROUTE, THIRD_LEVEL_ROUTE};

    // Spelled out rather than passed as CLIENT_ROUTES: an annotation value has to be a constant
    // expression, and an array reference is not one.
    @GetMapping({ROOT_ROUTE, SECOND_LEVEL_ROUTE, THIRD_LEVEL_ROUTE})
    public String forwardToIndex() {
        return "forward:/index.html";
    }
}
