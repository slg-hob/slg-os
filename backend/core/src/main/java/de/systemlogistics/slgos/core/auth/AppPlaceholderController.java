package de.systemlogistics.slgos.core.auth;

import de.systemlogistics.slgos.core.auth.PreSessionPages.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Placeholder for the shell document until milestone M3b generates it (PRD-0003 section 10). The session filter has already
 * required a live session, so reaching this controller proves the cookie arrived on the first request after sign-in.
 */
@RestController
class AppPlaceholderController {

    private final PageResponses pages;

    AppPlaceholderController(PageResponses pages) {
        this.pages = pages;
    }

    @GetMapping({"/app", "/app/**"})
    ResponseEntity<String> app(HttpServletRequest request) {
        return pages.page(HttpStatus.OK, Page.APP_PLACEHOLDER, request, null);
    }
}
