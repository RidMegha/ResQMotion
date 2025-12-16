package com.example.falldetectionapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DURATION = 5000;
    private static final String PREFS_NAME = "FallDetectionPrefs";
    private static final String KEY_PROFILE_SETUP = "profile_setup_complete";

    private ImageView logoImage;
    private TextView appName, tagline;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        logoImage = findViewById(R.id.logoImage);
        appName = findViewById(R.id.appName);
        tagline = findViewById(R.id.tagline);

        Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up);
        Animation zoomIn = AnimationUtils.loadAnimation(this, R.anim.zoom_in);

        logoImage.startAnimation(zoomIn);
        appName.startAnimation(fadeIn);
        tagline.startAnimation(slideUp);

        new Handler().postDelayed(() -> {
            SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
            boolean profileSetup = prefs.getBoolean(KEY_PROFILE_SETUP, false);

            Intent intent;
            if (profileSetup) {
                intent = new Intent(SplashActivity.this, MainActivity.class);
            } else {
                intent = new Intent(SplashActivity.this, ProfileSetupActivity.class);
            }

            startActivity(intent);
            finish();
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

        }, SPLASH_DURATION);
    }
}