package com.nemesis.mocktraffic;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.dnsoverhttps.DnsOverHttps;

public class TrafficService extends Service {

    private static final String CHANNEL_ID = "TrafficServiceChannel";
    private static final int NOTIFICATION_ID = 1;
    public static final String ACTION_UPDATE_STATS = "com.nemesis.mocktraffic.ACTION_UPDATE_STATS";
    private static final String RELOAD_CONFIG_ACTION = "com.nemesis.mocktraffic.RELOAD_CONFIG";

    private boolean isTrafficEnabled = true;
    private int requestCount = 0;
    private List<String> urlsToVisit = new ArrayList<>();
    private List<String> blacklistedUrls = new ArrayList<>();
    private List<String> userAgents = new ArrayList<>();
    private List<String> lastVisitedUrls = new ArrayList<>();
    private int timeout = 60000;
    private ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private Random random = new Random();
    private static final int LOG_CLEAN_INTERVAL = 30000;
    private static final int MAX_LAST_URLS = 5;
    private static final int MIN_PAGE_DELAY_MS = 10000;
    private static final int MAX_PAGE_DELAY_MS = 30000;
    private static final int MIN_RESOURCE_DELAY_MS = 100;
    private static final int MAX_RESOURCE_DELAY_MS = 1000;
    private static final int MAX_RESOURCES = 10;
    private static final long MAX_RESOURCE_SIZE = 1_000_000;
    private static final String TRACKER_URL = "https://www.google-analytics.com/collect";

    private static final List<String> DOH_PROVIDERS = Arrays.asList(
            "https://dns.google/resolve",
            "https://cloudflare-dns.com/dns-query",
            "https://dns.quad9.net/dns-query"
    );
    private OkHttpClient httpClient;

    private BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            loadUserUrls();
            configureHttpClient();
            Log.d("TrafficService", "URLs and DNS settings reloaded via broadcast.");
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        loadConfigFromAssets();
        loadUserUrls();
        configureHttpClient();
        ContextCompat.registerReceiver(this, configReceiver, new IntentFilter(RELOAD_CONFIG_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private void configureHttpClient() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        boolean dohEnabled = prefs.getBoolean("doh_enabled", true);
        String selectedDohProvider = prefs.getString("doh_provider", DOH_PROVIDERS.get(0));

        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .cache(null);

        if (dohEnabled) {
            for (String provider : DOH_PROVIDERS) {
                if (testDohProvider(provider)) {
                    builder.dns(new DnsOverHttps.Builder()
                            .client(new OkHttpClient())
                            .url(HttpUrl.parse(provider))
                            .build());
                    prefs.edit().putString("doh_provider", provider).apply();
                    Log.d("TrafficService", "Using DoH provider: " + provider);
                    break;
                }
            }
        }
        if (builder.dns() == null) {
            builder.dns(Dns.SYSTEM);
            Log.d("TrafficService", "Using system DNS");
        }

        httpClient = builder.build();
    }

    private boolean testDohProvider(String providerUrl) {
        try {
            OkHttpClient testClient = new OkHttpClient.Builder()
                    .dns(new DnsOverHttps.Builder()
                            .client(new OkHttpClient())
                            .url(HttpUrl.parse(providerUrl))
                            .build())
                    .build();
            Request testRequest = new Request.Builder()
                    .url("https://example.com")
                    .build();
            Response response = testClient.newCall(testRequest).execute();
            boolean isSuccessful = response.isSuccessful();
            response.close();
            return isSuccessful;
        } catch (IOException e) {
            Log.e("TrafficService", "DoH provider test failed for: " + providerUrl, e);
            return false;
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Traffic Service Channel", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Channel for Traffic Generation Service");
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            if (notificationManager != null) {
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ?
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT :
                        PendingIntent.FLAG_UPDATE_CURRENT);

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
        startForeground(NOTIFICATION_ID, buildNotification());
        startTraffic();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        unregisterReceiver(configReceiver);
        stopTraffic();
        super.onDestroy();
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
        scheduler.schedule(trafficRunnable, getDelay(false), TimeUnit.MILLISECONDS);
    }

    private void stopTraffic() {
        scheduler.shutdownNow();
        Log.d("TrafficService", "Traffic generation stopped.");
    }

    private Runnable trafficRunnable = new Runnable() {
        @Override
        public void run() {
            if (isTrafficEnabled && !urlsToVisit.isEmpty()) {
                String urlToVisit = urlsToVisit.get(random.nextInt(urlsToVisit.size()));
                makeHttpRequest(urlToVisit, true);
                if (random.nextInt(100) < 20) { // 20% шанс POST-запроса
                    makePostRequest(urlToVisit);
                }
                if (random.nextInt(100) < 30) { // 30% шанс трекер-запроса
                    makeTrackerRequest();
                }
                synchronized (lastVisitedUrls) {
                    lastVisitedUrls.add(urlToVisit);
                    if (lastVisitedUrls.size() > MAX_LAST_URLS) {
                        lastVisitedUrls.remove(0);
                    }
                    broadcastStats();
                }
                scheduler.schedule(this, getDelay(false), TimeUnit.MILLISECONDS);
            } else {
                Log.d("TrafficService", "Traffic generation stopped or no URLs.");
                stopSelf();
            }
        }
    };

    private long getDelay(boolean isResource) {
        if (isResource) {
            return random.nextInt(MAX_RESOURCE_DELAY_MS - MIN_RESOURCE_DELAY_MS) + MIN_RESOURCE_DELAY_MS;
        }
        int base = 20000; // Среднее 20 секунд для страниц
        int variation = random.nextInt(10000) - 5000; // ±5 секунд
        return Math.max(MIN_PAGE_DELAY_MS, Math.min(MAX_PAGE_DELAY_MS, base + variation));
    }

    private void makeHttpRequest(final String url, boolean isMainRequest) {
        if (!url.startsWith("https://")) {
            Log.e("TrafficService", "Invalid URL scheme (non-HTTPS): " + url);
            return;
        }

        String userAgent = userAgents.isEmpty() ? "Mozilla/5.0" : userAgents.get(random.nextInt(userAgents.size()));
        Request.Builder builder = new Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Connection", "keep-alive")
                .header("Cache-Control", "max-age=0");
        if (isMainRequest) {
            builder.header("Referer", "https://www.google.com/");
        }

        httpClient.newCall(builder.build()).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("TrafficService", "Failed to load URL: " + url, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    requestCount++;
                    Log.d("TrafficService", "Visited URL: " + url + " | Status: " + response.code());
                    if (isMainRequest) {
                        String html = response.body().string();
                        loadSafeResources(url, html);
                    }
                }
                response.close();
            }
        });
    }

    private void makePostRequest(String url) {
        if (!url.startsWith("https://")) return;

        String userAgent = userAgents.isEmpty() ? "Mozilla/5.0" : userAgents.get(random.nextInt(userAgents.size()));
        RequestBody body = RequestBody.create("dummy=1", MediaType.parse("application/x-www-form-urlencoded"));
        Request request = new Request.Builder()
                .url(url)
                .post(body)
                .header("User-Agent", userAgent)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Connection", "keep-alive")
                .header("Cache-Control", "max-age=0")
                .header("Referer", urlsToVisit.get(random.nextInt(urlsToVisit.size())))
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("TrafficService", "Failed POST request: " + url, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    requestCount++;
                    Log.d("TrafficService", "POST request to: " + url + " | Status: " + response.code());
                }
                response.close();
            }
        });
    }

    private void makeTrackerRequest() {
        String userAgent = userAgents.isEmpty() ? "Mozilla/5.0" : userAgents.get(random.nextInt(userAgents.size()));
        String payload = "v=1&tid=UA-123456-1&t=pageview&cid=" + random.nextInt(1000000) + "&dp=%2F";
        RequestBody body = RequestBody.create(payload, MediaType.parse("application/x-www-form-urlencoded"));
        Request request = new Request.Builder()
                .url(TRACKER_URL)
                .post(body)
                .header("User-Agent", userAgent)
                .header("Accept", "*/*")
                .header("Accept-Language", "en-US,en;q=0.5")
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Connection", "keep-alive")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("TrafficService", "Failed tracker request: " + TRACKER_URL, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    requestCount++;
                    Log.d("TrafficService", "Tracker request to: " + TRACKER_URL + " | Status: " + response.code());
                }
                response.close();
            }
        });
    }

    private void loadSafeResources(String baseUrl, String html) {
        try {
            Document doc = Jsoup.parse(html, baseUrl);
            Elements resources = doc.select("link[href$=.css], img[src$=.png], img[src$=.jpg], img[src$=.jpeg], img[src$=.gif], script[src$=.js]");
            List<org.jsoup.nodes.Element> resourceList = resources.stream().limit(MAX_RESOURCES).collect(Collectors.toList());
            Collections.shuffle(resourceList, random);
            for (org.jsoup.nodes.Element resource : resourceList) {
                final String resourceUrl = resource.attr("abs:href").isEmpty() ? resource.attr("abs:src") : resource.attr("abs:href");
                if (resourceUrl.startsWith("https://") && !isBlacklisted(resourceUrl)) {
                    scheduler.schedule(() -> makeHttpRequest(resourceUrl, false), getDelay(true), TimeUnit.MILLISECONDS);
                    if (resourceUrl.endsWith(".css")) {
                        loadCssImports(resourceUrl);
                    }
                }
            }
        } catch (Exception e) {
            Log.e("TrafficService", "Error parsing HTML for resources", e);
        }
    }

    private void loadCssImports(String cssUrl) {
        Request request = new Request.Builder()
                .url(cssUrl)
                .header("User-Agent", userAgents.get(random.nextInt(userAgents.size())))
                .header("Accept", "text/css")
                .header("Connection", "keep-alive")
                .build();

        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                Log.e("TrafficService", "Failed to load CSS: " + cssUrl, e);
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                if (response.isSuccessful()) {
                    String cssContent = response.body().string();
                    List<String> importUrls = extractCssImports(cssContent, cssUrl);
                    for (String importUrl : importUrls) {
                        if (importUrl.startsWith("https://") && !isBlacklisted(importUrl)) {
                            scheduler.schedule(() -> makeHttpRequest(importUrl, false), getDelay(true), TimeUnit.MILLISECONDS);
                        }
                    }
                }
                response.close();
            }
        });
    }

    private List<String> extractCssImports(String cssContent, String baseUrl) {
        List<String> importUrls = new ArrayList<>();
        String[] lines = cssContent.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("@import")) {
                String url = line.replaceAll(".*url\\(['\"]?([^'\"]+)['\"]?\\).*", "$1").trim();
                if (!url.startsWith("http")) {
                    url = HttpUrl.parse(baseUrl).resolve(url).toString();
                }
                if (url.startsWith("https://")) {
                    importUrls.add(url);
                }
            }
        }
        return importUrls;
    }

    private void broadcastStats() {
        Intent intent = new Intent(ACTION_UPDATE_STATS);
        intent.setPackage(getPackageName());
        intent.putExtra("requestCount", requestCount);
        intent.putStringArrayListExtra("lastUrls", new ArrayList<>(lastVisitedUrls));
        sendBroadcast(intent);
        Log.d("TrafficService", "Broadcasted stats: " + requestCount);
    }

    private void scheduleLogCleaning() {
        scheduler.schedule(() -> {
            Log.d("TrafficService", "Log cleaned.");
            scheduleLogCleaning();
        }, LOG_CLEAN_INTERVAL, TimeUnit.MILLISECONDS);
    }

    private void loadConfigFromAssets() {
        try {
            AssetManager assetManager = getAssets();
            InputStream inputStream = assetManager.open("config.json");
            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            parseJsonConfig(sb.toString());
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
            if (url.contains(blacklistedUrl)) return true;
        }
        return false;
    }
}
