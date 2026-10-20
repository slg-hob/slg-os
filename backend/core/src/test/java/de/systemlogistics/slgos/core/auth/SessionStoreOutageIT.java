package de.systemlogistics.slgos.core.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import de.systemlogistics.slgos.core.testsupport.Browser;
import de.systemlogistics.slgos.core.testsupport.IntegrationTestBase;
import de.systemlogistics.slgos.core.testsupport.TestDatabase;
import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The session store (PostgreSQL) is unavailable: 503 SESSION_STORE_DOWN, never an unauthenticated fallback, and recovery
 * without a restart of the core (PRD-0003 SK-5 AC5, SK-18 AC4).
 */
class SessionStoreOutageIT extends IntegrationTestBase {

    private MockHttpServletResponse call(Browser browser, String path) throws Exception {
        return mvc.perform(browser.shell(get(path))).andReturn().getResponse();
    }

    @Test
    void answers503WithoutFallbackAndRecoversWhenTheDatabaseReturns() throws Exception {
        Browser browser = new Browser(mvc, ENV.keycloak, ENV.clock);
        browser.login("outage-user-" + System.nanoTime(), "/slg/admins");
        try (Connection admin = ENV.db.admin(); Statement st = admin.createStatement()) {
            st.execute("ALTER ROLE " + TestDatabase.APP_USER + " NOLOGIN");
            st.execute("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE usename = '" + TestDatabase.APP_USER + "'");
            try {
                for (String path : new String[] {"/core/api/v1/session", "/app/reference", "/modules/reference/api/v1/notes"}) {
                    MockHttpServletResponse response = call(browser, path);
                    assertThat(response.getStatus()).as(path).isEqualTo(503);
                    assertThat(response.getContentAsString()).as(path).contains("\"code\":\"SESSION_STORE_DOWN\"");
                    assertThat(response.getHeader("Location")).as("no redirect to the login").isNull();
                }
                MockHttpServletResponse logout = mvc.perform(post("/core/internal/backchannel-logout").header("Host", "core-backend")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("logout_token", ENV.keycloak.sign(ENV.keycloak.logoutClaims("some-sid", null).build())))
                        .andReturn().getResponse();
                assertThat(logout.getStatus()).as("Keycloak retries a 503").isEqualTo(503);
            } finally {
                st.execute("ALTER ROLE " + TestDatabase.APP_USER + " LOGIN");
            }
        }
        int status = 0;
        for (int attempt = 0; attempt < 20 && status != 200; attempt++) {
            status = call(browser, "/core/api/v1/session").getStatus();
            if (status != 200) {
                Thread.sleep(500);
            }
        }
        assertThat(status).as("the same session works again without a restart").isEqualTo(200);
    }
}
