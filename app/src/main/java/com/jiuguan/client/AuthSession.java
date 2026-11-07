package com.jiuguan.client;

import java.util.HashSet;
import java.util.Set;

/** Authentication state only; no Android or storage dependency. */
public final class AuthSession {
    public static final class Submission {
        public final String scope, username, password;
        public final boolean remember, autoLogin;

        Submission(String scope, String username, String password, boolean remember, boolean autoLogin) {
            this.scope = scope;
            this.username = username;
            this.password = password;
            this.remember = remember;
            this.autoLogin = remember && autoLogin;
        }
    }

    private final Set<String> attempted = new HashSet<>();
    private String origin;
    private Submission pending;
    private boolean failed;
    private boolean committed;
    private boolean finished;

    public void start(String address) {
        origin = AuthScope.origin(address);
        attempted.clear();
        invalidate();
        failed = false;
    }

    public boolean challenge(String scope) {
        boolean rejected = pending != null && pending.scope.equals(scope);
        invalidate();
        return rejected;
    }

    public boolean tryAutomatic(String scope) {
        return belongsToSession(scope) && attempted.add(scope);
    }

    public void submit(String scope, String username, String password, boolean remember, boolean autoLogin) {
        if (!belongsToSession(scope)) throw new IllegalArgumentException("认证已失效，请重新打开酒馆");
        attempted.add(scope);
        pending = new Submission(scope, username, password, remember, autoLogin);
        failed = false;
        committed = false;
        finished = false;
    }

    public Submission pageCommitted(String url) {
        if (pending != null && matchesOrigin(url)) committed = true;
        return accepted();
    }

    public Submission finish(String url) {
        if (pending != null && matchesOrigin(url)) finished = true;
        return accepted();
    }

    private Submission accepted() {
        if (failed || !committed || !finished) return null;
        Submission result = pending;
        invalidate();
        return result;
    }

    public void fail() { failed = true; invalidate(); }
    public void invalidate() { pending = null; committed = false; finished = false; }

    private boolean belongsToSession(String scope) {
        return origin != null && scope != null && scope.startsWith(origin + "\n");
    }

    private boolean matchesOrigin(String url) {
        try { return origin != null && origin.equals(AuthScope.origin(url)); }
        catch (IllegalArgumentException e) { return false; }
    }
}
