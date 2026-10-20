package de.systemlogistics.slgos.core.auth;

import de.systemlogistics.slgos.core.auth.PreSessionPages.Page;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

/** Builds responses for the pages outside the shell, with the strict CSP these static pages need. */
@Component
class PageResponses {

    static final String CSP = "default-src 'none'; style-src 'self'; script-src 'self'; img-src 'self'; base-uri 'none'; "
            + "form-action 'none'; frame-ancestors 'none'";

    private final PreSessionPages pages;

    PageResponses(PreSessionPages pages) {
        this.pages = pages;
    }

    ResponseEntity<String> page(HttpStatus status, Page page, HttpServletRequest request, String target, String... cookies) {
        String language = PreSessionPages.pickLanguage(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status)
                .contentType(new MediaType("text", "html", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(HttpHeaders.CONTENT_LANGUAGE, language)
                .header("Content-Security-Policy", CSP)
                .header("Referrer-Policy", "no-referrer");
        for (String cookie : cookies) {
            builder.header(HttpHeaders.SET_COOKIE, cookie);
        }
        return builder.body(pages.render(page, language, target));
    }
}
