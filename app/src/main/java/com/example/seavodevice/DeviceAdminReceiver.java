package com.example.seavodevice;

import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;

/**
 * 设备管理员接收器，用于处理设备管理权限的启用/禁用状态变化
 */
public class DeviceAdminReceiver extends android.app.admin.DeviceAdminReceiver {
    @Override
    public void onEnabled(@NonNull Context context, @NonNull Intent intent) {
        super.onEnabled(context, intent);
    }

    @Override
    public void onDisabled(@NonNull Context context, @NonNull Intent intent) {
        super.onDisabled(context, intent);
    }
}
