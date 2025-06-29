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
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import okhttp3.Cache;
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
    private List<String> lastVisitedUrls = new ArrayList<>();
    private int minSleep = 2000;
    private int maxSleep = 5000;
    private int timeout = 60000;
    private Handler trafficHandler = new Handler();
    private Handler logCleanerHandler = new Handler();
    private OkHttpClient httpClient = new OkHttpClient.Builder()
            .cache(null) // Отключение кеша
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
            Log.e("TrafficService", "No URLs to visit. Check config.json");
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
            Log.d("TrafficService", "Traffic runnable started. URLs to visit: " + urlsToVisit.size());
            if (isTrafficEnabled && !urlsToVisit.isEmpty()) {
                String urlToVisit = urlsToVisit.get(random.nextInt(urlsToVisit.size()));
                Log.d("TrafficService", "Visiting URL: " + urlToVisit);
                makeHttpRequest(urlToVisit);
                synchronized (lastVisitedUrls) {
                    lastVisitedUrls.add(urlToVisit);
                    if (lastVisitedUrls.size() > MAX_LAST_URLS) {
                        lastVisitedUrls.remove(0);
                    }
                    broadcastStats();
                }
                int sleepTime = random.nextInt(maxSleep - minSleep + 1) + minSleep;
                trafficHandler.postDelayed(this, sleepTime);
            } else {
                Log.d("TrafficService", "Traffic generation stopped or no URLs.");
                stopSelf();
            }
        }
    };

    private void makeHttpRequest(final String url) {
        if (!url.startsWith("https://")) {
            Log.e("TrafficService", "Invalid URL scheme (non-HTTPS): " + url);
            return;
        }

        Request request = new Request.Builder()
                .url(url)
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
            SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
            String userUrlsJson = prefs.getString("user_urls", "[]");
            JSONArray userUrls = new JSONArray(userUrlsJson);
            if (userUrls.length() > 0) {
                for (int i = 0; i < userUrls.length(); i++) {
                    String url = userUrls.getString(i);
                    if (url.startsWith("https://")) {
                        urlsToVisit.add(url);
                    }
                }
            } else {
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
            }
            Log.d("TrafficService", "Configuration loaded successfully.");
        } catch (IOException | JSONException e) {
            Log.e("TrafficService", "Error reading config.json", e);
            stopSelf();
        }
    }

    private void parseJsonConfig(String jsonString) {
        try {
            JSONObject jsonObject = new JSONObject(jsonString);
            JSONArray rootUrls = jsonObject.getJSONArray("root_urls");
            JSONArray blacklistedUrlsJson = jsonObject.getJSONArray("blacklisted_urls");

            for (int i = 0; i < rootUrls.length(); i++) {
                String url = rootUrls.getString(i);
                if (url.startsWith("https://")) {
                    urlsToVisit.add(url);
                } else {
                    Log.w("TrafficService", "Skipped non-HTTPS URL: " + url);
                }
            }

            for (int i = 0; i < blacklistedUrlsJson.length(); i++) {
                blacklistedUrls.add(blacklistedUrlsJson.getString(i));
            }

            minSleep = jsonObject.getInt("min_sleep");
            maxSleep = jsonObject.getInt("max_sleep");
            timeout = jsonObject.optInt("timeout", 60000);
            Log.d("TrafficService", "Parsed config.json: " + jsonObject.toString());
        } catch (JSONException e) {
            Log.e("TrafficService", "Error parsing config.json", e);
            stopSelf();
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
