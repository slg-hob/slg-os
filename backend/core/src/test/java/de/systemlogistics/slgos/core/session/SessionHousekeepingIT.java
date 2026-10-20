package de.systemlogistics.slgos.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;

class SessionHousekeepingIT extends SessionTestSupport {

    @Autowired
    SessionPurger purger;

    @Autowired
    MeterRegistry meters;

    @Test
    void thePurgeJobEndsExpiredSessionsAndTheGaugeFollowsTheSessionCount() {
        String sub = newSub();
        createSession(sub);
        purger.measure();
        double live = meters.get("slg_session_active").gauge().value();
        assertThat(live).isEqualTo(sessions.count());
        ENV.clock.advance(Duration.ofMinutes(31));
        purger.purge();
        assertThat(sessionRows(sub)).isZero();
        assertThat(reasons(sub)).containsExactly("idle_timeout");
        purger.measure();
        assertThat(meters.get("slg_session_active").gauge().value()).isEqualTo(sessions.count()).isLessThan(live);
    }

    @Test
    void aRequestWithoutTheSessionFilterHasNoSessionContext() {
        assertThatThrownBy(() -> SessionContext.of(new MockHttpServletRequest())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void theSessionCookieIsHardenedAndRecognised() {
        assertThat(SessionCookie.issue("abc")).startsWith("__Host-slg-session=abc").contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/")
                .doesNotContain("Domain");
        assertThat(SessionCookie.clear()).contains("Max-Age=0");
        MockHttpServletRequest none = new MockHttpServletRequest();
        assertThat(SessionCookie.read(none)).isNull();
        none.setCookies(new jakarta.servlet.http.Cookie("other", "x"));
        assertThat(SessionCookie.read(none)).isNull();
        none.setCookies(new jakarta.servlet.http.Cookie("__Host-slg-session", "abc"));
        assertThat(SessionCookie.read(none)).isEqualTo("abc");
    }

    @Test
    void theSessionRequirementCoversExactlyTheProtectedPaths() {
        for (String path : new String[] {"/core/api/v1/session", "/core/auth/logout", "/app", "/app/x", "/modules/m/api/x",
                "/application", "/actuator/health", "/anything/else", "/core/auth/login/", "/core/auth/loginx", "/"}) {
            assertThat(SessionFilter.requiresSession(path)).as(path).isTrue();
        }
        for (String path : new String[] {"/core/auth/login", "/core/auth/callback", "/core/auth/signed-out",
                "/core/auth/popup-done", "/core/internal/backchannel-logout", "/core/internal", "/core/auth/assets/pages.css",
                "/favicon.ico"}) {
            assertThat(SessionFilter.requiresSession(path)).as(path).isFalse();
        }
    }
}
