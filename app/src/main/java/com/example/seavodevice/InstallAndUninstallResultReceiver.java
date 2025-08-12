package com.example.seavodevice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;

/**
 * 安装和卸载结果接收器，用于处理APK安装/卸载操作的结果回调
 * 接收系统广播并处理安装状态，完成后发送本地广播通知UI更新
 */
public class InstallAndUninstallResultReceiver extends BroadcastReceiver {
    public static final String ACTION_INSTALL_DELETION_RESULT = "com.example.seavodevice.INSTALL_DELETION_RESULT";
    public static final String EXTRA_RESULT_TEXT = "result_text";

    @Override
    public void onReceive(Context context, Intent intent) {
        String resultText;
        String apkPath = intent.getStringExtra("apk_path");
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        String packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME);
        switch (status) {
            case PackageInstaller.STATUS_PENDING_USER_ACTION:
                if (apkPath != null) {
                    deleteApkFile(apkPath);
                }
                resultText = "Permission denied.";
                break;
            case PackageInstaller.STATUS_SUCCESS:
                if (apkPath != null) {
                    deleteApkFile(apkPath);
                }
                resultText = "Succeeded in altering: " + packageName;
                break;
            default:
                if (apkPath != null) {
                    deleteApkFile(apkPath);
                }
                String errorMsg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                resultText = "Altering failure (Code: " + status + "): " + errorMsg;
                break;
        }
        if (MainActivity.operatingTasks.get() != 0) {
            MainActivity.operatingTasks.decrementAndGet();
        }
        Intent resultIntent = new Intent(ACTION_INSTALL_DELETION_RESULT);
        resultIntent.putExtra(EXTRA_RESULT_TEXT, resultText);
        LocalBroadcastManager.getInstance(context).sendBroadcast(resultIntent);
    }

    private void deleteApkFile(String apkPath) {
        try {
            File apkFile = new File(apkPath);
            if (apkFile.exists()) {
                boolean deleted = apkFile.delete();
                Log.i("SeavoDevice", "删除APK文件" + (deleted ? "成功" : "失败"));
            }
        } catch (SecurityException e) {
            Log.e("SeavoDevice", "删除APK文件时出现安全异常: " + e.getMessage());
        }
    }
}
