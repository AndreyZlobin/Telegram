package org.telegram.messenger;

import android.content.SharedPreferences;

import org.json.JSONObject;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

/** TGLock runs inside this process. The user's regular proxy stays untouched. */
public final class TgLockController {
    private static final String ENABLED = "tglock_enabled";
    private static final DispatchQueue queue = new DispatchQueue("TGLockController");
    private static volatile boolean busy;
    private static volatile boolean failed;
    private static boolean loaded;
    private static String lastDiagnostics = "";
    private static long defaultNetworkHandle = -1;
    private static final Runnable reconnectForNetwork = () -> {
        if (!isEnabled()) {
            return;
        }
        busy = true;
        notifyChanged();
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        try {
            // Route cooldown and open sockets belong to the previous network.
            // A fresh engine retries reachable routes immediately on Wi-Fi/VPN changes.
            stop();
            startOrThrow();
            applySavedProxy(preferences);
            failed = false;
        } catch (Throwable error) {
            failed = true;
            FileLog.e(error);
            // Preserve the opt-in and fail closed if the listener cannot restart.
            try {
                applySavedProxy(preferences);
            } catch (Throwable applyError) {
                FileLog.e(applyError);
            }
        } finally {
            busy = false;
            notifyChanged();
        }
    };

    private TgLockController() { }

    /** Called only when Android selects a different default network. */
    public static synchronized void onNetworkAvailable(long handle) {
        boolean changed = defaultNetworkHandle != -1 && defaultNetworkHandle != handle;
        defaultNetworkHandle = handle;
        if (changed) {
            queue.cancelRunnable(reconnectForNetwork);
            queue.postRunnable(reconnectForNetwork, 500);
        }
    }

    public static boolean isEnabled() {
        return MessagesController.getGlobalMainSettings().getBoolean(ENABLED, false);
    }

    public static boolean isBusy() {
        return busy;
    }

    private static synchronized ProxySettings startOrThrow() throws Exception {
        if (!loaded) {
            System.loadLibrary("tglock_android");
            loaded = true;
        }
        JSONObject endpoint = new JSONObject(nativeStart());
        int port = endpoint.getInt("port");
        String secret = endpoint.getString("secret");
        if (port <= 0 || port > 65535 || !secret.matches("dd[0-9a-f]{32}")) {
            throw new IllegalStateException("Invalid TGLock endpoint");
        }
        return ProxySettings.builder().setType(ProxySettings.Type.MTPROTO)
                .setAddress("127.0.0.1").setPort(port).setSecret(secret).build();
    }

    /** Fail closed after a persisted opt-in: never silently switch to direct. */
    public static ProxySettings getProxySettings() {
        try {
            return startOrThrow();
        } catch (Throwable error) {
            failed = true;
            FileLog.e(error);
            return ProxySettings.builder().setType(ProxySettings.Type.MTPROTO)
                    .setAddress("127.0.0.1").setPort(9)
                    .setSecret("dd00000000000000000000000000000000").build();
        }
    }

    private static synchronized void stop() {
        if (loaded) {
            nativeStop();
        }
    }

    public static void setEnabled(boolean enabled) {
        if (busy) {
            return;
        }
        busy = true;
        notifyChanged();
        queue.postRunnable(() -> {
            final SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            final boolean previous = isEnabled();
            try {
                if (enabled) {
                    startOrThrow();
                }
                if (!preferences.edit().putBoolean(ENABLED, enabled).commit()) {
                    throw new IllegalStateException("Unable to save TGLock setting");
                }
                applySavedProxy(preferences);
                if (!enabled) {
                    stop();
                }
                failed = false;
            } catch (Throwable error) {
                failed = true;
                FileLog.e(error);
                preferences.edit().putBoolean(ENABLED, previous).commit();
                try {
                    applySavedProxy(preferences);
                    if (!previous) {
                        stop();
                    }
                } catch (Throwable restoreError) {
                    FileLog.e(restoreError);
                }
            } finally {
                busy = false;
                notifyChanged();
            }
        });
    }

    private static void applySavedProxy(SharedPreferences preferences) {
        ConnectionsManager.setProxySettings(preferences.getBoolean("proxy_enabled", false),
                ProxySettings.fromSharedPreferences(preferences));
    }

    private static void notifyChanged() {
        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getGlobalInstance()
                .postNotificationName(NotificationCenter.proxySettingsChanged));
    }

    public static String getStatusText(int connectionState) {
        if (busy) {
            return LocaleController.getString(R.string.TgLockSwitching);
        }
        if (failed) {
            return LocaleController.getString(isEnabled() ? R.string.TgLockError : R.string.TgLockStartError);
        }
        if (!isEnabled()) {
            return LocaleController.getString(R.string.TgLockInfo);
        }
        try {
            JSONObject status;
            synchronized (TgLockController.class) {
                status = new JSONObject(nativeStatus());
            }
            if (BuildConfig.FORK_DIAGNOSTICS) {
                String diagnostic = status.toString();
                if (!diagnostic.equals(lastDiagnostics)) {
                    // Route state only: no endpoint secret, API credentials or message data.
                    android.util.Log.i("TGLockDiag", diagnostic);
                    lastDiagnostics = diagnostic;
                }
            }
            if (!status.optBoolean("running")) {
                return LocaleController.getString(R.string.TgLockError);
            }
            if (status.optInt("tunnels") > 0 && (connectionState == ConnectionsManager.ConnectionStateConnected
                    || connectionState == ConnectionsManager.ConnectionStateUpdating)) {
                return LocaleController.getString(R.string.TgLockConnected);
            }
            return LocaleController.getString(R.string.TgLockConnecting);
        } catch (Throwable error) {
            return LocaleController.getString(R.string.TgLockError);
        }
    }

    private static native String nativeStart();
    private static native void nativeStop();
    private static native String nativeStatus();
}
