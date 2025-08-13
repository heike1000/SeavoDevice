package com.example.seavodevice;
//adb shell dpm set-device-owner com.example.seavodevice/.DeviceAdminReceiver
//adb shell dpm remove-active-admin com.example.seavodevice/.DeviceAdminReceiver

import android.annotation.SuppressLint;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.os.UserManager;
import android.provider.Settings;
import android.widget.ListView;
import android.widget.ProgressBar;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.util.concurrent.atomic.AtomicInteger;

public class MainActivity extends AppCompatActivity {
    public static String fwVersion;
    public static String serialNumber;
    public static AtomicInteger operatingTasks = new AtomicInteger(0);
    private OperationManager operationManager;
    public static volatile String isOnline = "0";
    public static volatile String kiosk = "0";
    public static String kioskAppPackage = null;
    public static final int mainLoopIntervalAsy = 20;//单位：秒
    public static final int mainLoopIntervalSyn = 5;//单位：秒
    public static String limitation = "1";

    @SuppressLint("HardwareIds")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        //获取设备唯一标识和系统版本
        serialNumber = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        fwVersion = Build.DISPLAY;
        //初始化UI组件
        ListView listViewResult = findViewById(R.id.ListViewResult);
        ProgressBar progressBarResult = findViewById(R.id.ProgressBarResult);
        ProgressBar progressBarMemory = findViewById(R.id.ProgressBarMemory);
        //获取设备策略管理服务
        DevicePolicyManager devicePolicyManager = (DevicePolicyManager) getSystemService(Context.DEVICE_POLICY_SERVICE);
        ComponentName deviceAdminComponent = new ComponentName(this, DeviceAdminReceiver.class);
        //初始化操作控制器
        operationManager = new OperationManager(this, this, devicePolicyManager, deviceAdminComponent, listViewResult, progressBarResult, progressBarMemory);
        //请求忽略电池优化、获取位置
        boolean isDeviceOwner = devicePolicyManager.isDeviceOwnerApp(getPackageName());
        boolean hasLocationPermission = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        boolean hasPackageUsageStatsPermission = operationManager.isUsageStatsPermissionGranted();
        boolean isIgnoringBatteryOptimizations = getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(getPackageName());
        //检查必须权限
        if (isDeviceOwner && hasLocationPermission && isIgnoringBatteryOptimizations && hasPackageUsageStatsPermission) {
            //根据设备长宽锁定方向
            operationManager.setScreenOrientation();
            //禁用屏保，限制安装/卸载
            operationManager.setRestriction();
            //启动前台服务
            operationManager.startForegroundService();
            //注册安装结果广播接收器
            LocalBroadcastManager.getInstance(this).registerReceiver(ResultReceiver, new IntentFilter(InstallAndUninstallResultReceiver.ACTION_INSTALL_DELETION_RESULT));
            //开始获取位置
            operationManager.setupLocationListener();
            //初始化设备
            operationManager.initializeDevice();
            //主循环
            operationManager.mainLoop();
        } else {
            operationManager.requestPermission();
            if (!isDeviceOwner) {
                operationManager.addToResultList("Need permission: Device owner");
            }
            if (!hasLocationPermission) {
                operationManager.addToResultList("Need permission: Grant location");
            }
            if (!isIgnoringBatteryOptimizations) {
                operationManager.addToResultList("Need permission: Ignore battery optimizations");
            }
            if (!hasPackageUsageStatsPermission) {
                operationManager.addToResultList("Need permission: Package usage stats");
            }
        }
    }


    @Override
    protected void onDestroy() {
        operationManager.stopMainLoop();
        Intent serviceIntent = new Intent(this, ForegroundService.class);
        stopService(serviceIntent);
        LocalBroadcastManager.getInstance(this).unregisterReceiver(ResultReceiver);
        super.onDestroy();
    }

    //安装结果接收器，处理APK安装结果回调
    private final BroadcastReceiver ResultReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            DevicePolicyManager devicePolicyManager = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
            ComponentName deviceAdminComponent = new ComponentName(context, DeviceAdminReceiver.class);
            devicePolicyManager.addUserRestriction(deviceAdminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
            devicePolicyManager.addUserRestriction(deviceAdminComponent, UserManager.DISALLOW_INSTALL_APPS);
            String resultText = intent.getStringExtra(InstallAndUninstallResultReceiver.EXTRA_RESULT_TEXT);
            operationManager.addToResultList(resultText);
        }
    };
}