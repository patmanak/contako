package com.patmanak.contako.hostile;

import android.accounts.Account;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

/** Deliberately separate-UID fixture. It emits status names only, never payloads. */
public final class HostileProbeReceiver extends BroadcastReceiver {
    private static final String TAG = "ContakoHostileProbe";
    private static final String TARGET = "com.patmanak.contako";
    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {}
        @Override public void onServiceDisconnected(ComponentName name) {}
    };

    @Override public void onReceive(Context context, Intent ignored) {
        int passed = 0;
        passed += expectSecurity("AUTH_BIND", () -> context.bindService(
                serviceIntent("com.patmanak.contako.android.account.ContakoAccountAuthenticatorService",
                        "android.accounts.AccountAuthenticator"), CONNECTION, Context.BIND_AUTO_CREATE));
        passed += expectSecurity("SYNC_BIND", () -> context.bindService(
                serviceIntent("com.patmanak.contako.android.sync.ContakoContactsSyncAdapterService",
                        "android.content.SyncAdapter"), CONNECTION, Context.BIND_AUTO_CREATE));
        passed += expectSecurity("AUTH_START", () -> context.startService(
                serviceIntent("com.patmanak.contako.android.account.ContakoAccountAuthenticatorService",
                        "android.accounts.AccountAuthenticator")));
        passed += expectSecurity("SYNC_SETTINGS", () -> {
            ContentResolver.setSyncAutomatically(
                    new Account("synthetic-account", "com.patmanak.contako.account"),
                    "com.android.contacts", true);
            return true;
        });
        passed += expectProviderRejected(context);
        passed += expectLauncherOnly(context);
        if (passed != 6) {
            Log.e(TAG, "RESULT=FAIL passed=" + passed + " expected=6");
        } else {
            Log.i(TAG, "RESULT=PASS cases=6");
        }
    }

    private static Intent serviceIntent(String className, String action) {
        return new Intent(action).setComponent(new ComponentName(TARGET, className))
                .putExtra("account", "spoofed")
                .putExtra("malformed", new byte[] { 0, -1, 0 });
    }

    private static int expectSecurity(String name, Operation operation) {
        try {
            Object result = operation.run();
            if (result instanceof Boolean && !((Boolean) result)) {
                Log.i(TAG, name + "=REJECTED");
                return 1;
            }
            Log.e(TAG, name + "=UNEXPECTED_SUCCESS");
            return 0;
        } catch (SecurityException expected) {
            Log.i(TAG, name + "=SECURITY_EXCEPTION");
            return 1;
        } catch (RuntimeException rejected) {
            Log.i(TAG, name + "=REJECTED");
            return 1;
        }
    }

    private static int expectProviderRejected(Context context) {
        try (Cursor cursor = context.getContentResolver().query(
                Uri.parse("content://com.patmanak.contako.private/canary"), null, null, null, null)) {
            Log.e(TAG, "PRIVATE_PROVIDER=UNEXPECTED_SUCCESS");
            return 0;
        } catch (IllegalArgumentException | SecurityException expected) {
            Log.i(TAG, "PRIVATE_PROVIDER=REJECTED");
            return 1;
        }
    }

    private static int expectLauncherOnly(Context context) {
        Intent malformed = new Intent(Intent.ACTION_MAIN)
                .setComponent(new ComponentName(TARGET, "com.patmanak.contako.MainActivity"))
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("account", "spoofed")
                .putExtra("auth", new Bundle());
        try {
            context.startActivity(malformed);
            Log.i(TAG, "LAUNCHER_MALFORMED_EXTRAS=IGNORED");
            return 1;
        } catch (RuntimeException rejected) {
            Log.i(TAG, "LAUNCHER_MALFORMED_EXTRAS=REJECTED");
            return 1;
        }
    }

    private interface Operation { Object run(); }
}
