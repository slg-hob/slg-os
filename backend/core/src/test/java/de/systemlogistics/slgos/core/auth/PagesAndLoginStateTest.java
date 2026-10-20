package de.systemlogistics.slgos.core.auth;

import static org.assertj.core.api.Assertions.assertThat;

import de.systemlogistics.slgos.core.auth.PreSessionPages.Page;
import de.systemlogistics.slgos.core.security.CsrfTokens;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockHttpServletRequest;

class PagesAndLoginStateTest {

    private final PreSessionPages pages = new PreSessionPages();
    private final CsrfTokens signer = new CsrfTokens("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private final Instant now = Instant.parse("2026-10-20T12:00:00Z");

    // ---- language

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "en-GB,en;q=0.8,de;q=0.5|en", "de-DE,de;q=0.9,en;q=0.8|de", "fr-FR,fr;q=0.9|de", "en;q=0.2,de;q=0.9|de",
            "en;q=0,de;q=0.1|de", "EN|en", "*|de", "en;q=abc|de", "xx,en;q=0.5|en", "''|de"})
    void picksTheBestSupportedLanguageAndDefaultsToGerman(String header, String expected) {
        assertThat(PreSessionPages.pickLanguage(header)).isEqualTo(expected);
    }

    @Test
    void noHeaderMeansGerman() {
        assertThat(PreSessionPages.pickLanguage(null)).isEqualTo("de");
    }

    // ---- pages

    @ParameterizedTest
    @EnumSource(Page.class)
    void everyPageHasLanguageTitleAndTextInBothLanguages(Page page) {
        for (String lang : new String[] {"de", "en"}) {
            String html = pages.render(page, lang, "/app/reference");
            assertThat(html).startsWith("<!doctype html>").contains("<html lang=\"" + lang + "\">", "<h1>", "<main", "</html>")
                    .contains("name=\"viewport\"", "name=\"color-scheme\"", "/core/auth/assets/pages.css")
                    .doesNotContain("null", "<style", "style=\"", "onclick");
        }
        assertThat(pages.render(page, "de", "/app/x")).isNotEqualTo(pages.render(page, "en", "/app/x"));
    }

    @Test
    void unknownLanguagesFallBackToGerman() {
        assertThat(pages.render(Page.SIGNED_OUT, "fr", null)).contains("<html lang=\"de\">").contains("Abgemeldet");
    }

    @Test
    void errorPagesAnnounceThemselvesAsAlerts() {
        for (Page page : new Page[] {Page.CALLBACK_ERROR, Page.SIGN_IN_UNAVAILABLE, Page.RATE_LIMITED}) {
            assertThat(pages.render(page, "en", null)).contains("role=\"alert\"");
        }
        assertThat(pages.render(Page.SIGNED_OUT, "en", null)).doesNotContain("role=\"alert\"");
    }

    @Test
    void theInterstitialRefreshesToTheTargetAndShowsAVisibleLink() {
        String html = pages.render(Page.INTERSTITIAL, "en", "/app/reference/notes");
        assertThat(html).contains("<meta http-equiv=\"refresh\" content=\"0;url=/app/reference/notes\">")
                .contains("<a href=\"/app/reference/notes\">Continue to SLG OS</a>");
    }

    @Test
    void theTargetIsEscapedEvenThoughItWasValidatedBefore() {
        String html = pages.render(Page.INTERSTITIAL, "en", "/app/x\"><script>alert(1)</script>");
        assertThat(html).doesNotContain("<script>alert").contains("&quot;&gt;&lt;script&gt;");
        assertThat(PreSessionPages.escape("a&b<c>\"d'")).isEqualTo("a&amp;b&lt;c&gt;&quot;d&#39;");
    }

    @Test
    void thePopupPageLoadsItsScriptFromTheSameOriginAndOffersAVisibleButton() {
        String html = pages.render(Page.POPUP_DONE, "en", null);
        assertThat(html).contains("<script src=\"/core/auth/assets/popup-done.js\"></script>")
                .contains("<button type=\"button\" id=\"close-window\">Close this window</button>");
    }

    @Test
    void onlyThePopupPageIncludesAScript() {
        for (Page page : Page.values()) {
            if (page != Page.POPUP_DONE) {
                assertThat(pages.render(page, "en", "/app/x")).as(page.name()).doesNotContain("<script");
            }
        }
    }

    // ---- pre-login cookie

    private LoginState state(boolean popup, boolean stepUp) {
        return new LoginState("st-1", "no-1", "ve-1", "/app/reference/notes", popup, stepUp, now);
    }

    private Optional<LoginState> roundTrip(String setCookie, Instant at) {
        String value = setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(LoginState.COOKIE, value));
        return LoginState.read(request, signer, at);
    }

    @Test
    void theLoginCookieHasTheRequiredAttributes() {
        String cookie = state(false, false).toSetCookie(signer);
        assertThat(cookie).startsWith("__Host-slg-login=").contains("Path=/", "Secure", "HttpOnly", "SameSite=Lax", "Max-Age=600")
                .doesNotContain("Domain");
    }

    @Test
    void theStateSurvivesTheRoundTripIncludingFlags() {
        assertThat(roundTrip(state(true, false).toSetCookie(signer), now)).contains(state(true, false));
        assertThat(roundTrip(state(false, true).toSetCookie(signer), now.plusSeconds(599))).contains(state(false, true));
    }

    @Test
    void anExpiredOrFutureCookieIsIgnored() {
        String cookie = state(false, false).toSetCookie(signer);
        assertThat(roundTrip(cookie, now.plusSeconds(601))).isEmpty();
        assertThat(roundTrip(cookie, now.minusSeconds(120))).isEmpty();
    }

    @Test
    void aForgedOrAlteredCookieIsIgnored() {
        String cookie = state(false, false).toSetCookie(signer);
        String value = cookie.substring(cookie.indexOf('=') + 1, cookie.indexOf(';'));
        String altered = value.replaceFirst("st-1", "st-2");
        assertThat(LoginState.parse(altered, signer, now)).isEmpty();
        CsrfTokens otherKey = new CsrfTokens("fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8));
        assertThat(LoginState.parse(value, otherKey, now)).isEmpty();
        for (String junk : new String[] {"", "nodot", "a.b", "a.b.c.d.e.f.g", "....." , value + "x", value.substring(1)}) {
            assertThat(LoginState.parse(junk, signer, now)).as(junk).isEmpty();
        }
    }

    @Test
    void aCsrfSignatureCannotBeReusedAsALoginSignature() {
        byte[] payload = "st.no.ve.dGFyZ2V0.00.1".getBytes(StandardCharsets.UTF_8);
        assertThat(Arrays.equals(signer.sign("csrf", payload), signer.sign("login", payload))).isFalse();
    }

    @Test
    void noCookieAtAllMeansNoState() {
        assertThat(LoginState.read(new MockHttpServletRequest(), signer, now)).isEmpty();
        MockHttpServletRequest other = new MockHttpServletRequest();
        other.setCookies(new Cookie("unrelated", "x"));
        assertThat(LoginState.read(other, signer, now)).isEmpty();
    }

    @Test
    void clearingTheCookieExpiresItImmediately() {
        assertThat(LoginState.clearCookie()).startsWith("__Host-slg-login=;").contains("Max-Age=0", "Secure", "Path=/");
    }
}
