package com.jiuguan.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class StartupPolicyTest {
    private static final String URL = "http://192.168.1.10:8000/";

    @Test public void firstLaunchWithoutSuccessStaysInSettingsAcrossResumes() {
        StartupPolicy policy = new StartupPolicy(false);
        assertFalse(policy.claim(true, true, false, false, false));
        assertTrue(policy.considered());
        // Even if setup later succeeds, reopening the Activity is not a new auto attempt.
        assertFalse(policy.claim(true, true, false, false, true));
    }

    @Test public void successfulSetupAutomaticallyOpensOnlyOnce() {
        StartupPolicy policy = new StartupPolicy(false);
        assertTrue(policy.claim(true, true, false, false, true));
        // Failure, cancellation and timeout leave the same launch consumed.
        assertFalse(policy.claim(true, true, false, false, true));
        assertFalse(policy.claim(false, true, false, false, true));
        assertFalse(policy.claim(true, true, false, false, true));
    }

    @Test public void waitsForForegroundAndServiceInEitherOrder() {
        StartupPolicy bindingFirst = new StartupPolicy(false);
        assertFalse(bindingFirst.claim(false, true, false, false, true));
        assertFalse(bindingFirst.considered());
        assertTrue(bindingFirst.claim(true, true, false, false, true));

        StartupPolicy foregroundFirst = new StartupPolicy(false);
        assertFalse(foregroundFirst.claim(true, false, false, false, true));
        assertFalse(foregroundFirst.considered());
        assertTrue(foregroundFirst.claim(true, true, false, false, true));
    }

    @Test public void retainedSessionAndExplicitSettingsPreventAutomaticNavigation() {
        StartupPolicy retained = new StartupPolicy(false);
        assertFalse(retained.claim(true, true, false, true, true));
        assertFalse(retained.claim(true, true, false, false, true));
        StartupPolicy settings = new StartupPolicy(false);
        assertFalse(settings.claim(true, true, true, false, true));
        assertFalse(settings.claim(true, true, false, false, true));
    }

    @Test public void manualActionAndRecreatedActivityCannotRepeatTheAttempt() {
        StartupPolicy manual = new StartupPolicy(false);
        manual.consume();
        assertFalse(manual.claim(true, true, false, false, true));
        StartupPolicy restored = new StartupPolicy(manual.considered());
        assertFalse(restored.claim(true, true, false, false, true));
    }

    @Test public void successMustMatchAddressAndBothConnectionOptions() {
        assertTrue(StartupPolicy.hasMatchingSuccess(URL, true, true, URL, true, true));
        assertFalse(StartupPolicy.hasMatchingSuccess(URL, false, true, URL, true, true));
        assertFalse(StartupPolicy.hasMatchingSuccess(URL, true, false, URL, true, true));
        assertFalse(StartupPolicy.hasMatchingSuccess(URL + "other", true, true, URL, true, true));
        assertFalse(StartupPolicy.hasMatchingSuccess(URL + "?profile=new", true, true, URL, true, true));
        assertFalse(StartupPolicy.hasMatchingSuccess(URL + "#other", true, true, URL, true, true));
        assertFalse(StartupPolicy.hasMatchingSuccess("https://192.168.1.10:8000/", true, true, URL, true, true));
    }

    @Test public void equivalentHostAndDefaultPortAreAcceptedWithoutIgnoringPath() {
        assertTrue(StartupPolicy.hasMatchingSuccess(" EXAMPLE.test ", false, true,
                "http://example.test:80/", false, true));
        assertTrue(StartupPolicy.hasMatchingSuccess("https://EXAMPLE.test/tavern/?p=1#chat", false, true,
                "https://example.test:443/tavern/?p=1#chat", false, true));
        assertFalse(StartupPolicy.hasMatchingSuccess("https://example.test/Tavern/", false, true,
                "https://example.test/tavern/", false, true));
    }

    @Test public void missingOrInvalidSavedConfigurationNeverEnablesAutoOpen() {
        for (String bad : new String[] {null, "", " ", "file:///tmp/test", "http://user:secret@example.test", "http://bad host"}) {
            assertFalse(StartupPolicy.hasMatchingSuccess(URL, false, true, bad, false, true));
            assertFalse(StartupPolicy.hasMatchingSuccess(bad, false, true, URL, false, true));
        }
    }
}
