package com.jiuguan.client;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionStateTest {
    private static final String URL = "http://192.168.1.10:8000";

    @Test public void networkWaitAndPageLoadShareOneDeadline() {
        ConnectionState state = new ConnectionState();
        long attempt = state.begin(URL, 1000);
        state.waitingNetwork("等待移动数据");
        assertEquals(16000, state.deadline());
        state.loading(attempt);
        assertEquals(16000, state.deadline());
        assertFalse(state.timeOut(attempt, 15999));
        assertTrue(state.timeOut(attempt, 16000));
        assertFalse(state.isConnecting());
        assertFalse(state.hasLoadedPage());
    }
    @Test public void finishedDocumentSucceedsWithoutAVisibleCommit() {
        ConnectionState state = new ConnectionState();
        long attempt = state.begin(URL, 0);
        state.loading(attempt); state.pageStarted(URL + "/");
        assertTrue(state.pageFinished(URL));
        assertTrue(state.hasLoadedPage());
        assertFalse(state.timeOut(attempt, 20000));
        assertFalse(state.fail(attempt, "late error"));
        assertFalse(state.cancel());
        assertTrue(state.hasLoadedPage());
    }
    @Test public void authWaitPausesAndSubmissionGetsAFreshDeadline() {
        ConnectionState state = new ConnectionState();
        long attempt = state.begin(URL, 0);
        state.loading(attempt); state.pageStarted(URL);
        assertTrue(state.pauseForAuth());
        assertFalse(state.timeOut(attempt, 60000));
        assertFalse(state.pageFinished(URL));
        assertTrue(state.resumeAfterAuth(60000));
        assertEquals(75000, state.deadline());
        assertTrue(state.pageFinished(URL));
    }
    @Test public void canceledAndFailedPagesCannotBecomeLoaded() {
        ConnectionState state = new ConnectionState();
        long attempt = state.begin(URL, 0);
        state.loading(attempt); state.pageStarted(URL); state.pauseForAuth();
        assertTrue(state.cancel());
        assertFalse(state.pageFinished(URL));
        assertFalse(state.resumeAfterAuth(100));
        attempt = state.begin(URL, 100);
        state.loading(attempt); state.pageStarted(URL);
        assertTrue(state.fail(attempt, "HTTP 403"));
        assertFalse(state.pageFinished(URL));
        assertFalse(state.hasLoadedPage());
    }
    @Test public void aPreviousAttemptCannotTimeoutOrStartANewerAttempt() {
        ConnectionState state = new ConnectionState();
        long old = state.begin(URL, 0);
        state.loading(old); state.pageStarted(URL); state.cancel();
        long fresh = state.begin(URL, 20000);
        assertFalse(state.timeOut(old, 40000));
        assertFalse(state.loading(old));
        assertFalse(state.pageFinished(URL));
        assertTrue(state.loading(fresh));
        assertFalse(state.pageFinished(URL));
        state.pageStarted(URL);
        assertTrue(state.pageFinished(URL));
    }
    @Test public void redirectsRequireTheLatestMainDocumentAndIgnoreSubresourceUrls() {
        ConnectionState state = new ConnectionState();
        long attempt = state.begin(URL, 0);
        state.loading(attempt); state.pageStarted(URL);
        assertFalse(state.currentDocument(URL + "/assets/icon.png"));
        assertFalse(state.pageStarted("https://outside.example/"));
        state.pageStarted(URL + "/login");
        assertFalse(state.pageFinished(URL));
        assertTrue(state.pageFinished(URL + "/login"));
    }
    @Test public void startupCanRejectALoadedPageWhoseNetworkSetupFailed() {
        ConnectionState state = new ConnectionState();
        long attempt = state.begin(URL, 0);
        state.loading(attempt); state.pageStarted(URL); state.pageFinished(URL);
        assertFalse(state.fail(attempt, "late HTTP error"));
        assertTrue(state.hasLoadedPage());
        assertTrue(state.rejectSetup(attempt, "电脑插件连接失败"));
        assertEquals("电脑插件连接失败", state.message());
        assertFalse(state.hasLoadedPage());
        assertFalse(state.isConnecting());
        assertFalse(state.pageFinished(URL));
        assertFalse(state.rejectSetup(attempt, "late second rejection"));
        assertEquals("电脑插件连接失败", state.message());
    }
    @Test public void aStaleStartupRejectionCannotCancelANewerSuccessfulConnection() {
        ConnectionState state = new ConnectionState();
        long old = state.begin(URL, 0);
        state.loading(old); state.pageStarted(URL); state.pageFinished(URL);
        long fresh = state.begin(URL, 1000);
        state.loading(fresh); state.pageStarted(URL); state.pageFinished(URL);
        assertFalse(state.rejectSetup(old, "old startup timer"));
        assertTrue(state.hasLoadedPage());
        assertEquals("酒馆已连接", state.message());
    }
    @Test public void explicitStartupCancelStopsBothNetworkAndPausedAuthWaiting() {
        ConnectionState state = new ConnectionState();
        long attempt = state.begin(URL, 0);
        assertTrue(state.rejectSetup(attempt, "取消自动连接"));
        assertFalse(state.timeOut(attempt, 20000));
        assertFalse(state.loading(attempt));
        attempt = state.begin(URL, 30000);
        state.loading(attempt); state.pageStarted(URL); state.pauseForAuth();
        assertTrue(state.rejectSetup(attempt, "取消自动登录"));
        assertFalse(state.resumeAfterAuth(60000));
        assertFalse(state.pageFinished(URL));
    }
}
