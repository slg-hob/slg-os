package de.systemlogistics.slgos.core.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.systemlogistics.slgos.core.config.SlgProperties;
import de.systemlogistics.slgos.core.security.oidc.IdTokenValidator;
import de.systemlogistics.slgos.core.security.oidc.OidcClient;
import de.systemlogistics.slgos.core.testsupport.TestEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.support.TransactionTemplate;

/** Token renewal under a row lock (PRD-0003 SK-3 AC6, SK-20 AC1): one grant, lost responses, rotation, stalled Keycloak. */
class TokenRefresherIT extends SessionTestSupport {

    @Autowired
    TransactionTemplate tx;

    @Autowired
    Clock clock;

    @Autowired
    SlgProperties properties;

    @Autowired
    MeterRegistry meters;

    @Autowired
    TokenVault vault;

    @AfterEach
    void restoreKeycloak() {
        ENV.keycloak.failRefreshWith(0);
        ENV.keycloak.delayRefresh(Duration.ZERO);
        ENV.keycloak.restart();
    }

    private String storedAccessToken(byte[] hash) {
        SessionStore.StoredTokens t = store.readTokens(hash).orElseThrow();
        return vault.open(t.keyId(), t.accessToken(), SessionService.PURPOSE_ACCESS, hash);
    }

    /** A task that knows which of the parallel callers it is. */
    private interface IndexedTask<T> {
        T run(int index) throws Exception;
    }

    private <T> List<T> inParallel(int threads, IndexedTask<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int index = i;
            futures.add(pool.submit(() -> {
                start.await();
                return task.run(index);
            }));
        }
        start.countDown();
        List<T> results = new ArrayList<>();
        for (Future<T> f : futures) {
            results.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdownNow();
        return results;
    }

    @Test
    void aFreshTokenIsReturnedWithoutCallingKeycloak() {
        Created created = createSession(newSub());
        int attempts = ENV.keycloak.refreshAttempts.get();
        String token = refresher.freshAccessToken(created.hash());
        assertThat(token).isEqualTo(created.tokens().accessToken());
        assertThat(ENV.keycloak.refreshAttempts.get()).isEqualTo(attempts);
    }

    @Test
    void aTokenWithinTheMarginIsRenewedOnceAndTheGroupsComeFromTheRenewedToken() {
        String sub = newSub();
        Created created = createSession(sub, "/slg/users");
        ENV.keycloak.setGroups(sub, "/slg/admins", "/slg/users");
        int grants = ENV.keycloak.refreshGrants.get();
        ENV.clock.advance(Duration.ofSeconds(241));
        String renewed = refresher.freshAccessToken(created.hash());
        assertThat(renewed).isNotEqualTo(created.tokens().accessToken());
        assertThat(ENV.keycloak.refreshGrants.get()).isEqualTo(grants + 1);
        assertThat(storedAccessToken(created.hash())).isEqualTo(renewed);
        assertThat(sessions.resolve(created.session().cookieValue()).orElseThrow().groups())
                .containsExactly("/slg/admins", "/slg/users");
        assertThat(refresher.freshAccessToken(created.hash())).as("now fresh again").isEqualTo(renewed);
        assertThat(ENV.keycloak.refreshGrants.get()).isEqualTo(grants + 1);
    }

    @Test
    void justOutsideTheMarginNothingIsRenewed() {
        Created created = createSession(newSub());
        int attempts = ENV.keycloak.refreshAttempts.get();
        ENV.clock.advance(Duration.ofSeconds(239));
        refresher.freshAccessToken(created.hash());
        assertThat(ENV.keycloak.refreshAttempts.get()).isEqualTo(attempts);
    }

    @Test
    void manyThreadsOfOneContainerCauseExactlyOneGrant() throws Exception {
        Created created = createSession(newSub());
        ENV.keycloak.delayRefresh(Duration.ofMillis(300));
        int grants = ENV.keycloak.refreshGrants.get();
        ENV.clock.advance(Duration.ofSeconds(280));
        List<String> tokens = inParallel(12, i -> refresher.freshAccessToken(created.hash()));
        assertThat(ENV.keycloak.refreshGrants.get()).isEqualTo(grants + 1);
        assertThat(Set.copyOf(tokens)).hasSize(1);
        assertThat(sessions.resolve(created.session().cookieValue())).as("the session survived").isPresent();
    }

    @Test
    void twoContainersAgainstOneDatabaseCauseExactlyOneGrant() throws Exception {
        Created created = createSession(newSub());
        TokenRefresher second = new TokenRefresher(store, vault, oidc, idTokens, sessions, tx, clock, properties, meters);
        ENV.keycloak.delayRefresh(Duration.ofMillis(500));
        int grants = ENV.keycloak.refreshGrants.get();
        ENV.clock.advance(Duration.ofSeconds(280));
        List<String> tokens = inParallel(8, i -> (i % 2 == 0 ? refresher : second)
                .freshAccessToken(created.hash()));
        assertThat(ENV.keycloak.refreshGrants.get()).as("one grant for both containers").isEqualTo(grants + 1);
        assertThat(Set.copyOf(tokens)).hasSize(1);
        assertThat(sessions.resolve(created.session().cookieValue())).isPresent();
    }

    @Test
    void theOtherContainerGetsExactlyTheLockHoldersResultEvenWhenItsOwnCallIsFirst() throws Exception {
        Created created = createSession(newSub());
        TokenRefresher second = new TokenRefresher(store, vault, oidc, idTokens, sessions, tx, clock, properties, meters);
        ENV.keycloak.delayRefresh(Duration.ofMillis(600));
        int grants = ENV.keycloak.refreshGrants.get();
        ENV.clock.advance(Duration.ofSeconds(290));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Future<String> first = pool.submit(() -> refresher.freshAccessToken(created.hash()));
        Thread.sleep(200);
        Future<String> other = pool.submit(() -> second.freshAccessToken(created.hash()));
        assertThat(other.get(30, TimeUnit.SECONDS)).isEqualTo(first.get(30, TimeUnit.SECONDS));
        pool.shutdownNow();
        assertThat(ENV.keycloak.refreshGrants.get()).isEqualTo(grants + 1);
    }

    @Test
    void aLostRefreshResponseEndsTheSessionWithAnAuditReason() {
        String sub = newSub();
        Created created = createSession(sub);
        ENV.keycloak.dropNextRefreshResponse();
        ENV.clock.advance(Duration.ofSeconds(290));
        assertThatThrownBy(() -> refresher.freshAccessToken(created.hash())).isInstanceOf(SessionGoneException.class);
        assertThat(sessionRows(sub)).isZero();
        assertThat(reasons(sub)).containsExactly("refresh_lost");
        assertThatThrownBy(() -> refresher.freshAccessToken(created.hash())).isInstanceOf(SessionGoneException.class);
    }

    @Test
    void aRefusedRefreshEndsTheSessionWithAnAuditReason() {
        String sub = newSub();
        Created created = createSession(sub);
        ENV.keycloak.revokeAllRefreshTokens();
        ENV.clock.advance(Duration.ofSeconds(290));
        assertThatThrownBy(() -> refresher.freshAccessToken(created.hash())).isInstanceOf(SessionGoneException.class);
        assertThat(reasons(sub)).containsExactly("refresh_rejected");
        assertThat(meters.get("slg_token_refresh_failures_total").counter().count()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void anUnreachableKeycloakKeepsTheSessionAndTheCachedTokenWorksUntilItExpires() {
        String sub = newSub();
        Created created = createSession(sub);
        ENV.keycloak.stop();
        ENV.clock.advance(Duration.ofSeconds(100));
        assertThat(refresher.freshAccessToken(created.hash())).as("still valid beyond the margin").isEqualTo(created.tokens().accessToken());
        ENV.clock.advance(Duration.ofSeconds(160));
        assertThatThrownBy(() -> refresher.freshAccessToken(created.hash())).isInstanceOf(AuthUnavailableException.class);
        assertThat(sessionRows(sub)).as("the session is kept").isEqualTo(1);
        assertThat(reasons(sub)).isEmpty();
        ENV.keycloak.restart();
        assertThat(refresher.freshAccessToken(created.hash())).isNotEqualTo(created.tokens().accessToken());
    }

    @Test
    void aServerErrorFromKeycloakKeepsTheSession() {
        String sub = newSub();
        Created created = createSession(sub);
        ENV.keycloak.failRefreshWith(503);
        ENV.clock.advance(Duration.ofSeconds(290));
        assertThatThrownBy(() -> refresher.freshAccessToken(created.hash())).isInstanceOf(AuthUnavailableException.class);
        assertThat(sessionRows(sub)).isEqualTo(1);
        ENV.keycloak.failRefreshWith(0);
        assertThat(refresher.freshAccessToken(created.hash())).isNotNull();
    }

    @Test
    void aStalledKeycloakDoesNotExhaustTheConnectionPool() throws Exception {
        Created stalled = createSession(newSub());
        Created other = createSession(newSub());
        ENV.keycloak.delayRefresh(Duration.ofSeconds(2));
        ENV.clock.advance(Duration.ofSeconds(290));
        ExecutorService pool = Executors.newFixedThreadPool(40);
        List<Future<String>> waiting = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            waiting.add(pool.submit(() -> refresher.freshAccessToken(stalled.hash())));
        }
        Thread.sleep(500);
        long begin = System.nanoTime();
        for (int i = 0; i < 30; i++) {
            assertThat(sessions.resolve(other.session().cookieValue())).isPresent();
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin);
        assertThat(millis).as("other sessions stay responsive while 40 requests wait for Keycloak (pool of 6)").isLessThan(1500);
        for (Future<String> f : waiting) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isNotNull();
        }
        pool.shutdownNow();
        assertThat(ENV.keycloak.refreshGrants.get()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void renewalsOfManyDifferentSessionsAreCappedSoTheOtherRequestsKeepTheirConnections() throws Exception {
        List<Created> stalled = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            stalled.add(createSession(newSub()));
        }
        Created other = createSession(newSub());
        ENV.keycloak.delayRefresh(Duration.ofMillis(1200));
        ENV.clock.advance(Duration.ofSeconds(290));
        sessions.touch(sessions.resolve(other.session().cookieValue()).orElseThrow(), true);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<String>> futures = stalled.stream().map(c -> pool.submit(() -> refresher.freshAccessToken(c.hash())))
                .collect(Collectors.toList());
        Thread.sleep(400);
        long begin = System.nanoTime();
        for (int i = 0; i < 30; i++) {
            assertThat(sessions.resolve(other.session().cookieValue())).isPresent();
            sessions.touch(sessions.resolve(other.session().cookieValue()).orElseThrow(), true);
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin))
                .as("touching and reading other sessions is never blocked by renewals, which hold at most half of the pool")
                .isLessThan(1500);
        for (Future<String> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isNotNull();
        }
        pool.shutdownNow();
    }

    @Test
    void aTouchOfTheSameSessionIsNotBlockedWhileItsRenewalWaitsForKeycloak() throws Exception {
        Created created = createSession(newSub());
        ENV.keycloak.delayRefresh(Duration.ofMillis(1500));
        ENV.clock.advance(Duration.ofSeconds(290));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<String> renewal = pool.submit(() -> refresher.freshAccessToken(created.hash()));
        Thread.sleep(400);
        long begin = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            sessions.touch(sessions.resolve(created.session().cookieValue()).orElseThrow(), true);
        }
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - begin)).as("the row is not locked by the renewal").isLessThan(1000);
        assertThat(renewal.get(30, TimeUnit.SECONDS)).isNotNull();
        pool.shutdownNow();
    }

    @Test
    void unreadableVaultDataEndsTheSessionWithAnAuditReasonInsteadOfFailing() {
        String sub = newSub();
        Created created = createSession(sub);
        db.sql("UPDATE session SET vault_key_id = 'retired' WHERE user_id = ?").param(sub).update();
        assertThatThrownBy(() -> refresher.freshAccessToken(created.hash())).isInstanceOf(SessionGoneException.class);
        assertThat(reasons(sub)).containsExactly("vault_unreadable");

        String sub2 = newSub();
        Created expired = createSession(sub2);
        ENV.clock.advance(Duration.ofSeconds(290));
        db.sql("UPDATE session SET vault_key_id = 'retired' WHERE user_id = ?").param(sub2).update();
        assertThatThrownBy(() -> refresher.freshAccessToken(expired.hash())).isInstanceOf(SessionGoneException.class);
        assertThat(reasons(sub2)).containsExactly("vault_unreadable");
    }

    @Test
    void aSessionDeletedDuringTheRenewalIsReportedAsGoneAndNotRecreated() throws Exception {
        String sub = newSub();
        Created created = createSession(sub);
        ENV.keycloak.delayRefresh(Duration.ofMillis(800));
        ENV.clock.advance(Duration.ofSeconds(290));
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<String> renewal = pool.submit(() -> refresher.freshAccessToken(created.hash()));
        Thread.sleep(300);
        sessions.logout(sessions.resolve(created.session().cookieValue()).orElseThrow(), "10.0.0.1");
        assertThatThrownBy(() -> renewal.get(30, TimeUnit.SECONDS)).hasCauseInstanceOf(SessionGoneException.class);
        assertThat(sessionRows(sub)).isZero();
        pool.shutdownNow();
    }

    @Test
    void keyRotationKeepsOldSessionsReadableAndSealsRenewedTokensWithTheNewKey() throws Exception {
        String sub = newSub();
        Created created = createSession(sub);
        assertThat(db.sql("SELECT vault_key_id FROM session WHERE user_id = ?").param(sub).query(String.class).single()).isEqualTo("k1");
        Map<String, byte[]> keys = Map.of("k1", Base64.getDecoder().decode(TestEnvironment.VAULT_KEY_1),
                "k2", Base64.getDecoder().decode(TestEnvironment.VAULT_KEY_2));
        TokenVault rotated = new TokenVault(keys, "k2");
        TokenRefresher rotatedRefresher = new TokenRefresher(store, rotated, oidc, idTokens, sessions, tx, clock, properties, meters);
        ENV.clock.advance(Duration.ofSeconds(290));
        String renewed = rotatedRefresher.freshAccessToken(created.hash());
        assertThat(renewed).isNotEqualTo(created.tokens().accessToken());
        assertThat(db.sql("SELECT vault_key_id FROM session WHERE user_id = ?").param(sub).query(String.class).single()).isEqualTo("k2");
        SessionStore.StoredTokens t = store.readTokens(created.hash()).orElseThrow();
        assertThat(rotated.open("k2", t.accessToken(), SessionService.PURPOSE_ACCESS, created.hash())).isEqualTo(renewed);
        assertThat(new String(t.refreshToken(), StandardCharsets.ISO_8859_1)).doesNotContain("refresh-");
        TokenVault oldConfiguration = new TokenVault(Map.of("k1", keys.get("k1")), "k1");
        assertThatThrownBy(() -> oldConfiguration.open("k2", t.accessToken(), SessionService.PURPOSE_ACCESS, created.hash()))
                .as("a container that still has only the old key cannot read data of the new key")
                .hasMessageContaining("unknown vault key");
    }

    @Test
    void aSessionThatDisappearedIsReportedAsGone() {
        assertThatThrownBy(() -> refresher.freshAccessToken(SessionIds.hash("no-such-session"))).isInstanceOf(SessionGoneException.class);
    }

    @Test
    void renewalsOfDifferentSessionsRunIndependently() throws Exception {
        List<Created> all = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            all.add(createSession(newSub()));
        }
        int grants = ENV.keycloak.refreshGrants.get();
        ENV.clock.advance(Duration.ofSeconds(290));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Future<String>> futures = all.stream().map(c -> pool.submit(() -> refresher.freshAccessToken(c.hash())))
                .collect(Collectors.toList());
        for (Future<String> f : futures) {
            assertThat(f.get(30, TimeUnit.SECONDS)).isNotNull();
        }
        pool.shutdownNow();
        assertThat(ENV.keycloak.refreshGrants.get()).isEqualTo(grants + 4);
    }
}
