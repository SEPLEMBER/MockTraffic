package com.nemesis.mocktraffic;

import android.Manifest;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;
import android.util.Patterns;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_POST_NOTIFICATIONS = 1;
    private static final int REQUEST_IGNORE_BATTERY_OPTIMIZATIONS = 2;
    private static final String PREFS_NAME = "app_prefs";
    private static final String URLS_KEY = "user_urls";
    private static final String[] DOH_PROVIDERS = {
            "https://dns.google/resolve",
            "https://cloudflare-dns.com/dns-query",
            "https://dns.quad9.net/dns-query"
    };
    private static final String[] DOH_PROVIDER_NAMES = {
            "Google DNS",
            "Cloudflare DNS",
            "Quad9 DNS"
    };

    private CheckBox trafficCheckBox;
    private CheckBox dohCheckBox;
    private Spinner dohProviderSpinner;
    private TextView trafficStatsTextView;
    private TextView statusTextView;
    private TextView lastUrlsTextView;
    private EditText urlInput;
    private Button addUrlButton;
    private Button clearUrlButton;

    private BroadcastReceiver statsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (TrafficService.ACTION_UPDATE_STATS.equals(intent.getAction())) {
                int requestCount = intent.getIntExtra("requestCount", 0);
                trafficStatsTextView.setText("Traffic Stats: " + requestCount + " requests");
                ArrayList<String> lastUrls = intent.getStringArrayListExtra("lastUrls");
                if (lastUrls != null && !lastUrls.isEmpty()) {
                    lastUrlsTextView.setText("Last URLs:\n" + String.join("\n", lastUrls));
                }
                SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                preferences.edit().putInt("request_count", requestCount).apply();
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        trafficCheckBox = findViewById(R.id.trafficCheckBox);
        dohCheckBox = findViewById(R.id.dohCheckBox);
        dohProviderSpinner = findViewById(R.id.dohProviderSpinner);
        trafficStatsTextView = findViewById(R.id.trafficStatsTextView);
        statusTextView = findViewById(R.id.statusTextView);
        lastUrlsTextView = findViewById(R.id.lastUrlsTextView);
        urlInput = findViewById(R.id.urlInput);
        addUrlButton = findViewById(R.id.addUrlButton);
        clearUrlButton = findViewById(R.id.clearUrlButton);

        SharedPreferences preferences = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        boolean trafficEnabled = preferences.getBoolean("traffic_enabled", false);
        boolean dohEnabled = preferences.getBoolean("doh_enabled", true);
        String selectedDohProvider = preferences.getString("doh_provider", DOH_PROVIDERS[0]);

        trafficCheckBox.setChecked(trafficEnabled);
        dohCheckBox.setChecked(dohEnabled);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, DOH_PROVIDER_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        dohProviderSpinner.setAdapter(adapter);
        dohProviderSpinner.setSelection(Arrays.asList(DOH_PROVIDERS).indexOf(selectedDohProvider));
        dohProviderSpinner.setEnabled(dohEnabled);

        int savedRequestCount = preferences.getInt("request_count", 0);
        trafficStatsTextView.setText("Traffic Stats: " + savedRequestCount + " requests");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_POST_NOTIFICATIONS);
            }
        }

        addUrlButton.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "Enter a URL", Toast.LENGTH_SHORT).show();
                return;
            }
            if (!Patterns.WEB_URL.matcher(url).matches() || !url.startsWith("https://")) {
                Toast.makeText(this, "Invalid HTTPS URL", Toast.LENGTH_SHORT).show();
                return;
            }
            String userUrlsJson = preferences.getString(URLS_KEY, "[]");
            try {
                JSONArray userUrls = new JSONArray(userUrlsJson);
                if (!containsUrl(userUrls, url)) {
                    userUrls.put(url);
                    preferences.edit().putString(URLS_KEY, userUrls.toString()).apply();
                    urlInput.setText("");
                    Toast.makeText(this, "URL added", Toast.LENGTH_SHORT).show();
                    reloadTrafficService();
                } else {
                    Toast.makeText(this, "URL already exists", Toast.LENGTH_SHORT).show();
                }
            } catch (JSONException e) {
                Log.e("MainActivity", "Error updating URLs", e);
            }
        });

        clearUrlButton.setOnClickListener(v -> {
            preferences.edit().putString(URLS_KEY, "[]").apply();
            Toast.makeText(this, "URLs cleared", Toast.LENGTH_SHORT).show();
            reloadTrafficService();
        });

        dohCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.edit().putBoolean("doh_enabled", isChecked).apply();
            dohProviderSpinner.setEnabled(isChecked);
            reloadTrafficService();
            Toast.makeText(this, "DoH " + (isChecked ? "enabled" : "disabled"), Toast.LENGTH_SHORT).show();
        });

        dohProviderSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, android.view.View view, int position, long id) {
                preferences.edit().putString("doh_provider", DOH_PROVIDERS[position]).apply();
                reloadTrafficService();
                Toast.makeText(MainActivity.this, "Selected DoH provider: " + DOH_PROVIDER_NAMES[position], Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
                // No action needed
            }
        });

        trafficCheckBox.setOnCheckedChangeListener((buttonView, isChecked) -> {
            preferences.edit().putBoolean("traffic_enabled", isChecked).apply();
            if (isChecked) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        Toast.makeText(this, "Notification permission required.", Toast.LENGTH_LONG).show();
                        trafficCheckBox.setChecked(false);
                        return;
                    }
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                    if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
                        Intent intentExempt = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        intentExempt.setData(android.net.Uri.parse("package:" + getPackageName()));
                        startActivityForResult(intentExempt, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                        return;
                    }
                }

                Intent serviceIntent = new Intent(this, TrafficService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                Toast.makeText(this, "Traffic Generation Enabled", Toast.LENGTH_SHORT).show();
                statusTextView.setText("Traffic Generation Enabled");
            } else {
                Intent serviceIntent = new Intent(this, TrafficService.class);
                stopService(serviceIntent);
                Toast.makeText(this, "Traffic Generation Disabled", Toast.LENGTH_SHORT).show();
                statusTextView.setText("Traffic Generation Disabled");
            }
        });
    }

    private boolean containsUrl(JSONArray urls, String url) throws JSONException {
        for (int i = 0; i < urls.length(); i++) {
            if (urls.getString(i).equals(url)) {
                return true;
            }
        }
        return false;
    }

    private void reloadTrafficService() {
        Intent intent = new Intent("com.nemesis.mocktraffic.RELOAD_CONFIG");
        intent.setPackage(getPackageName());
        sendBroadcast(intent);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(TrafficService.ACTION_UPDATE_STATS);
        ContextCompat.registerReceiver(this, statsReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(statsReceiver);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_POST_NOTIFICATIONS) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Notification permission granted.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Notification permission denied.", Toast.LENGTH_LONG).show();
                trafficCheckBox.setChecked(false);
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                Toast.makeText(this, "Battery optimization exemption granted.", Toast.LENGTH_SHORT).show();
                Intent serviceIntent = new Intent(this, TrafficService.class);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent);
                } else {
                    startService(serviceIntent);
                }
                statusTextView.setText("Traffic Generation Enabled");
            } else {
                Toast.makeText(this, "Battery optimization exemption denied.", Toast.LENGTH_LONG).show();
                trafficCheckBox.setChecked(false);
                new AlertDialog.Builder(this)
                        .setTitle("Battery Optimization")
                        .setMessage("Please allow the app to ignore battery optimizations.")
                        .setPositiveButton("Open Settings", (dialog, which) -> {
                            Intent intentExempt = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                            startActivity(intentExempt);
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            }
        }
    }
}
