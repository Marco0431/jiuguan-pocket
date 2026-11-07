package com.jiuguan.client;
import org.junit.Test;
import static org.junit.Assert.*;

public class PluginControlStateTest {
    @Test public void enableWaitsForLoginAndDoesNotLoopAfterFailure() {
        PluginControlState state = new PluginControlState();
        state.target("http://pc:8000", "proxy-one", true);
        assertNull(state.next(false));
        PluginControlState.Operation operation = state.next(true);
        assertTrue(operation.enable());
        assertNull(state.next(true));
        state.complete(operation, false, "missing plugin");
        assertFalse(state.mobileConfirmed());
        assertNull(state.next(true));
        state.retry(); assertNotNull(state.next(true));
    }
    @Test public void lateEnableCannotUndoOffMode() {
        PluginControlState state = new PluginControlState();
        state.target("http://pc:8000", "proxy-one", true);
        PluginControlState.Operation enable = state.next(true);
        state.target("http://pc:8000", "", true);
        assertFalse(state.current(enable));
        assertFalse(state.complete(enable, true, ""));
        PluginControlState.Operation disable = state.next(true);
        assertFalse(disable.enable());
        assertTrue(state.disablePending());
        state.complete(disable, true, "");
        assertFalse(state.disablePending());
        assertFalse(state.mobileConfirmed());
    }
    @Test public void newNetworkAndNewComputerRejectOldReplies() {
        PluginControlState state = new PluginControlState();
        state.target("http://pc:8000", "proxy-one", true);
        PluginControlState.Operation old = state.next(true);
        state.target("http://pc:8000", "proxy-two", true);
        assertFalse(state.complete(old, true, ""));
        PluginControlState.Operation fresh = state.next(true);
        assertTrue(state.complete(fresh, true, ""));
        assertTrue(state.mobileConfirmed());
        state.target("http://pc2:8000", "proxy-two", true);
        assertFalse(state.mobileConfirmed());
        assertFalse(state.current(fresh));
    }
    @Test public void normalModeWithoutPreviousUseDoesNotContactPlugin() {
        PluginControlState state = new PluginControlState();
        state.target("http://pc:8000", "", false);
        assertNull(state.next(true));
        state.target("http://pc:8000", "", true);
        PluginControlState.Operation disable = state.next(true);
        state.complete(disable, false, "offline");
        assertTrue(state.disablePending());
        assertNull(state.next(true));
    }
    @Test public void statusContractDoesNotRequireOkAndDoesNotEchoRemoteMessages() {
        assertEquals("", PluginControlState.statusProblem(1, "normal", true, ""));
        assertEquals("", PluginControlState.statusProblem(1, "mobile", true, ""));
        assertFalse(PluginControlState.statusProblem(2, "normal", true, "").isEmpty());
        assertFalse(PluginControlState.statusProblem(1, "unexpected", true, "").isEmpty());
        assertTrue(PluginControlState.statusProblem(1, "normal", false, "需要 Node.js 24.14+。 ") .contains("Node.js"));
        assertFalse(PluginControlState.statusProblem(1, "normal", false, "secret token data") .contains("secret"));
    }
}
