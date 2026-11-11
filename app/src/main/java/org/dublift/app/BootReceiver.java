package org.dublift.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            // Also clean up when the user stopped the server, without requiring another app launch.
            ApkUpdateManager.clear(context);
        }
        if (DubLiftService.isEnabled(context)) {
            Intent service = new Intent(context, DubLiftService.class).setAction(DubLiftService.ACTION_START);
            AndroidCompat.startServer(context, service);
        }
    }
}
