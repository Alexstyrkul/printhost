package dev.oleksandr.printhost;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the service after boot. Same pattern as dev.oleksandr.usbtap: on the very first boot
 *  right after installation, PackageManager may not have finished indexing the new package yet,
 *  so this can silently no-op once - a second reboot picks it up normally, that's expected. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent serviceIntent = new Intent(context, PrinterService.class);
            context.startForegroundService(serviceIntent);
        }
    }
}
