package de.systemlogistics.slgos.core.auth;

import de.systemlogistics.slgos.core.audit.AuditEvent;
import de.systemlogistics.slgos.core.audit.AuditService;
import de.systemlogistics.slgos.core.audit.AuditTypes;
import de.systemlogistics.slgos.core.auth.PreSessionPages.Page;
import de.systemlogistics.slgos.core.config.SlgProperties;
import de.systemlogistics.slgos.core.health.OidcAvailability;
import de.systemlogistics.slgos.core.security.ClientIp;
import de.systemlogistics.slgos.core.security.CsrfTokens;
import de.systemlogistics.slgos.core.security.RateLimiter;
import de.systemlogistics.slgos.core.security.ReturnTarget;
import de.systemlogistics.slgos.core.security.oidc.IdTokenClaims;
import de.systemlogistics.slgos.core.security.oidc.IdTokenValidator;
import de.systemlogistics.slgos.core.security.oidc.LogoutTokenValidator;
import de.systemlogistics.slgos.core.security.oidc.LogoutTokenValidator.LogoutClaims;
import de.systemlogistics.slgos.core.security.oidc.OidcClient;
import de.systemlogistics.slgos.core.security.oidc.OidcException;
import de.systemlogistics.slgos.core.security.oidc.OidcTokens;
import de.systemlogistics.slgos.core.security.oidc.Pkce;
import de.systemlogistics.slgos.core.session.SessionContext;
import de.systemlogistics.slgos.core.session.SessionCookie;
import de.systemlogistics.slgos.core.session.SessionRecord;
import de.systemlogistics.slgos.core.session.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sign-in, sign-out and back-channel logout (PRD-0003 SK-3). Authorization code + PKCE (S256) against Keycloak; the
 * browser only ever holds the session cookie, the tokens stay on the server (ADR-0012).
 */
@RestController
class AuthController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthController.class);
    private static final String POPUP_TARGET = "/core/auth/popup-done";
    private static final int ATTEMPTS_PER_MINUTE = 30;

    private final SlgProperties properties;
    private final OidcClient oidc;
    private final IdTokenValidator idTokens;
    private final LogoutTokenValidator logoutTokens;
    private final SessionService sessions;
    private final CsrfTokens signer;
    private final ClientIp clientIp;
    private final Clock clock;
    private final AuditService audit;
    private final OidcAvailability availability;
    private final PageResponses pages;
    private final RateLimiter limiter;
    private final String shellBase;
    private final ConcurrentHashMap<String, Instant> consumedStates = new ConcurrentHashMap<>();

    AuthController(SlgProperties properties, OidcClient oidc, IdTokenValidator idTokens, LogoutTokenValidator logoutTokens,
            SessionService sessions, CsrfTokens signer, ClientIp clientIp, Clock clock, AuditService audit,
            OidcAvailability availability, PageResponses pages) {
        this.properties = properties;
        this.oidc = oidc;
        this.idTokens = idTokens;
        this.logoutTokens = logoutTokens;
        this.sessions = sessions;
        this.signer = signer;
        this.clientIp = clientIp;
        this.clock = clock;
        this.audit = audit;
        this.availability = availability;
        this.pages = pages;
        this.limiter = new RateLimiter(clock, ATTEMPTS_PER_MINUTE, Duration.ofMinutes(1));
        this.shellBase = properties.shellBaseUrl().replaceAll("/+$", "");
    }

    /** Starts the sign-in: a redirect to Keycloak with state, nonce and PKCE challenge, and the signed pre-login cookie. */
    @GetMapping("/core/auth/login")
    ResponseEntity<String> login(@RequestParam(name = "return", required = false) String returnTarget,
            @RequestParam(name = "popup", required = false) String popup,
            @RequestParam(name = "stepup", required = false) String stepUp, HttpServletRequest request) {
        if (!limiter.tryAcquire(clientIp.of(request))) {
            return pages.page(HttpStatus.TOO_MANY_REQUESTS, Page.RATE_LIMITED, request, null);
        }
        if (!availability.isAvailable()) {
            return pages.page(HttpStatus.SERVICE_UNAVAILABLE, Page.SIGN_IN_UNAVAILABLE, request, null);
        }
        boolean isPopup = "1".equals(popup);
        boolean isStepUp = "1".equals(stepUp);
        LoginState state = new LoginState(Pkce.randomValue(), Pkce.randomValue(), Pkce.randomValue(),
                isPopup ? POPUP_TARGET : ReturnTarget.sanitize(returnTarget), isPopup, isStepUp, clock.instant());
        String language = PreSessionPages.pickLanguage(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE));
        URI authorization = oidc.authorizationUri(shellBase + "/core/auth/callback", state.state(), state.nonce(),
                Pkce.challenge(state.verifier()), language, isStepUp);
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, authorization.toString())
                .header(HttpHeaders.SET_COOKIE, state.toSetCookie(signer))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .build();
    }

    /**
     * Completes the sign-in. The answer is a 200 document that navigates on, not a 302: a {@code SameSite=Strict} cookie is
     * not sent on a redirect chain that began at another site, but it is on the following same-site navigation (SK-3 AC3).
     */
    @GetMapping("/core/auth/callback")
    ResponseEntity<String> callback(@RequestParam(required = false) String code, @RequestParam(required = false) String state,
            @RequestParam(required = false) String error, HttpServletRequest request) {
        String clearLogin = LoginState.clearCookie();
        if (!limiter.tryAcquire(clientIp.of(request))) {
            return pages.page(HttpStatus.TOO_MANY_REQUESTS, Page.RATE_LIMITED, request, null, clearLogin);
        }
        Instant now = clock.instant();
        Optional<LoginState> stored = LoginState.read(request, signer, now);
        if (stored.isEmpty() || error != null || code == null || state == null || !sameValue(stored.get().state(), state)
                || consumed(state, now)) {
            return pages.page(HttpStatus.BAD_REQUEST, Page.CALLBACK_ERROR, request, null, clearLogin);
        }
        LoginState login = stored.get();
        OidcTokens tokens;
        IdTokenClaims claims;
        try {
            tokens = oidc.exchangeCode(code, login.verifier(), shellBase + "/core/auth/callback");
            availability.report(true);
            claims = idTokens.validate(tokens.idToken(), login.nonce());
        } catch (OidcException e) {
            LOG.warn("sign-in callback failed: {}", e.kind());
            if (e.kind() == OidcException.Kind.UNAVAILABLE || e.kind() == OidcException.Kind.LOST_RESPONSE) {
                availability.report(false);
                return pages.page(HttpStatus.SERVICE_UNAVAILABLE, Page.SIGN_IN_UNAVAILABLE, request, null, clearLogin);
            }
            return pages.page(HttpStatus.BAD_REQUEST, Page.CALLBACK_ERROR, request, null, clearLogin);
        }
        String ip = clientIp.of(request);
        if (login.stepUp() && !stepUpFresh(claims, now, ip)) {
            return pages.page(HttpStatus.BAD_REQUEST, Page.CALLBACK_ERROR, request, null, clearLogin);
        }
        discardPreviousSession(request);
        SessionService.Created created = sessions.create(new SessionService.Login(claims.subject(), claims.sessionId(),
                claims.displayName(), claims.locale(), claims.groups(), claims.authTime(), tokens.accessToken(),
                tokens.refreshToken(), tokens.idToken(), tokens.expiresInSeconds()), ip);
        return pages.page(HttpStatus.OK, Page.INTERSTITIAL, request, login.target(), clearLogin,
                SessionCookie.issue(created.cookieValue()));
    }

    /**
     * Ends the session and sends the browser to Keycloak's RP-initiated logout. Fetch callers (Accept: application/json)
     * get the target in the body, because a cross-origin redirect is not readable for a fetch.
     */
    @PostMapping("/core/auth/logout")
    ResponseEntity<?> logout(HttpServletRequest request) {
        SessionRecord session = SessionContext.of(request).session();
        String idToken = sessions.idToken(session);
        sessions.logout(session, clientIp.of(request));
        String signedOut = shellBase + "/core/auth/signed-out";
        String target = idToken == null ? signedOut : oidc.endSessionUri(idToken, signedOut).toString();
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains(MediaType.APPLICATION_JSON_VALUE)) {
            return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, SessionCookie.clear())
                    .header(HttpHeaders.CACHE_CONTROL, "no-store").body(Map.of("redirectUrl", target));
        }
        return ResponseEntity.status(HttpStatus.SEE_OTHER).header(HttpHeaders.SET_COOKIE, SessionCookie.clear())
                .header(HttpHeaders.CACHE_CONTROL, "no-store").header(HttpHeaders.LOCATION, target).build();
    }

    @GetMapping("/core/auth/signed-out")
    ResponseEntity<String> signedOut(HttpServletRequest request) {
        return pages.page(HttpStatus.OK, Page.SIGNED_OUT, request, null);
    }

    @GetMapping("/core/auth/popup-done")
    ResponseEntity<String> popupDone(HttpServletRequest request) {
        return pages.page(HttpStatus.OK, Page.POPUP_DONE, request, null);
    }

    /**
     * Back-channel logout from Keycloak (internal network only). Invalid or replayed tokens answer 400, an unknown session
     * answers 200 without effect.
     */
    @PostMapping(path = "/core/internal/backchannel-logout", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    ResponseEntity<Void> backchannelLogout(@RequestParam(name = "logout_token", required = false) String logoutToken) {
        if (logoutToken == null || logoutToken.length() > 8192) {
            return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
        LogoutClaims claims;
        try {
            claims = logoutTokens.validate(logoutToken);
        } catch (OidcException e) {
            LOG.warn("back-channel logout token rejected: {}", e.kind());
            HttpStatus status = e.kind() == OidcException.Kind.UNAVAILABLE ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.BAD_REQUEST;
            return ResponseEntity.status(status).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
        try {
            Optional<Integer> ended = sessions.applyLogoutToken(claims.jti(), claims.sessionId(), claims.subject());
            if (ended.isEmpty()) {
                LOG.warn("back-channel logout token replayed");
                return ResponseEntity.badRequest().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
            }
        } catch (DataAccessException | TransactionException e) {
            LOG.error("session store unavailable during back-channel logout", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header(HttpHeaders.CACHE_CONTROL, "no-store").build();
        }
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
    }

    private boolean stepUpFresh(IdTokenClaims claims, Instant now, String ip) {
        boolean fresh = claims.authTime() != null
                && claims.authTime().plus(properties.session().stepUpMaxAge()).isAfter(now);
        audit.record(new AuditEvent(AuditTypes.STEP_UP, claims.subject(), ip, null,
                fresh ? AuditEvent.Outcome.SUCCESS : AuditEvent.Outcome.FAILURE, null));
        return fresh;
    }

    /** A new login replaces the session of the same browser; the old row is removed without a second audit entry. */
    private void discardPreviousSession(HttpServletRequest request) {
        String cookie = SessionCookie.read(request);
        if (cookie != null) {
            sessions.resolve(cookie).ifPresent(sessions::discard);
        }
    }

    private boolean consumed(String state, Instant now) {
        consumedStates.values().removeIf(t -> t.plus(LoginState.LIFETIME).isBefore(now));
        return consumedStates.putIfAbsent(state, now) != null;
    }

    private static boolean sameValue(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
