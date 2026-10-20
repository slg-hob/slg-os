package de.systemlogistics.slgos.core.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;

class SessionServiceIT extends SessionTestSupport {

    private String cookieOf(Created created) {
        return created.session().cookieValue();
    }

    @Test
    void createWritesOneRowAndOneLoginAuditEntry() {
        String sub = newSub();
        Created created = createSession(sub, "/slg/users");
        assertThat(sessionRows(sub)).isEqualTo(1);
        assertThat(auditCount("auth.login", sub)).isEqualTo(1);
        SessionRecord record = sessions.resolve(cookieOf(created)).orElseThrow();
        assertThat(record.userId()).isEqualTo(sub);
        assertThat(record.groups()).containsExactly("/slg/users");
        assertThat(record.sid()).isEqualTo(created.claims().sessionId());
        assertThat(record.authTime()).isNotNull();
    }

    @Test
    void theNewestSessionsWinWhenTheCapIsReached() {
        String sub = newSub();
        Created[] all = new Created[12];
        for (int i = 0; i < all.length; i++) {
            ENV.clock.advance(Duration.ofSeconds(1));
            all[i] = createSession(sub);
        }
        assertThat(sessionRows(sub)).isEqualTo(10);
        assertThat(sessions.resolve(cookieOf(all[0]))).isEmpty();
        assertThat(sessions.resolve(cookieOf(all[1]))).isEmpty();
        assertThat(sessions.resolve(cookieOf(all[2]))).isPresent();
        assertThat(sessions.resolve(cookieOf(all[11]))).as("the new session itself is never dropped").isPresent();
        assertThat(reasons(sub)).containsExactly("session_cap", "session_cap");
        assertThat(auditCount("auth.login", sub)).isEqualTo(12);
    }

    @Test
    void theCapNeverDropsTheNewSessionEvenWithEqualTimestamps() {
        String sub = newSub();
        Created last = null;
        for (int i = 0; i < 11; i++) {
            last = createSession(sub);
        }
        assertThat(sessions.resolve(cookieOf(last))).isPresent();
        assertThat(sessionRows(sub)).isEqualTo(10);
    }

    @Test
    void idleAndAbsoluteTimeoutsEndTheSessionOnceWithTheRightReason() {
        String idle = newSub();
        String absolute = newSub();
        Created a = createSession(idle);
        Created b = createSession(absolute);
        ENV.clock.advance(Duration.ofMinutes(29));
        sessions.touch(sessions.resolve(cookieOf(b)).orElseThrow(), false);
        ENV.clock.advance(Duration.ofMinutes(2));
        assertThat(sessions.resolve(cookieOf(a))).isEmpty();
        assertThat(sessions.resolve(cookieOf(a))).isEmpty();
        assertThat(reasons(idle)).containsExactly("idle_timeout");

        for (int i = 0; i < 22; i++) {
            ENV.clock.advance(Duration.ofMinutes(25));
            sessions.touch(sessions.resolve(cookieOf(b)).orElseThrow(), true);
        }
        ENV.clock.advance(Duration.ofMinutes(25));
        assertThat(sessions.resolve(cookieOf(b))).isEmpty();
        assertThat(reasons(absolute)).containsExactly("absolute_timeout");
    }

    @Test
    void purgeEndsExpiredSessionsAndOldReplayMarkers() {
        String stale = newSub();
        String live = newSub();
        Created s = createSession(stale);
        Created l = createSession(live);
        sessions.applyLogoutToken("old-jti-" + stale, "unknown-sid", null);
        ENV.clock.advance(Duration.ofMinutes(29));
        sessions.touch(sessions.resolve(cookieOf(l)).orElseThrow(), true);
        ENV.clock.advance(Duration.ofMinutes(2));
        sessions.purgeExpired();
        assertThat(sessionRows(stale)).isZero();
        assertThat(sessionRows(live)).isEqualTo(1);
        assertThat(reasons(stale)).containsExactly("idle_timeout");
        assertThat(s.session().cookieValue()).isNotNull();
        assertThat(db.sql("SELECT count(*) FROM logout_jti WHERE jti = ?").param("old-jti-" + stale).query(Long.class).single())
                .as("recent marker stays").isEqualTo(1);
        ENV.clock.advance(Duration.ofHours(2));
        sessions.purgeExpired();
        assertThat(db.sql("SELECT count(*) FROM logout_jti WHERE jti = ?").param("old-jti-" + stale).query(Long.class).single())
                .isZero();
    }

    @Test
    void theTimeoutsEndExactlyAtTheirBoundary() {
        String sub = newSub();
        Created created = createSession(sub);
        ENV.clock.advance(Duration.ofMinutes(30).minusMillis(1));
        assertThat(sessions.resolve(cookieOf(created))).as("1 ms before the idle timeout").isPresent();
        ENV.clock.advance(Duration.ofMillis(1));
        assertThat(sessions.resolve(cookieOf(created))).as("exactly at the idle timeout").isEmpty();
        assertThat(reasons(sub)).containsExactly("idle_timeout");

        String other = newSub();
        Created kept = createSession(other);
        for (int i = 0; i < 20; i++) {
            ENV.clock.advance(Duration.ofMinutes(29));
            sessions.touch(sessions.resolve(cookieOf(kept)).orElseThrow(), true);
        }
        ENV.clock.advance(Duration.ofMinutes(20).minusMillis(1));
        assertThat(sessions.resolve(cookieOf(kept))).as("1 ms before 10 hours").isPresent();
        ENV.clock.advance(Duration.ofMillis(1));
        assertThat(sessions.resolve(cookieOf(kept))).as("exactly 10 hours").isEmpty();
        assertThat(reasons(other)).containsExactly("absolute_timeout");
    }

    @Test
    void purgeUsesTheAbsoluteReasonForSessionsThatOutlivedTenHours() {
        String sub = newSub();
        Created created = createSession(sub);
        for (int i = 0; i < 20; i++) {
            ENV.clock.advance(Duration.ofMinutes(29));
            sessions.touch(sessions.resolve(cookieOf(created)).orElseThrow(), true);
        }
        ENV.clock.advance(Duration.ofMinutes(20).plusMillis(1));
        sessions.purgeExpired();
        assertThat(sessionRows(sub)).isZero();
        assertThat(reasons(sub)).containsExactly("absolute_timeout");
    }

    @Test
    void theLatestSessionIgnoresSessionsPastTheirTimeouts() {
        String sub = newSub();
        createSession(sub, "/slg/admins");
        assertThat(sessions.latestOfUser(sub)).isPresent();
        ENV.clock.advance(Duration.ofMinutes(31));
        assertThat(sessions.latestOfUser(sub)).as("idle for 31 minutes: no permissions any more").isEmpty();
    }

    @Test
    void aLogoutTokenWithASidAlsoEndsSessionsStoredWithoutOne() {
        String sub = newSub();
        Created created = createSession(sub);
        db.sql("UPDATE session SET sid = NULL WHERE user_id = ?").param(sub).update();
        assertThat(sessions.applyLogoutToken("jti-null-sid-" + sub, "any-sid", sub)).contains(1);
        assertThat(sessions.resolve(cookieOf(created))).isEmpty();
    }

    @Test
    void touchWritesAtMostEveryThirtySecondsUnlessForced() {
        String sub = newSub();
        Created created = createSession(sub);
        SessionRecord first = sessions.resolve(cookieOf(created)).orElseThrow();
        ENV.clock.advance(Duration.ofSeconds(10));
        sessions.touch(first, false);
        assertThat(sessions.resolve(cookieOf(created)).orElseThrow().lastAccess()).isEqualTo(first.lastAccess());
        sessions.touch(first, true);
        assertThat(sessions.resolve(cookieOf(created)).orElseThrow().lastAccess()).isAfter(first.lastAccess());
        SessionRecord second = sessions.resolve(cookieOf(created)).orElseThrow();
        ENV.clock.advance(Duration.ofSeconds(31));
        sessions.touch(second, false);
        assertThat(sessions.resolve(cookieOf(created)).orElseThrow().lastAccess()).isAfter(second.lastAccess());
    }

    @Test
    void malformedCookieValuesNeverReachTheDatabase() {
        for (String bad : new String[] {null, "", "x", "!!!!!!!!!!!!!!!!!!!!!!", "AAAAAAAAAAAAAAAAAAAAAAA"}) {
            assertThat(sessions.resolve(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    void logoutWritesOneEntryAndDecryptsTheIdTokenForTheHint() {
        String sub = newSub();
        Created created = createSession(sub);
        SessionRecord record = sessions.resolve(cookieOf(created)).orElseThrow();
        assertThat(sessions.idToken(record)).isEqualTo(created.tokens().idToken());
        sessions.logout(record, "10.0.0.1");
        sessions.logout(record, "10.0.0.1");
        assertThat(auditCount("auth.logout", sub)).as("a second logout of a gone session writes nothing").isEqualTo(1);
        assertThat(sessions.idToken(record)).isNull();
        assertThat(sessions.resolve(cookieOf(created))).isEmpty();
    }

    @Test
    void discardRemovesTheRowWithoutAnAuditEntry() {
        String sub = newSub();
        Created created = createSession(sub);
        sessions.discard(sessions.resolve(cookieOf(created)).orElseThrow());
        assertThat(sessionRows(sub)).isZero();
        assertThat(reasons(sub)).isEmpty();
        assertThat(auditCount("auth.logout", sub)).isZero();
    }

    @Test
    void concurrentTerminationsWriteExactlyOneAuditEntry() throws Exception {
        String sub = newSub();
        Created created = createSession(sub);
        SessionRecord record = sessions.resolve(cookieOf(created)).orElseThrow();
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<?>[] threads = new CompletableFuture<?>[6];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = CompletableFuture.runAsync(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                sessions.terminate(record, "revoked");
            });
        }
        start.countDown();
        CompletableFuture.allOf(threads).get();
        assertThat(reasons(sub)).containsExactly("revoked");
    }

    @Test
    void backChannelLogoutEndsTheSessionsOfTheKeycloakSessionOnceAndRejectsReplays() {
        String sub = newSub();
        Created created = createSession(sub);
        String sid = created.claims().sessionId();
        Optional<Integer> first = sessions.applyLogoutToken("jti-" + sub, sid, null);
        assertThat(first).contains(1);
        assertThat(sessions.resolve(cookieOf(created))).isEmpty();
        assertThat(reasons(sub)).containsExactly("backchannel_logout");
        assertThat(sessions.applyLogoutToken("jti-" + sub, sid, null)).as("replay").isEmpty();
        assertThat(sessions.applyLogoutToken("jti-other-" + sub, "sid-that-does-not-exist", null)).as("unknown sid").contains(0);
    }

    @Test
    void backChannelLogoutWithOnlyASubjectEndsAllSessionsOfTheUser() {
        String sub = newSub();
        createSession(sub);
        createSession(sub);
        assertThat(sessions.applyLogoutToken("jti-sub-" + sub, null, sub)).contains(2);
        assertThat(sessionRows(sub)).isZero();
        assertThat(reasons(sub)).containsExactly("backchannel_logout", "backchannel_logout");
    }

    @Test
    void theLatestSessionOfAUserIsTheMostRecentlyUsedOne() {
        String sub = newSub();
        Created older = createSession(sub, "/slg/a");
        ENV.clock.advance(Duration.ofSeconds(5));
        Created newer = createSession(sub, "/slg/b");
        assertThat(sessions.latestOfUser(sub).orElseThrow().groups()).containsExactly("/slg/b");
        ENV.clock.advance(Duration.ofSeconds(60));
        sessions.touch(sessions.resolve(cookieOf(older)).orElseThrow(), true);
        assertThat(sessions.latestOfUser(sub).orElseThrow().groups()).containsExactly("/slg/a");
        assertThat(newer.session().cookieValue()).isNotNull();
        assertThat(sessions.latestOfUser("nobody-" + sub)).isEmpty();
    }

    @Test
    void countReportsLiveSessions() {
        long before = sessions.count();
        createSession(newSub());
        assertThat(sessions.count()).isEqualTo(before + 1);
    }
}
