package fi.karivanttinen.maintenance;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/** Serves the maintenance page and refuses every application API route without authentication. */
@Controller
public class MaintenanceController {

    private static final Resource PAGE = new ClassPathResource("maintenance.html");
    private static final String SEGMENT = "[^.]*";
    private static final String ROOT_ROUTE = "/{one:(?!api$|actuator$)" + SEGMENT + "}";
    private static final String SECOND_LEVEL_ROUTE = ROOT_ROUTE + "/{two:" + SEGMENT + "}";
    private static final String THIRD_LEVEL_ROUTE = SECOND_LEVEL_ROUTE + "/{three:" + SEGMENT + "}";

    @GetMapping(value = {"/", "/index.html", ROOT_ROUTE, SECOND_LEVEL_ROUTE, THIRD_LEVEL_ROUTE},
            produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<Resource> page() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.TEXT_HTML)
                .body(PAGE);
    }

    @RequestMapping({"/api", "/api/**"})
    public ResponseEntity<ProblemDetail> unavailable() {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "FPL Sidekick is under maintenance until 26 September 2026.");
        problem.setTitle("Maintenance in progress");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .cacheControl(CacheControl.noStore())
                .body(problem);
    }
}
