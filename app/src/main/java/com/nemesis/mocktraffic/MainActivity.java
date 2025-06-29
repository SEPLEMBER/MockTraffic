package com.nemesis.mocktraffic;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONArray;
import org.json.JSONException;

public class MainActivity extends AppCompatActivity {
    private EditText urlInput;
    private Button addUrlButton;
    private SeekBar speedSeekBar;
    private SharedPreferences prefs;
    private static final String PREFS_NAME = "TrafficPrefs";
    private static final String URLS_KEY = "UserUrls";
    private static final String SPEED_KEY = "RequestSpeed";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        urlInput = findViewById(R.id.urlInput);
        addUrlButton = findViewById(R.id.addUrlButton);
        speedSeekBar = findViewById(R.id.speedSeekBar);

        // Настройка SeekBar для скорости (0 - быстро, 1 - средне, 2 - медленно)
        speedSeekBar.setMax(2);
        speedSeekBar.setProgress(prefs.getInt(SPEED_KEY, 1)); // Средняя скорость по умолчанию
        speedSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                prefs.edit().putInt(SPEED_KEY, progress).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        addUrlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String newUrl = urlInput.getText().toString().trim();
                if (!newUrl.isEmpty()) {
                    addUrl(newUrl);
                    urlInput.setText("");
                }
            }
        });
    }

    private void addUrl(String url) {
        try {
            String urlsJson = prefs.getString(URLS_KEY, "[]");
            JSONArray urls = new JSONArray(urlsJson);
            if (!containsUrl(urls, url)) {
                urls.put(url);
                prefs.edit().putString(URLS_KEY, urls.toString()).apply();
                Log.i("MainActivity", "Added URL: " + url);
            }
        } catch (JSONException e) {
            Log.e("MainActivity", "Error updating URLs", e);
        }
    }

    private boolean containsUrl(JSONArray urls, String url) throws JSONException {
        for (int i = 0; i < urls.length(); i++) {
            if (urls.getString(i).equals(url)) {
                return true;
            }
        }
        return false;
    }
}
