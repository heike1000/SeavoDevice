package com.example.seavodevice;

import static android.content.Context.ACTIVITY_SERVICE;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.graphics.Point;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.UserManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.Display;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class OperationManager {
    private final Context context;
    private final Activity activity;
    private final DevicePolicyManager devicePolicyManager;
    private final ComponentName deviceAdminComponent;
    private final ArrayAdapter<String> adapter;
    private final List<String> resultList;
    private final ProgressBar progressBarResult;
    private final ProgressBar progressBarMemory;
    private final HttpManager httpManager;
    private double longitude = 0;
    private double latitude = 0;
    private String location = "0";
    private String memoryUsage = "0/0";
    private Thread mainLoopThreadAsy = null;
    private Thread mainLoopThreadSyn = null;
    private LocationManager locationManager;
    private LocationListener locationListener;

    public OperationManager(Context context,
                            Activity activity,
                            DevicePolicyManager devicePolicyManager,
                            ComponentName deviceAdminComponent,
                            ListView listViewResult,
                            ProgressBar progressBarResult,
                            ProgressBar progressBarMemory) {
        this.context = context;
        this.activity = activity;
        this.devicePolicyManager = devicePolicyManager;
        this.deviceAdminComponent = deviceAdminComponent;
        resultList = new ArrayList<>();
        adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, resultList);
        listViewResult.setAdapter(adapter);
        this.progressBarResult = progressBarResult;
        this.progressBarMemory = progressBarMemory;
        httpManager = new HttpManager();
    }

//-----------------------------------------------//

    //1 应用管理
    //功能：获取设备上所有已安装的非系统应用包名列表
    //参数：无
    //返回值：List<String>类型，包含所有用户安装的应用包名（排除系统应用），若无则返回空列表
    private List<String> getInstalledApp() {
        PackageManager pm = context.getPackageManager();
        //已在Manifest中注册权限
        @SuppressLint("QueryPermissionsNeeded") List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        List<String> userApps = new ArrayList<>();
        for (ApplicationInfo appInfo : apps) {
            if ((appInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                userApps.add(appInfo.packageName);
            }
        }
        return userApps;
    }

    //功能：使用系统PackageInstaller静默安装APK文件
    //参数：apkPath - 要安装的APK文件绝对路径
    //返回值：无（安装结果通过InstallDeleteResultReceiver广播接收）
    private void installApkWithCallback(String apkPath) {
        try {
            devicePolicyManager.clearUserRestriction(deviceAdminComponent, UserManager.DISALLOW_INSTALL_APPS);
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try (InputStream in = new FileInputStream(apkPath); OutputStream out = session.openWrite("pkg", 0, -1)) {
                byte[] buffer = new byte[1024 * 1024];
                int len;
                File apkFile = new File(apkPath);
                long totalSize = apkFile.length();
                long totalRead = 0;
                while ((len = in.read(buffer)) > 0) {
                    out.write(buffer, 0, len);
                    totalRead += len;
                    int progress = (int) (totalRead * 100 / totalSize);
                    updateProgressBar(progress, progressBarResult);
                }
            }
            addToResultList("Installing......");
            Intent intent = new Intent(context, InstallAndUninstallResultReceiver.class);
            intent.putExtra("apk_path", apkPath);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context, sessionId, intent, PendingIntent.FLAG_MUTABLE);
            session.commit(pendingIntent.getIntentSender());
        } catch (IOException e) {
            Log.e("SeavoDevice", "IO异常: " + e.getMessage());
        }
    }

    //功能：使用系统PackageInstaller静默卸载APP文件
    //参数：packageName - 要卸载的APP文件的包名
    //返回值：无（安装结果通过InstallDeleteResultReceiver广播接收）
    private void uninstallAppWithCallback(String packageName) {
        devicePolicyManager.clearUserRestriction(deviceAdminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
        PackageInstaller packageInstaller = context.getPackageManager().getPackageInstaller();
        Intent intent = new Intent(context, InstallAndUninstallResultReceiver.class);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE);
        packageInstaller.uninstall(packageName, pendingIntent.getIntentSender());
    }

    //功能：开机启动指定应用，以及是否进入kiosk模式
    //参数：appName - 要锁定的应用包名（为null时立即返回）
    //返回值：无
    private void setAppToStartOnReboot(String appName) {
        if (appName == null || appName.equals("-1")) {
            return;
        }
        if (Objects.equals(MainActivity.kiosk, "1")) {
            String[] allowedPackages = {context.getPackageName(), appName};
            devicePolicyManager.setLockTaskPackages(deviceAdminComponent, allowedPackages);
            activity.startLockTask();
        }
        if (!appName.equals(context.getPackageName())) {
            try {
                addToResultList("Starting " + appName + " in 2 seconds");
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Log.e("SeavoDevice", e.toString());
            }
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(appName);
            if (intent != null) {
                intent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                );
                context.startActivity(intent);
            }
        } else {
            addToResultList("This device is locked by admin");
        }
    }

//-----------------------------------------------//

    //2 工具方法
    //功能：更新进度条
    //参数：progress - int型变量，表示进度百分比
    //返回值：无
    private void updateProgressBar(int progress, ProgressBar progressBar) {
        if (activity != null) {
            activity.runOnUiThread(() -> progressBar.setProgress(progress));
        }
    }

    //功能：将单个操作结果添加到结果列表并刷新UI显示
    //参数：result - 要添加的结果字符串（非空才会处理）
    //返回值：无
    public void addToResultList(String result) {
        if (activity != null) {
            activity.runOnUiThread(() -> {
                if (result != null) {
                    if (resultList.size() > 25) {
                        resultList.clear();
                        adapter.notifyDataSetChanged();
                    }
                    resultList.add(result);
                    adapter.notifyDataSetChanged();
                }
            });
        }
    }

    //功能：从指定URL下载文件到应用私有存储空间/data/data/<package>/files
    //参数：fileUrl - 文件下载URL
    //返回值：File对象表示下载成功的文件，失败则返回null
    private File downloadToStorage(String fileUrl) {
        final int MAX_RETRY = 8;
        final long RETRY_INTERVAL = 3000;
        int retryCount = 0;

        while (retryCount < MAX_RETRY) {
            HttpURLConnection connection = null;
            try {
                String fileName = "app_to_install.apk";
                File downloadDir = context.getFilesDir();
                File outputFile = new File(downloadDir, fileName);
                URL url = new URL(fileUrl);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(10000);

                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(outputFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    long totalRead = 0;
                    long contentLength = connection.getContentLength();
                    while ((bytesRead = input.read(buffer)) > 0) {
                        output.write(buffer, 0, bytesRead);
                        totalRead += bytesRead;
                        int progress = (int) (totalRead * 100 / contentLength);
                        updateProgressBar(progress, progressBarResult);
                    }
                }
                return outputFile;
            } catch (Exception e) {
                retryCount++;
                Log.e("SeavoDevice", "下载异常(" + retryCount + "/" + MAX_RETRY + "): " + e.getMessage());
                if (retryCount < MAX_RETRY) {
                    try {
                        Thread.sleep(RETRY_INTERVAL);
                    } catch (InterruptedException ie) {
                        Log.e("SeavoDevice", "线程异常");
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        return null;
    }

    //功能：获取系统错误日志
    //参数：无
    //返回值：String类型，表示系统错误日志
    private String getErrorLogcat() {
        StringBuilder result = new StringBuilder();
        try {
            Process process = Runtime.getRuntime().exec("logcat -d -v time *:E *:W");
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                result.append(line).append("\n");
            }
        } catch (IOException e) {
            Log.e("SeavoDevice", "Error reading log");
        }
        return result.toString();
    }

    //功能：获取内存占用率
    //参数：无
    //返回值：String类型，格式为：已使用内存/总内存
    private String getMemoryUsage() {
        StringBuilder result = new StringBuilder();
        ActivityManager activityManager = (ActivityManager) context.getSystemService(ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
        activityManager.getMemoryInfo(memoryInfo);
        float usedPercent = 100f * (memoryInfo.totalMem - memoryInfo.availMem) / memoryInfo.totalMem;
        updateProgressBar((int) usedPercent, progressBarMemory);
        result.append(memoryInfo.availMem).append("/").append(memoryInfo.totalMem);
        return result.toString();
    }

    //功能：在线程中休眠
    //参数：无
    //返回值：无
    private void sleepInThread(int sleepTime) {
        try {
            Thread.sleep(sleepTime * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

//-----------------------------------------------//

    //3 设备初始化控制
    //功能：获取屏幕长宽
    //参数：无
    //返回值：boolean类型
    private boolean isNaturalOrientationLandscape() {
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        if (windowManager == null) return false;
        Display display = windowManager.getDefaultDisplay();
        Point size = new Point();
        display.getRealSize(size);
        return size.x > size.y;
    }

    //功能：锁定屏幕方向
    //参数：无
    //返回值：无
    public void setScreenOrientation() {
        if (isNaturalOrientationLandscape()) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        } else {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    }

    //功能：启动前台服务
    //参数：无
    //返回值：无
    public void startForegroundService() {
        Intent serviceIntent = new Intent(context, ForegroundService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent);
        } else {
            context.startService(serviceIntent);
        }
    }

    //功能：禁用屏保，禁止所有卸载/安装
    //参数：无
    //返回值：无
    public void setRestriction() {
        //禁用屏保，方便重启时直接启动软件
        devicePolicyManager.setKeyguardDisabled(deviceAdminComponent, true);
        //禁止所有卸载/安装
        devicePolicyManager.addUserRestriction(deviceAdminComponent, UserManager.DISALLOW_UNINSTALL_APPS);
        devicePolicyManager.addUserRestriction(deviceAdminComponent, UserManager.DISALLOW_INSTALL_APPS);
    }

    //功能：检查获取topActivity权限
    //参数：无
    //返回值：无
    public boolean isUsageStatsPermissionGranted() {
        try {
            PackageManager packageManager = context.getPackageManager();
            ApplicationInfo applicationInfo = packageManager.getApplicationInfo(context.getPackageName(), 0);
            AppOpsManager appOpsManager = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            int mode = appOpsManager.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    applicationInfo.uid,
                    applicationInfo.packageName);
            return (mode == AppOpsManager.MODE_ALLOWED);
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    //功能：请求忽略电池优化、获取位置
    //参数：无
    //返回值：无
    public void requestPermission() {
        //请求忽略电池优化
        @SuppressLint("BatteryLife") Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        context.startActivity(intent);
        //请求获取位置
        ActivityCompat.requestPermissions(
                activity,
                new String[]{
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                },
                100
        );
        if (!isUsageStatsPermissionGranted()) {
            //请求读取topActivity
            intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            context.startActivity(intent);
        }
    }

    //功能：开始获取位置
    //参数：无
    //返回值：无
    public void setupLocationListener() {
        locationManager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        locationListener = new LocationListener() {
            @Override
            public void onLocationChanged(@NonNull Location location) {
                longitude = location.getLongitude();
                latitude = location.getLatitude();
            }
        };
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        10000,
                        0,
                        locationListener
                );
            } else if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        10000,
                        0,
                        locationListener
                );
            }
        } catch (SecurityException e) {
            Log.e("SeavoDevice", "Location permission not granted", e);
        }
    }

    //功能：初始化设备
    //1.注册设备到服务器
    //2.首次更新设备心跳时间戳
    //3.拉起开机自启动应用
    //参数：无
    //返回值：无（直接初始化设备）
    public void initializeDevice() {
        new Thread(() -> {
            //注册设备到服务器
            String result = httpManager.registerDevice();
            addToResultList(result);
            addToResultList("Limitation level: " + MainActivity.limitation);
            if (Objects.equals(MainActivity.isOnline, "1")) {
                //首次更新设备心跳时间戳
                httpManager.updateState("1",
                        location,
                        memoryUsage);
                //拉起开机自启应用
                setAppToStartOnReboot(httpManager.getAppToStartOnReboot());
            } else {
                addToResultList("Connection failure. Retrying in " + MainActivity.mainLoopIntervalAsy + "s......");
            }
        }).start();
    }

//-----------------------------------------------//

    //4 主任务循环
    //功能：程序主循环
    //1.检查网络连接状态，异常时尝试重连
    //2.更新设备心跳时间戳、上传设备信息、执行地理围栏策略
    //3.上传已安装应用列表
    //4.获取并显示管理员消息
    //5.检查并安装待下载应用
    //6.检查并卸载待删除应用
    //7.检查并执行重启指令
    //参数：无
    //返回值：无
    public void mainLoop() {
        mainLoopThreadAsy = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                //更新设备心跳时间戳、上传设备信息、执行地理围栏策略
                updateDeviceState();
                sleepInThread(MainActivity.mainLoopIntervalAsy);
                if (Objects.equals(MainActivity.isOnline, "-1")) {
                    initializeDevice();
                    sleepInThread(MainActivity.mainLoopIntervalAsy);
                }
            }
        });

        mainLoopThreadSyn = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                //防止空转
                sleepInThread(MainActivity.mainLoopIntervalSyn);
                if (Objects.equals(MainActivity.isOnline, "1")) {
                    if (MainActivity.operatingTasks.get() == 0) {
                        //上传已安装应用列表
                        httpManager.updateAppList(getInstalledApp());
                        sleepInThread(MainActivity.mainLoopIntervalSyn);
                    }
                    if (MainActivity.operatingTasks.get() == 0) {
                        //获取并显示管理员消息
                        getAndPrintMessages();
                        sleepInThread(MainActivity.mainLoopIntervalSyn);
                    }
                    if (MainActivity.operatingTasks.get() == 0) {
                        //检查并安装待下载应用
                        checkAndInstallApp();
                        sleepInThread(MainActivity.mainLoopIntervalSyn);
                    }
                    if (MainActivity.operatingTasks.get() == 0) {
                        //检查并卸载待删除应用
                        checkAndUninstallApp();
                        sleepInThread(MainActivity.mainLoopIntervalSyn);
                    }
                    if (MainActivity.operatingTasks.get() == 0) {
                        //检查并执行重启指令
                        checkAndExecuteReboot();
                        sleepInThread(MainActivity.mainLoopIntervalSyn);
                    }
                }
            }
        });
        mainLoopThreadAsy.start();
        mainLoopThreadSyn.start();
    }

    //功能：终止主循环线程
    //参数：无
    //返回值：无
    public void stopMainLoop() {
        if (mainLoopThreadAsy != null) {
            mainLoopThreadAsy.interrupt();
        }
        if (mainLoopThreadSyn != null) {
            mainLoopThreadSyn.interrupt();
        }
        if (locationManager != null && locationListener != null) {
            locationManager.removeUpdates(locationListener);
        }
    }

    //以下为一组隶属于MainLoop的函数，不是公共api
    //更新设备心跳时间戳、上传设备信息、执行地理围栏策略
    private void updateDeviceState() {
        //获取内存使用情况
        memoryUsage = getMemoryUsage();
        //获取地理位置
        if (!(longitude == 0 && latitude == 0)) {
            location = httpManager.reverseGeoCode(Double.toString(longitude),
                    Double.toString(latitude));
        }
        //执行地理围栏策略
        if (!Objects.equals(MainActivity.geoFence, "0") &&
                !Objects.equals(location, "0") &&
                !location.contains(MainActivity.geoFence)) {
            if (!Objects.equals(MainActivity.kiosk, "1")) {
                MainActivity.kiosk = "1";
                String[] allowedPackages = {context.getPackageName()};
                devicePolicyManager.setLockTaskPackages(deviceAdminComponent, allowedPackages);
                activity.startLockTask();
                addToResultList("Please stay within the designate area");
            }
        }
        //更新设备心跳时间戳并上传信息
        String connection = httpManager.updateState("0",
                location,
                memoryUsage);
        //网络连接失败时将isOnline设置为"-1"
        if (connection.equals("-1")) {
            MainActivity.isOnline = "-1";
        }
    }

    //检查并执行重启指令
    private void checkAndExecuteReboot() {
        String reboot = httpManager.getRebootCommand();
        if (Objects.equals(reboot, "1")) {
            devicePolicyManager.reboot(deviceAdminComponent);
        } else if (Objects.equals(reboot, "-1")) {
            //异常处理：获取重启指令失败
            Log.e("SeavoDevice", "Fail to get reboot command");
        }
    }

    //获取并显示管理员消息
    private void getAndPrintMessages() {
        List<String> messages;
        messages = httpManager.getMessages();
        if (!messages.isEmpty()) {
            activity.runOnUiThread(() -> {
                AlertDialog.Builder builder = new AlertDialog.Builder(activity);
                builder.setTitle("Admin Message");
                String messageText = TextUtils.join("\n\n", messages);
                builder.setMessage(messageText);
                builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());
                builder.setCancelable(true);
                AlertDialog dialog = builder.create();
                dialog.show();
            });
        }
    }

    //检查并安装待下载应用
    private void checkAndInstallApp() {
        String downloadUrl = httpManager.getAppsToInstall();
        if (downloadUrl != null && !Objects.equals(downloadUrl, "-1")) {
            MainActivity.operatingTasks.incrementAndGet();
            addToResultList("Downloading apk......");
            File downloadedFile = downloadToStorage(downloadUrl);
            if (downloadedFile != null) {
                addToResultList("Writing pkg......");
                installApkWithCallback(downloadedFile.getAbsolutePath());
            } else {
                //异常处理：下载失败
                MainActivity.operatingTasks.decrementAndGet();
                addToResultList("Fail to download app to install");
            }
        } else {
            if (Objects.equals(downloadUrl, "-1")) {
                //异常处理：获取待安装app列表失败
                Log.e("SeavoDevice", "Fail to get app to install");
            }
        }
    }

    //检查并卸载待删除应用
    private void checkAndUninstallApp() {
        String packageName = httpManager.getAppsToUninstall();
        if (packageName != null && !packageName.equals("-1")) {
            MainActivity.operatingTasks.incrementAndGet();
            addToResultList("Uninstalling: " + packageName);
            try {
                uninstallAppWithCallback(packageName);
            } catch (Exception e) {
                MainActivity.operatingTasks.decrementAndGet();
                addToResultList(e.getMessage());
            }
        } else if (Objects.equals(packageName, "-1")) {
            //异常处理：获取待卸载app列表失败
            Log.e("SeavoDevice", "Fail to get app to uninstall");
        }
    }
}