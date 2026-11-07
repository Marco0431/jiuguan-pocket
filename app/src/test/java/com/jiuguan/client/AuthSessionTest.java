package com.jiuguan.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class AuthSessionTest {
    private static final String URL = "http://192.168.1.100:8000";
    private static final String SCOPE = AuthScope.key(URL, "192.168.1.100", "Tavern");

    @Test public void authenticationScopesSeparateSchemePortAndRealm() {
        assertEquals(AuthScope.origin("https://EXAMPLE.com/path"), AuthScope.origin("https://example.com:443"));
        assertNotEquals(SCOPE, AuthScope.key("http://192.168.1.100:8001", "192.168.1.100", "Tavern"));
        assertNotEquals(SCOPE, AuthScope.key("https://192.168.1.100:8000", "192.168.1.100", "Tavern"));
        assertNotEquals(SCOPE, AuthScope.key(URL, "192.168.1.100", "Other realm"));
        assertEquals("http://[fd00::1]:8003\nTavern", AuthScope.key("http://[fd00::1]:8003", "fd00::1", "Tavern"));
    }

    @Test(expected = IllegalArgumentException.class) public void aForeignHostCannotGetTheSavedCredentials() {
        AuthScope.key(URL, "attacker.example", "Tavern");
    }

    @Test public void wrongPasswordIsAutomaticallySubmittedOnlyOnce() {
        AuthSession session = new AuthSession();
        session.start(URL);
        assertTrue(session.tryAutomatic(SCOPE));
        session.submit(SCOPE, "user", "wrong", true, true);
        assertTrue(session.challenge(SCOPE));
        assertFalse(session.tryAutomatic(SCOPE));
        assertNull(session.finish(URL));
        session.submit(SCOPE, "user", "corrected", true, true);
        session.pageCommitted(URL);
        assertEquals("corrected", session.finish(URL).password);
        assertNull(session.finish(URL));
    }

    @Test public void savingRequiresACommittedPageAndNoHttpOrNetworkFailure() {
        AuthSession session = new AuthSession();
        session.start(URL);
        session.submit(SCOPE, "user", "password", true, true);
        assertNull(session.finish(URL));
        session.fail();
        assertNull(session.pageCommitted(URL));
        assertNull(session.finish(URL));
    }

    @Test public void pageFinishAndCommitCanArriveInEitherOrder() {
        AuthSession session = new AuthSession();
        session.start(URL);
        session.submit(SCOPE, "user", "password", true, true);
        assertNull(session.finish(URL));
        assertEquals("password", session.pageCommitted(URL).password);
        assertNull(session.finish(URL));
        assertNull(session.pageCommitted(URL));
        session.submit(SCOPE, "user", "changed", true, true);
        assertNull(session.pageCommitted(URL));
        assertEquals("changed", session.finish(URL).password);
    }

    @Test public void clearingOrNavigatingDiscardsLateSuccessCallbacks() {
        AuthSession session = new AuthSession();
        session.start(URL);
        session.submit(SCOPE, "user", "password", true, true);
        session.pageCommitted(URL);
        session.invalidate();
        assertNull(session.finish(URL));
        session.start("http://another.example:8003");
        assertFalse(session.tryAutomatic(SCOPE));
        assertNull(session.finish(URL));
    }

    @Test public void rememberingDoesNotRequireAutoLogin() {
        AuthSession session = new AuthSession();
        session.start(URL);
        session.submit(SCOPE, "user", "password", true, false);
        session.pageCommitted(URL);
        AuthSession.Submission saved = session.finish(URL);
        assertTrue(saved.remember);
        assertFalse(saved.autoLogin);
    }
}
