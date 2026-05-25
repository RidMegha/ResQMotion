package com.resqmotion.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;

public class SplashActivity extends AppCompatActivity {

    private static final int SPLASH_DURATION = 4000; // optimized to 4 sec
    private static final String PREFS_NAME = "FallDetectionPrefs";
    private static final String KEY_PROFILE_SETUP = "profile_setup_complete";

    private ImageView logoImage;
    private TextView appName, tagline;

    private FirebaseAuth mAuth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_splash);

        // Initialize Firebase Auth
        mAuth = FirebaseAuth.getInstance();

        // UI Init
        logoImage = findViewById(R.id.logoImage);
        appName = findViewById(R.id.appName);
        tagline = findViewById(R.id.tagline);

        // Animations
        Animation fadeIn = AnimationUtils.loadAnimation(this, R.anim.fade_in);
        Animation slideUp = AnimationUtils.loadAnimation(this, R.anim.slide_up);
        Animation zoomIn = AnimationUtils.loadAnimation(this, R.anim.zoom_in);

        logoImage.startAnimation(zoomIn);
        appName.startAnimation(fadeIn);
        tagline.startAnimation(slideUp);

        // Delay Handler (modern way)
        new Handler(Looper.getMainLooper()).postDelayed(() -> {

            FirebaseUser currentUser = mAuth.getCurrentUser();

            Intent intent;

            if (currentUser != null) {
                // User logged in → check profile setup
                SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
                boolean profileSetup = prefs.getBoolean(KEY_PROFILE_SETUP, false);

                if (profileSetup) {
                    intent = new Intent(SplashActivity.this, MainActivity.class);
                } else {
                    intent = new Intent(SplashActivity.this, ProfileSetupActivity.class);
                }

            } else {
                // Not logged in → go to Login
                intent = new Intent(SplashActivity.this, LoginActivity.class);
            }

            startActivity(intent);
            finish();

            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);

        }, SPLASH_DURATION);
    }
}