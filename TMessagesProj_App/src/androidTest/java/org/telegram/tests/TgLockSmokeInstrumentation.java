package org.telegram.tests;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.SharedPreferences;
import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONObject;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.TgLockController;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

/** Account-free instrumentation: real JNI, listener, toggle and proxy restore. */
public class TgLockSmokeInstrumentation extends Instrumentation {
    private static final String[] KEYS = {"tglock_enabled", "proxy_enabled", "proxy_ip", "proxy_port",
            "proxy_type", "proxy_user", "proxy_pass", "proxy_secret"};

    @Override
    public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        start();
    }

    @Override
    public void onStart() {
        Bundle result = new Bundle();
        try {
            runOnMainSync(ApplicationLoader::postInitApplication);
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                require(!UserConfig.getInstance(a).isClientActivated(), "Use an emulator without a signed-in account");
            }
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                ConnectionsManager manager = ConnectionsManager.getInstance(a);
                require(!manager.isTestBackend(), "Test backend survived startup migration");
                manager.switchBackend(false);
            }
            SystemClock.sleep(300);
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                require(!ConnectionsManager.getInstance(a).isTestBackend(), "Test backend can still be enabled");
            }
            runOnMainSync(() -> {});
            require(!getTargetContext().getSharedPreferences("logininfo2", Context.MODE_PRIVATE)
                    .contains("tglock_test_backend_fixture"), "Pending test login survived migration");
            testToggleAndRestore();
            result.putString("result", "PASS: production backend, disabled backend switch, JNI, loopback listener, blocked non-Telegram target, toggle, proxy restore");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void testToggleAndRestore() throws Exception {
        SharedPreferences prefs = MessagesController.getGlobalMainSettings();
        Map<String, ?> before = new HashMap<>(prefs.getAll());
        SharedConfig.ProxyInfo oldProxy = SharedConfig.currentProxy;
        boolean wasEnabled = TgLockController.isEnabled();
        try {
            toggle(false);
            ProxySettings previous = ProxySettings.builder().setType(ProxySettings.Type.SOCKS5)
                    .setAddress("127.0.0.1").setPort(9).setUser("previous-proxy").build();
            SharedPreferences.Editor editor = prefs.edit();
            previous.toSharedPreferences(editor);
            require(editor.putBoolean("proxy_enabled", true).commit(), "Unable to store fixture");
            SharedConfig.currentProxy = new SharedConfig.ProxyInfo(previous);

            toggle(true);
            require(TgLockController.isEnabled(), "Enable flag was not set");
            ProxySettings local = TgLockController.getProxySettings();
            require(local.getType() == ProxySettings.Type.MTPROTO && local.getAddress().equals("127.0.0.1")
                    && local.getPort() > 0 && local.getPort() != 9, "Native listener missing");
            require(previous.equals(ProxySettings.fromSharedPreferences(prefs)), "Saved proxy was overwritten");
            Method status = TgLockController.class.getDeclaredMethod("nativeStatus");
            status.setAccessible(true);
            require(new JSONObject((String) status.invoke(null)).getBoolean("running"), "Native engine is not running");
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", local.getPort()), 2000);
                socket.setSoTimeout(3000);
                socket.getOutputStream().write(new byte[]{5, 1, 0});
                require(socket.getInputStream().read() == 5 && socket.getInputStream().read() == 0, "SOCKS greeting failed");
                socket.getOutputStream().write(new byte[]{5, 1, 0, 1, (byte) 192, 0, 2, 1, 0, 80});
                require(socket.getInputStream().read() == 5 && socket.getInputStream().read() != 0,
                        "Non-Telegram target was not blocked");
            }
            toggle(false);
            require(!TgLockController.isEnabled(), "Disable flag was not set");
            require(!new JSONObject((String) status.invoke(null)).getBoolean("running"), "Native engine did not stop");
            require(prefs.getBoolean("proxy_enabled", false)
                    && previous.equals(ProxySettings.fromSharedPreferences(prefs)), "Previous proxy was not restored");
            toggle(true);
            require(TgLockController.getProxySettings().getPort() != 9, "Second start failed");
            toggle(false);
        } finally {
            SharedPreferences.Editor editor = prefs.edit();
            for (String key : KEYS) {
                Object value = before.get(key);
                if (value == null) editor.remove(key);
                else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof String) editor.putString(key, (String) value);
            }
            editor.commit();
            SharedConfig.currentProxy = oldProxy;
            toggle(wasEnabled);
        }
    }

    private void toggle(boolean enabled) throws Exception {
        runOnMainSync(() -> TgLockController.setEnabled(enabled));
        long deadline = SystemClock.elapsedRealtime() + 10000;
        while (TgLockController.isBusy() && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20);
        }
        require(!TgLockController.isBusy(), "Toggle timed out");
    }

    private static void require(boolean condition, String message) throws Exception {
        if (!condition) throw new Exception(message);
    }
}
