package com.nemesis.mocktraffic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TrafficService extends Service {

    private static final String CHANNEL_ID = "TrafficServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_UPDATE_STATS = "com.nemesis.mocktraffic.ACTION_UPDATE_STATS";

    private boolean isTrafficEnabled = true;
    private int requestCount = 0;
    private List<String> urlsToVisit = new ArrayList<>();
    private List<String> blacklistedUrls = new ArrayList<>();
    private List<String> userAgents = new ArrayList<>();
    private List<String> lastVisitedUrls = new ArrayList<>();
    private int timeout = 60000;
    private Handler trafficHandler = new Handler();
    private Handler logCleanerHandler = new Handler();
    private OkHttpClient httpClient = new OkHttpClient.Builder()
            .cache(null)
            .build();
    private Random random = new Random();
    private static final int LOG_CLEAN_INTERVAL = 30000;
    private static final int MAX_LAST_URLS = 5;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d("TrafficService", "Service created.");
        createNotificationChannel();
        loadConfigFromAssets();
        loadUserUrls();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            CharSequence name = "Traffic Service Channel";
            String description = "Channel for Traffic Generation Service";
            int importance = NotificationManager.IMPORTANCE_LOW;
            NotificationChannel channel = new NotificationChannel(CHANNEL_ID, name, importance);
            channel.setDescription(description);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
                Log.d("TrafficService", "Notification channel created.");
            } else {
                Log.e("TrafficService", "NotificationManager is null.");
            }
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingIntent = PendingIntent.getActivity(this,
                    0, notificationIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        } else {
            pendingIntent = PendingIntent.getActivity(this,
                    0, notificationIntent, PendingIntent.FLAG_UPDATE_CURRENT);
        }

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Traffic Generation Active")
                .setContentText("Generating traffic in the background")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d("TrafficService", "onStartCommand called.");
        Notification notification = buildNotification();
        startForeground(NOTIFICATION_ID, notification);
        Log.d("TrafficService", "Foreground service started.");
        startTraffic();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopTraffic();
        Log.d("TrafficService", "Service destroyed.");
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void startTraffic() {
        if (urlsToVisit.isEmpty()) {
            Log.e("TrafficService", "No URLs to visit.");
            stopSelf();
            return;
        }
        trafficHandler.post(trafficRunnable);
        scheduleLogCleaning();
        Log.d("TrafficService", "Traffic generation started.");
    }

    private void stopTraffic() {
        trafficHandler.removeCallbacks(trafficRunnable);
        logCleanerHandler.removeCallbacksAndMessages(null);
        Log.d("TrafficService", "Traffic generation stopped.");
    }

    private Runnable trafficRunnable = new Runnable() {
        @Override
        public void run() {
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            int speed = prefs.getInt("request_speed", 1); // 0: fast, 1: medium, 2: slow
            long delay;
            switch (speed) {
                case 0: delay = 1000; break; // Fast: 1s
                case 1: delay = 3000; break; // Medium: 3s
                case 2: delay = 6000; break; // Slow: 6s
                default: delay = 3000; break;
            }

            Log.d("TrafficService", "Traffic runnable started. URLs to visit: " + urlsToVisit.size());
            if (isTrafficEnabled && !urlsToVisit.isEmpty()) {
                String urlToVisit = urlsToVisit.get(random.nextInt(urlsToVisit.size()));
                String ipAddress = getIpAddress(urlToVisit);
                Log.d("TrafficService", "Visiting URL: " + urlToVisit + " (" + ipAddress + ")");
                makeHttpRequest(urlToVisit);
                synchronized (lastVisitedUrls) {
                    lastVisitedUrls.add(urlToVisit + " (" + ipAddress + ")");
                    if (lastVisitedUrls.size() > MAX_LAST_URLS) {
                        lastVisitedUrls.remove(0);
                    }
                    broadcastStats();
                }
                trafficHandler.postDelayed(this, delay);
            } else {
                Log.d("TrafficService", "Traffic generation stopped or no URLs.");
                stopSelf();
            }
        }
    };

    private String getIpAddress(String url) {
        try {
            String host = url.replace("https://", "").split("/")[0];
            InetAddress address = InetAddress.getByName(host);
            return address.getHostAddress();
        } catch (Exception e) {
            Log.e("TrafficService", "Failed to resolve IP for: " + url, e);
            return "Unknown IP";
        }
    }

    private void makeHttpRequest(final String url) {
        if (!url.startsWith("https://")) {
            Log.e("TrafficService", "Invalid URL scheme (non-HTTPS): " + url);
            return;
        }

        String userAgent = userAgents.isEmpty() ? "Mozilla/5.0" : userAgents.get(random.nextInt(userAgents.size()));
        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("TrafficService", "Failed to load URL: " + url, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    requestCount++;
                    Log.d("TrafficService", "Visited URL: " + url + " | Status: " + response.code());
                } else {
                    Log.e("TrafficService", "Failed to visit URL: " + url + " | Status: " + response.code());
                }
                response.close();
            }
        });
    }

    private void broadcastStats() {
        Intent intent = new Intent(ACTION_UPDATE_STATS);
        intent.setPackage(getPackageName()); // Fix for UnsafeImplicitIntentLaunch
        intent.putExtra("requestCount", requestCount);
        intent.putStringArrayListExtra("lastUrls", new ArrayList<>(lastVisitedUrls));
        sendBroadcast(intent);
        Log.d("TrafficService", "Broadcasted stats: " + requestCount);
    }

    private void scheduleLogCleaning() {
        logCleanerHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                Log.d("TrafficService", "Log cleaned.");
                logCleanerHandler.postDelayed(this, LOG_CLEAN_INTERVAL);
            }
        }, LOG_CLEAN_INTERVAL);
    }

    private void loadConfigFromAssets() {
        try {
            AssetManager assetManager = getAssets();
            InputStream inputStream = assetManager.open("config.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder stringBuilder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line);
            }
            reader.close();
            parseJsonConfig(stringBuilder.toString());
            Log.d("TrafficService", "Configuration loaded successfully.");
        } catch (IOException e) {
            Log.e("TrafficService", "Error reading config.json", e);
            stopSelf();
        }
    }

    private void parseJsonConfig(String jsonString) {
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            JSONArray blacklistedUrlsJson = jsonObject.getJSONArray("blacklisted_urls");
            JSONArray userAgentsJson = jsonObject.getJSONArray("user_agents");

            for (int i = 0; i < blacklistedUrlsJson.length(); i++) {
                blacklistedUrls.add(blacklistedUrlsJson.getString(i));
            }

            for (int i = 0; i < userAgentsJson.length(); i++) {
                userAgents.add(userAgentsJson.getString(i));
            }

            timeout = jsonObject.optInt("timeout", 60000);
            Log.d("TrafficService", "Parsed config.json: " + jsonObject.toString());
        } catch (JSONException e) {
            Log.e("TrafficService", "Error parsing config.json", e);
            stopSelf();
        }
    }

    private void loadUserUrls() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String userUrlsJson = prefs.getString("user_urls", "[]");
        try {
            JSONArray userUrls = new JSONArray(userUrlsJson);
            urlsToVisit.clear();
            for (int i = 0; i < userUrls.length(); i++) {
                String url = userUrls.getString(i);
                if (url.startsWith("https://") && !isBlacklisted(url)) {
                    urlsToVisit.add(url);
                }
            }
        } catch (JSONException e) {
            Log.e("TrafficService", "Error loading user URLs", e);
        }
    }

    private boolean isBlacklisted(String url) {
        for (String blacklistedUrl : blacklistedUrls) {
            if (url.contains(blacklistedUrl)) {
                return true;
            }
        }
        return false;
    }
}
