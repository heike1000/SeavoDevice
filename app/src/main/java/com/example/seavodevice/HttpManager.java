package com.example.seavodevice;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class HttpManager {
    private final String serialNumber;
    private final String server = "47.112.30.35:5000";
    private final OkHttpClient client;
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public HttpManager(String serialNumber) {
        this.serialNumber = serialNumber;
        this.client = new OkHttpClient.Builder()
                .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    // 功能：从HTTP连接中读取响应并解析为JSON对象
    // 参数：response - OkHttp的响应对象
    // 返回值：解析后的JSON对象
    private JSONObject getJsonResponse(Response response) throws IOException, JSONException {
        String responseBody = response.body().string();
        return new JSONObject(responseBody);
    }

    // 功能：创建并配置一个HTTP GET请求连接
    // 参数：apiUrl - 请求的目标URL
    // 返回值：配置好的HTTP响应（当响应码为200时），否则返回null
    private Response openHttpWithGet(String apiUrl) throws IOException {
        Request request = new Request.Builder()
                .url(apiUrl)
                .get()
                .build();
        Response response = client.newCall(request).execute();
        if (response.isSuccessful()) {
            return response;
        } else {
            return null;
        }
    }

    // 功能：创建并配置一个HTTP POST请求连接
    // 参数：apiUrl - 请求的目标URL、requestBody - 要发送的JSON数据
    // 返回值：配置好的HTTP响应（当响应码为200时），否则返回null
    private Response openHttpWithPost(String apiUrl, JSONObject requestBody) throws IOException {
        RequestBody body = RequestBody.create(requestBody.toString(), JSON);
        Request request = new Request.Builder()
                .url(apiUrl)
                .post(body)
                .build();
        Response response = client.newCall(request).execute();
        if (response.isSuccessful()) {
            return response;
        } else {
            return null;
        }
    }

    //功能：检查当前设备是否有待处理的重启命令
    //参数：无
    //返回值：String类型，"0"表示无需重启，"1"表示需要重启，"-1"表示出现错误
    public String getRebootCommand() {
        String result;
        String apiUrl = "http://" + server + "/api/reboot?serial_number=" + serialNumber;
        try (Response response = openHttpWithGet(apiUrl)) {
            JSONObject jsonResponse = getJsonResponse(response);
            if (jsonResponse.getString("status").equals("success")) {
                if (jsonResponse.getBoolean("reboot")) {
                    result = "1";
                } else {
                    result = "0";
                }
            } else {
                //服务器错误
                result = "-1";
            }
        } catch (Exception e) {
            //连接错误
            Log.e("SeavoDevice", "Error: " + e.getMessage());
            result = "-1";
        }
        return result;
    }

    //功能：从服务器获取待下载的软件包链接
    //参数：无
    //返回值：String对象，表示需要下载的APK文件的链接；若失败则返回"-1"，若无则返回null
    public String getAppsToInstall() {
        String downloadUrl;
        String apiUrl = "http://" + server + "/api/install?serial_number=" + serialNumber;
        try (Response response = openHttpWithGet(apiUrl)) {
            JSONObject jsonResponse = getJsonResponse(response);
            if (jsonResponse.getString("status").equals("success")) {
                if (!jsonResponse.isNull("download_url")) {
                    downloadUrl = jsonResponse.getString("download_url");
                }
                else {
                    downloadUrl = null;
                }
            } else {
                //服务器错误
                downloadUrl = "-1";
            }
        } catch (Exception e) {
            //连接错误
            Log.e("SeavoDevice", "Error: " + e.getMessage());
            downloadUrl = "-1";
        }
        return downloadUrl;
    }

    //功能：从服务器获取待卸载的软件包的名称
    //参数：无
    //返回值：String，表示要卸载的APP名称；若失败则返回"-1"，若无则返回null
    public String getAppsToUninstall() {
        String packageName;
        String apiUrl = "http://" + server + "/api/uninstall?serial_number=" + serialNumber;
        try (Response response = openHttpWithGet(apiUrl)) {
            JSONObject jsonResponse = getJsonResponse(response);
            if (jsonResponse.getString("status").equals("success")) {
                if (!jsonResponse.isNull("package_name")) {
                    packageName = jsonResponse.getString("package_name");
                }
                else {
                    packageName = null;
                }
            } else {
                //服务器错误
                packageName = "-1";
            }
        } catch (Exception e) {
            //连接错误
            Log.e("SeavoDevice", "Error: " + e.getMessage());
            packageName = "-1";
        }
        return packageName;
    }

    //功能：将当前设备注册到数据库系统，并初始化固件版本信息。若注册成功则将online设置为"1"，失败则设置为"-1"
    //参数：fwVersion - 设备当前固件版本号
    //返回值：String类型，包含注册结果信息（成功/失败/已注册）及设备序列号
    public String registerDevice(String fwVersion) {
        StringBuilder result = new StringBuilder();
        Response response = null;
        String apiUrl = "http://" + server + "/api/register";
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("serial_number", serialNumber);
            requestBody.put("fw_version", fwVersion);
            response = openHttpWithPost(apiUrl, requestBody);
            JSONObject jsonResponse = getJsonResponse(response);
            if (jsonResponse.getString("status").equals("success")) {
                boolean isRegistered = jsonResponse.getBoolean("is_registered");
                if (isRegistered) {
                    MainActivity.limitation = jsonResponse.getString("limitation");
                    result.append(String.format("Device already registered\nSerial number: %s", serialNumber));
                } else {
                    result.append(String.format("Registration success\nSerial number: %s", serialNumber));
                }
                MainActivity.isOnline = "1";
            } else {
                //服务器错误
                result.append("Registration failed");
                MainActivity.isOnline = "-1";
                MainActivity.limitation = "1";
            }
        } catch (Exception e) {
            //连接错误
            result.append("Error: ").append(e.getMessage());
            MainActivity.isOnline = "-1";
            MainActivity.limitation = "1";
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return result.toString();
    }

    //功能：更新设备在线状态和时间戳，用于心跳检测和设备状态监控
    //参数：waked - 设备启动标志（1表示开机状态，0表示常规状态更新）
    //返回值：String类型，"1"表示更新成功，"-1"表示更新失败
    public String updateState(int waked, String longitude, String latitude, String memoryUsage) {
        String result;
        Response response = null;
        String apiUrl = "http://" + server + "/api/update_state";
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("serial_number", serialNumber);
            requestBody.put("waked", waked);
            requestBody.put("longitude", longitude);
            requestBody.put("latitude", latitude);
            requestBody.put("memory_usage", memoryUsage);
            response = openHttpWithPost(apiUrl, requestBody);
            JSONObject jsonResponse = getJsonResponse(response);
            if (jsonResponse.getString("status").equals("success")) {
                result = "1";
            } else {
                //服务器错误
                result = "-1";
            }
        } catch (Exception e) {
            //连接错误
            Log.e("SeavoDevice", "HTTP异常: " + e.getMessage());
            result = "-1";
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return result;
    }

    //功能：查询当前设备需要开机启动的应用、是否需要进入kiosk模式
    //参数：无
    //返回值：String类型，返回需要启用的应用包名；若无则返回null，若失败则返回"-1"。limitation为2时会直接返回该应用名称。
    public String getAppToStartOnReboot() {
        String result;
        String apiUrl = "http://" + server + "/api/app_on_start?serial_number=" + serialNumber;
        try (Response response = openHttpWithGet(apiUrl)) {
            JSONObject jsonResponse = getJsonResponse(response);
            if (jsonResponse.getString("status").equals("success")) {
                if (!jsonResponse.isNull("app_name")) {
                    result = jsonResponse.getString("app_name");
                    MainActivity.kiosk = jsonResponse.getString("kiosk");
                }
                else {
                    result = null;
                }
            } else {
                //服务器错误
                result = "-1";
            }
            if (Objects.equals(MainActivity.limitation, "2") || Objects.equals(MainActivity.limitation, "3")) {
                result = "com.example.seavodevice";
            }
        } catch (Exception e) {
            //连接错误
            Log.e("SeavoDevice", "HTTP异常: " + e.getMessage());
            result = "-1";
        }
        return result;
    }

    //功能：全量更新设备上安装的应用程序列表到数据库
    //参数：usersApp - 设备当前安装的所有应用包名列表
    //返回值：String类型，"1"表示更新成功，"-1"表示更新失败
    public String updateAppList(List<String> usersApp) {
        String result;
        Response response = null;
        String apiUrl = "http://" + server + "/api/update_app_list";
        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("serial_number", serialNumber);
            JSONArray appsArray = new JSONArray();
            for (String app : usersApp) {
                appsArray.put(app);
            }
            requestBody.put("apps", appsArray);
            response = openHttpWithPost(apiUrl, requestBody);
            JSONObject jsonResponse = getJsonResponse(response);
            if (jsonResponse.getString("status").equals("success")) {
                result = "1";
            } else {
                //服务器错误
                result = "-1";
            }
        } catch (Exception e) {
            //连接错误
            Log.e("UpdateAppList", "HTTP异常: " + e.getMessage());
            result = "-1";
        } finally {
            if (response != null) {
                response.close();
            }
        }
        return result;
    }

    //功能：获取管理员发送给当前设备的未读消息
    //参数：无
    //返回值：List<String>类型，包含所有未读消息内容（每条消息前缀"Message from admin:"），无消息返回空列表
    public List<String> getMessages() {
        List<String> result = new ArrayList<>();
        String apiUrl = "http://" + server + "/api/messages?serial_number=" + serialNumber;
        try (Response response = openHttpWithGet(apiUrl)) {
            JSONObject jsonResponse = getJsonResponse(response);
            if (jsonResponse.getString("status").equals("success")) {
                JSONArray messages = jsonResponse.getJSONArray("messages");
                for (int i = 0; i < messages.length(); i++) {
                    JSONObject message = messages.getJSONObject(i);
                    result.add(message.getString("content"));
                }
            }
        } catch (Exception e) {
            //连接错误
            Log.e("SeavoDevice", "HTTP异常: " + e.getMessage());
        }
        return result;
    }
}