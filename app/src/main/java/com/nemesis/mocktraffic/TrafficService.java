package com.nemesis.mocktraffic;

import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.IBinder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class TrafficService extends Service {
    private OkHttpClient httpClient;
    private SharedPreferences prefs;
    private List<String> userAgents;
    private volatile boolean isRunning = false;
    private static final String PREFS_NAME = "TrafficPrefs";
    private static final String URLS_KEY = "UserUrls";
    private static final String SPEED_KEY = "RequestSpeed";

    @Override
    public void onCreate() {
        super.onCreate();
        httpClient = new OkHttpClient();
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        userAgents = loadUserAgents(); // Загружаем юзерагенты из config.json
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!isRunning) {
            isRunning = true;
            new Thread(this::runTraffic).start();
        }
        return START_STICKY;
    }

    private void runTraffic() {
        List<String> urls = loadUrls();
        if (urls.isEmpty()) {
            stopSelf();
            return;
        }

        Random random = new Random();
        while (isRunning) {
            String url = urls.get(random.nextInt(urls.size()));
            String userAgent = userAgents.get(random.nextInt(userAgents.size()));
            Request request = new Request.Builder()
                    .url(url)
                    .header("User-Agent", userAgent)
                    .build();

            httpClient.newCall(request).enqueue(new Callback() {
                @Override
                public void onFailure(Call call, IOException e) {}

                @Override
                public void onResponse(Call call, Response response) throws IOException {
                    response.close();
                }
            });

            try {
                Thread.sleep(getSleepInterval());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private List<String> loadUrls() {
        List<String> urls = new ArrayList<>();
        String urlsJson = prefs.getString(URLS_KEY, "[]");
        try {
            JSONArray jsonArray = new JSONArray(urlsJson);
            for (int i = 0; i < jsonArray.length(); i++) {
                urls.add(jsonArray.getString(i));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return urls;
    }

    private List<String> loadUserAgents() {
        // Здесь должна быть логика загрузки user_agents из config.json
        // Для примера возвращаем заглушку
        List<String> agents = new ArrayList<>();
        agents.add("Mozilla/5.0 (Windows NT 10.0; Win64; x64) Chrome/91.0.4472.124");
        return agents;
    }

    private long getSleepInterval() {
        int speed = prefs.getInt(SPEED_KEY, 1); // 0 - быстро, 1 - средне, 2 - медленно
        switch (speed) {
            case 0: return 1000; // 1 секунда
            case 1: return 3000; // 3 секунды
            case 2: return 6000; // 6 секунд
            default: return 3000;
        }
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
