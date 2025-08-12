package com.example.seavodevice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 一个广播接收器，用于在设备启动完成后自动启动应用主界面
 */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            Intent mainIntent = new Intent(context, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(mainIntent);
        }
    }
}