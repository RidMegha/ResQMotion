package com.resqmotion.app;

import android.Manifest;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.FirebaseApp;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.telephony.SmsManager;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.tensorflow.lite.Interpreter;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    // ─────────────────────────────────────────────────────────
    //  FIREBASE
    // ─────────────────────────────────────────────────────────
    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    private static final String DATABASE_URL =
            "https://resqmotion-default-rtdb.asia-southeast1.firebasedatabase.app/";

    // ─────────────────────────────────────────────────────────
    //  PHASE 3 – REAL-TIME SYNC
    // ─────────────────────────────────────────────────────────
    // Uploads current activity to Firebase every 30 seconds while monitoring.
    // Lower = more real-time but more DB writes. 30s is safe for free tier.
    private static final long SYNC_INTERVAL_MS = 30_000L;

    private final Handler  syncHandler  = new Handler();
    private       Runnable syncRunnable = null;

    // These are updated by runKuhar() on every inference, ready for the sync loop
    private String lastSyncedActivity   = "";
    private float  lastSyncedConfidence = 0f;

    // ─────────────────────────────────────────────────────────
    //  CONSTANTS
    // ─────────────────────────────────────────────────────────
    private static final String TAG = "FallApp";
    private static final int PERMISSION_REQ_CODE   = 101;
    private static final int CONTACTS_REQUEST_CODE = 102;
    private static final int PROFILE_REQUEST_CODE  = 103;

    private static final String KUHAR_MODEL   = "kuhar_cnn_lstm_corrected.tflite";
    private static final String SISFALL_MODEL = "best_cnn_lstm_model.tflite";
    private static final int    KUHAR_WINDOW   = 300;
    private static final int    SISFALL_WINDOW = 200;
    private static final float  TRAINING_MEAN  = 4.1497445f;
    private static final float  TRAINING_STD   = 3906.263f;
    private static final float  FALL_THRESHOLD          = 0.82f;
    private static final float  MIN_IMPACT_THRESHOLD    = 15.0f;
    private static final float  GYRO_ROTATION_THRESHOLD = 0.5f;
    private static final float  MOVEMENT_THRESHOLD      = 0.5f;
    private static final long   INFERENCE_INTERVAL_MS   = 500;
    private static final long   ALERT_DELAY_MS          = 10000;
    private static final int    ACTIVITY_CONFIRM_THRESHOLD = 1;
    private static final int    FALL_CONFIRM_THRESHOLD     = 2;

    // ─────────────────────────────────────────────────────────
    //  ML / SENSOR FIELDS
    // ─────────────────────────────────────────────────────────
    private Interpreter kuharTflite, sisfallTflite;
    private SensorManager sensorManager;
    private Sensor accel, gyro;

    private final List<Float> ax = new ArrayList<>();
    private final List<Float> ay = new ArrayList<>();
    private final List<Float> az = new ArrayList<>();
    private final List<Float> gx = new ArrayList<>();
    private final List<Float> gy = new ArrayList<>();
    private final List<Float> gz = new ArrayList<>();

    private float[] lastAccel = new float[3];
    private float[] lastGyro  = new float[3];
    private long    lastInferenceTime   = 0;
    private boolean isMonitoring        = false;
    private boolean fallDetected        = false;
    private Handler alertHandler        = new Handler();
    private boolean modelsLoaded        = false;
    private String  lastDisplayedActivity = "";
    private int     activityConfirmCount  = 0;
    private int     fallConfirmCount      = 0;

    // ─────────────────────────────────────────────────────────
    //  UI REFERENCES
    // ─────────────────────────────────────────────────────────
    private TextView activityText, confidenceText, statusText,
            sensorDataText, emergencyContactsText,
            userProfileText, headerWelcomeText,
            syncStatusText;   // NEW in Phase 3 – shows sync state

    private Button startButton, stopButton, emergencyButton,
            manageContactsButton, viewProfileButton,
            logoutButton;

    private View statusIndicator;

    // ─────────────────────────────────────────────────────────
    //  LOCAL STORAGE
    // ─────────────────────────────────────────────────────────
    private SharedPreferences prefs;
    private static final String PREFS_NAME   = "FallDetectionPrefs";
    private static final String CONTACTS_KEY = "emergency_contacts_json";
    private static final String PROFILE_KEY  = "user_profile";
    private List<EmergencyContact> emergencyContacts = new ArrayList<>();
    private UserProfile userProfile;
    private Gson gson;

    // ─────────────────────────────────────────────────────────
    //  ACTIVITY LABELS
    // ─────────────────────────────────────────────────────────
    private static final String[] KUHAR_LABELS = {
            "Walking Forward", "Walking Left", "Walking Right",
            "Walking Upstairs", "Walking Downstairs",
            "Running", "Jumping", "Sitting", "Standing", "Lying Down",
            "Stand to Sit", "Sit to Stand", "Sit to Lie",
            "Lie to Sit", "Stand to Lie", "Lie to Stand",
            "Turning Left", "Turning Right"
    };

    // ═════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FirebaseApp.initializeApp(this);
        setContentView(R.layout.activity_main);

        // ── STEP 1: Auth guard ────────────────────────────────
        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) {
            navigateToLogin();
            return;
        }

        // ── Firebase DB ───────────────────────────────────────
        try {
            mDatabase = FirebaseDatabase.getInstance(DATABASE_URL).getReference();
        } catch (Exception e) {
            Log.e(TAG, "Firebase DB init error: " + e.getMessage());
        }

        // ── Local storage ─────────────────────────────────────
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        gson  = new Gson();
        loadEmergencyContacts();
        loadUserProfile();

        // ── Bind all views ────────────────────────────────────
        activityText          = findViewById(R.id.activityText);
        confidenceText        = findViewById(R.id.confidenceText);
        statusText            = findViewById(R.id.statusText);
        sensorDataText        = findViewById(R.id.sensorDataText);
        emergencyContactsText = findViewById(R.id.emergencyContactsText);
        userProfileText       = findViewById(R.id.userProfileText);
        statusIndicator       = findViewById(R.id.statusIndicator);
        headerWelcomeText     = findViewById(R.id.headerWelcomeText);
        syncStatusText        = findViewById(R.id.syncStatusText);   // Phase 3
        startButton           = findViewById(R.id.startButton);
        stopButton            = findViewById(R.id.stopButton);
        emergencyButton       = findViewById(R.id.emergencyButton);
        manageContactsButton  = findViewById(R.id.manageContactsButton);
        viewProfileButton     = findViewById(R.id.viewProfileButton);
        logoutButton          = findViewById(R.id.logoutButton);

        // ── Button listeners ──────────────────────────────────
        startButton.setOnClickListener(v -> startMonitoring());
        stopButton.setOnClickListener(v -> stopMonitoring());
        emergencyButton.setOnClickListener(v -> cancelAlert());
        manageContactsButton.setOnClickListener(v -> openContactsManager());
        viewProfileButton.setOnClickListener(v -> openProfileView());
        if (logoutButton != null) logoutButton.setOnClickListener(v -> logoutUser());

        // ── Initial UI state ──────────────────────────────────
        updateButtonStates();
        updateEmergencyContactsDisplay();
        updateProfileDisplay();
        updateWelcomeHeader(currentUser);
        if (syncStatusText != null) syncStatusText.setText("☁ Not syncing");

        // ── STEP 3: Load Firebase profile (async) ─────────────
        loadUserFromFirebase(currentUser);

        // ── Permissions, models, sensors ─────────────────────
        requestPermissions();
        loadModels();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro  = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (accel == null || gyro == null) {
            Toast.makeText(this, "ERROR: Required sensors not available!",
                    Toast.LENGTH_LONG).show();
            Log.e(TAG, "Sensors not available");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Safety re-check on every resume
        if (mAuth.getCurrentUser() == null) { navigateToLogin(); return; }

        if (isMonitoring && accel != null && gyro != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, gyro,  SensorManager.SENSOR_DELAY_GAME);
        }
        loadEmergencyContacts();
        updateEmergencyContactsDisplay();
        loadUserProfile();
        updateProfileDisplay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isMonitoring) sensorManager.unregisterListener(this);
        // NOTE: sync loop intentionally keeps running in onPause so that
        // background monitoring still uploads. It is stopped only in
        // stopMonitoring() or onDestroy().
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSyncLoop();   // always clean up timer
        if (kuharTflite   != null) { try { kuharTflite.close();   } catch (Exception ignored) {} }
        if (sisfallTflite != null) { try { sisfallTflite.close(); } catch (Exception ignored) {} }
        alertHandler.removeCallbacksAndMessages(null);
    }

    // ═════════════════════════════════════════════════════════
    //  PHASE 3 ── REAL-TIME SYNC LOOP
    // ═════════════════════════════════════════════════════════

    /**
     * Starts a repeating 30-second upload loop.
     * First tick fires immediately so there is no initial delay.
     * Called from startMonitoring().
     */
    private void startSyncLoop() {
        stopSyncLoop(); // never run two loops simultaneously

        syncRunnable = new Runnable() {
            @Override
            public void run() {
                if (isMonitoring) {
                    uploadCurrentStatus();
                    syncHandler.postDelayed(this, SYNC_INTERVAL_MS);
                }
            }
        };
        syncHandler.post(syncRunnable); // fire immediately
    }

    /**
     * Stops the upload loop.
     * Called from stopMonitoring(), logoutUser(), and onDestroy().
     */
    private void stopSyncLoop() {
        if (syncRunnable != null) {
            syncHandler.removeCallbacks(syncRunnable);
            syncRunnable = null;
        }
    }

    /**
     * PHASE 3 CORE METHOD.
     *
     * Writes to:  users/{uid}/current_status/
     *
     * Fields uploaded:
     *   activity   – current recognised activity (e.g. "Walking")
     *   confidence – model confidence 0.0–1.0
     *   battery    – device battery % (for family dashboard)
     *   monitoring – true (lets family know device is active)
     *   fall_alert – false (normal state; set true on fall)
     *   timestamp  – epoch ms of this upload
     *
     * Uses SET (not push) so only ONE node exists — always the latest.
     * Fall history uses push() and lives under fall_history/ separately.
     */
    private void uploadCurrentStatus() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null || mDatabase == null) return;

        // Don't write until the model has recognised at least one activity
        if (lastSyncedActivity.isEmpty()) {
            Log.d(TAG, "Sync skipped – waiting for first activity recognition");
            return;
        }

        String userId = currentUser.getUid();

        Map<String, Object> status = new HashMap<>();
        status.put("activity",   lastSyncedActivity);
        status.put("confidence", lastSyncedConfidence);
        status.put("battery",    getBatteryLevel());
        status.put("monitoring", true);
        status.put("fall_alert", false);
        status.put("timestamp",  System.currentTimeMillis());

        mDatabase.child("users").child(userId).child("current_status")
                .setValue(status)
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "✅ Synced: " + lastSyncedActivity);
                    runOnUiThread(() -> {
                        if (syncStatusText != null)
                            syncStatusText.setText("☁ Synced: " + lastSyncedActivity);
                    });
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "⚠ Sync failed (offline?): " + e.getMessage());
                    runOnUiThread(() -> {
                        if (syncStatusText != null)
                            syncStatusText.setText("☁ Sync pending…");
                    });
                });
    }

    /**
     * PHASE 3 – FALL UPLOAD.
     *
     * Does TWO writes when a fall is detected:
     *
     * 1. fall_history/{push-key}  – permanent record, never overwritten
     *    Fields: timestamp, probability, confirmed, activity_before
     *
     * 2. current_status           – SET with fall_alert=true so a family
     *    member's dashboard can react in real time (Phase 5).
     */
    private void uploadFallToFirebase(float probability) {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null || mDatabase == null) return;

        String userId = currentUser.getUid();

        // ── Write 1: permanent fall history record ────────────
        Map<String, Object> fallData = new HashMap<>();
        fallData.put("timestamp",       System.currentTimeMillis());
        fallData.put("probability",     probability);
        fallData.put("confirmed",       true);
        fallData.put("activity_before", lastSyncedActivity);

        mDatabase.child("users").child(userId).child("fall_history")
                .push()
                .setValue(fallData)
                .addOnSuccessListener(unused -> Log.d(TAG, "✅ Fall saved to history"))
                .addOnFailureListener(e      -> Log.e(TAG, "❌ Fall history save failed: " + e.getMessage()));

        // ── Write 2: current_status with fall_alert flag ──────
        Map<String, Object> alertStatus = new HashMap<>();
        alertStatus.put("activity",   "FALL_DETECTED");
        alertStatus.put("confidence", probability);
        alertStatus.put("battery",    getBatteryLevel());
        alertStatus.put("monitoring", true);
        alertStatus.put("fall_alert", true);          // Phase 5 dashboard reads this
        alertStatus.put("timestamp",  System.currentTimeMillis());

        mDatabase.child("users").child(userId).child("current_status")
                .setValue(alertStatus)
                .addOnSuccessListener(unused -> Log.d(TAG, "✅ Fall alert pushed to status"))
                .addOnFailureListener(e      -> Log.e(TAG, "❌ Alert status push failed: " + e.getMessage()));
    }

    /**
     * PHASE 3 – OFFLINE STATUS.
     *
     * When monitoring stops, writes monitoring=false so the family
     * dashboard knows the person is no longer being tracked.
     */
    private void uploadOfflineStatus() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null || mDatabase == null) return;

        String userId = currentUser.getUid();

        Map<String, Object> offline = new HashMap<>();
        offline.put("activity",   "Not monitoring");
        offline.put("monitoring", false);
        offline.put("fall_alert", false);
        offline.put("timestamp",  System.currentTimeMillis());

        mDatabase.child("users").child(userId).child("current_status")
                .setValue(offline)
                .addOnSuccessListener(unused -> Log.d(TAG, "✅ Offline status uploaded"))
                .addOnFailureListener(e      -> Log.w(TAG, "Offline status upload failed: " + e.getMessage()));
    }

    /**
     * Returns device battery % using BatteryManager API.
     * Returns -1 if unavailable.
     */
    private int getBatteryLevel() {
        try {
            android.os.BatteryManager bm =
                    (android.os.BatteryManager) getSystemService(BATTERY_SERVICE);
            if (bm != null)
                return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY);
        } catch (Exception e) {
            Log.w(TAG, "Battery level unavailable: " + e.getMessage());
        }
        return -1;
    }

    // ═════════════════════════════════════════════════════════
    //  MONITORING CONTROLS
    // ═════════════════════════════════════════════════════════
    private void startMonitoring() {
        if (!modelsLoaded) {
            Toast.makeText(this, "Cannot start – models not loaded!", Toast.LENGTH_SHORT).show();
            return;
        }
        if (emergencyContacts.isEmpty()) {
            Toast.makeText(this, "⚠ Warning: No emergency contacts set!", Toast.LENGTH_LONG).show();
        }
        if (!checkPermissions()) {
            Toast.makeText(this, "Please grant all permissions", Toast.LENGTH_SHORT).show();
            requestPermissions();
            return;
        }

        isMonitoring = true;
        fallDetected = false;

        synchronized (ax) { ax.clear(); ay.clear(); az.clear(); }
        synchronized (gx) { gx.clear(); gy.clear(); gz.clear(); }

        lastDisplayedActivity = "";
        lastSyncedActivity    = "";   // reset sync state too
        activityConfirmCount  = 0;
        fallConfirmCount      = 0;

        if (accel != null && gyro != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, gyro,  SensorManager.SENSOR_DELAY_GAME);
        }

        // PHASE 3: kick off the 30-second upload loop
        startSyncLoop();

        statusText.setText("✓ Monitoring Active");
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator_active);
        if (syncStatusText != null) syncStatusText.setText("☁ Syncing…");
        updateButtonStates();
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show();
    }

    private void stopMonitoring() {
        isMonitoring = false;
        fallDetected = false;
        sensorManager.unregisterListener(this);
        alertHandler.removeCallbacksAndMessages(null);

        // PHASE 3: stop loop then mark user offline in Firebase
        stopSyncLoop();
        uploadOfflineStatus();

        synchronized (ax) { ax.clear(); ay.clear(); az.clear(); }
        synchronized (gx) { gx.clear(); gy.clear(); gz.clear(); }

        fallConfirmCount     = 0;
        activityConfirmCount = 0;

        statusText.setText("○ Monitoring Stopped");
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator);
        activityText.setText("Activity: Waiting...");
        confidenceText.setText("Confidence: 0%");
        sensorDataText.setText("Accel: 0.00 m/s²");
        if (syncStatusText != null) syncStatusText.setText("☁ Not syncing");
        updateButtonStates();
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show();
    }

    private void cancelAlert() {
        fallDetected     = false;
        fallConfirmCount = 0;
        alertHandler.removeCallbacksAndMessages(null);
        statusText.setText("✓ Monitoring Active");
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        emergencyButton.setEnabled(false);
        Toast.makeText(this, "Alert cancelled", Toast.LENGTH_SHORT).show();

        // Restore normal (non-alert) status in Firebase
        uploadCurrentStatus();
    }

    // ═════════════════════════════════════════════════════════
    //  FALL DETECTED
    // ═════════════════════════════════════════════════════════
    private void onFallDetected(float probability) {
        // PHASE 3: save to history + push alert flag to Firebase immediately
        uploadFallToFirebase(probability);

        runOnUiThread(() -> {
            statusText.setText(String.format(
                    "⚠ FALL DETECTED (%.0f%%) – Alert in 10s", probability * 100));
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            emergencyButton.setEnabled(true);
            Toast.makeText(this, "FALL DETECTED! Cancel within 10 seconds",
                    Toast.LENGTH_LONG).show();
        });

        alertHandler.postDelayed(() -> {
            if (fallDetected) sendEmergencySMS();
        }, ALERT_DELAY_MS);
    }

    // ═════════════════════════════════════════════════════════
    //  FIREBASE – LOAD PROFILE  (Steps 2 & 3 from previous build)
    // ═════════════════════════════════════════════════════════
    private void loadUserFromFirebase(FirebaseUser currentUser) {
        if (mDatabase == null) return;

        mDatabase.child("users").child(currentUser.getUid()).get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) return;
                    try {
                        User firebaseUser = snapshot.getValue(User.class);
                        if (firebaseUser == null) return;

                        runOnUiThread(() -> {
                            if (headerWelcomeText != null)
                                headerWelcomeText.setText("Welcome, " + firebaseUser.getName() + "!");

                            if (userProfileText != null) {
                                userProfileText.setText(String.format(
                                        "👤 %s\n🎂 Age: %d | ⚧ %s\n📱 %s\n✉️ %s",
                                        firebaseUser.getName(), firebaseUser.getAge(),
                                        firebaseUser.getGender(), firebaseUser.getPhone(),
                                        firebaseUser.getEmail()));
                                userProfileText.setTextColor(ContextCompat.getColor(
                                        MainActivity.this, android.R.color.holo_green_dark));
                            }
                        });
                        Log.d(TAG, "✅ Firebase profile loaded: " + firebaseUser.getName());
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing Firebase user: " + e.getMessage());
                    }
                })
                .addOnFailureListener(e ->
                        Log.w(TAG, "Firebase profile load failed: " + e.getMessage()));
    }

    private void updateWelcomeHeader(FirebaseUser user) {
        if (headerWelcomeText == null) return;
        headerWelcomeText.setText("Welcome, " +
                (user.getEmail() != null ? user.getEmail() : "User"));
    }

    // ═════════════════════════════════════════════════════════
    //  AUTH HELPERS
    // ═════════════════════════════════════════════════════════
    private void navigateToLogin() {
        startActivity(new Intent(MainActivity.this, LoginActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        finish();
    }

    private void logoutUser() {
        if (isMonitoring) stopMonitoring(); // stops sync loop + uploads offline status
        mAuth.signOut();
        Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show();
        navigateToLogin();
    }

    // ═════════════════════════════════════════════════════════
    //  UI HELPERS
    // ═════════════════════════════════════════════════════════
    private void updateButtonStates() {
        startButton.setEnabled(!isMonitoring);
        stopButton.setEnabled(isMonitoring);
        emergencyButton.setEnabled(fallDetected);
        manageContactsButton.setEnabled(!isMonitoring);
        viewProfileButton.setEnabled(!isMonitoring);
        if (logoutButton != null) logoutButton.setEnabled(!isMonitoring);

        startButton.setAlpha(isMonitoring ? 0.5f : 1.0f);
        stopButton.setAlpha(isMonitoring ? 1.0f : 0.5f);
        manageContactsButton.setAlpha(isMonitoring ? 0.5f : 1.0f);
        viewProfileButton.setAlpha(isMonitoring ? 0.5f : 1.0f);
        if (logoutButton != null) logoutButton.setAlpha(isMonitoring ? 0.5f : 1.0f);
    }

    private void loadUserProfile() {
        try {
            String json = prefs.getString(PROFILE_KEY, null);
            userProfile = (json != null) ? gson.fromJson(json, UserProfile.class) : null;
        } catch (Exception e) {
            userProfile = null;
        }
    }

    private void updateProfileDisplay() {
        if (userProfileText == null) return;
        if (userProfile == null) {
            userProfileText.setText("Loading profile…");
            userProfileText.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        } else {
            userProfileText.setText(String.format("👤 %s\n🎂 Age: %d | 🩸 %s | 📱 %s",
                    userProfile.getFullName(), userProfile.getAge(),
                    userProfile.getBloodGroup(), userProfile.getPhone()));
            userProfileText.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }
    }

    private void openProfileView() {
        startActivityForResult(
                new Intent(this, ProfileSetupActivity.class), PROFILE_REQUEST_CODE);
    }

    private void loadEmergencyContacts() {
        try {
            String json = prefs.getString(CONTACTS_KEY, null);
            if (json != null) {
                Type type = new TypeToken<List<EmergencyContact>>(){}.getType();
                emergencyContacts = gson.fromJson(json, type);
            } else {
                emergencyContacts = new ArrayList<>();
            }
        } catch (Exception e) {
            emergencyContacts = new ArrayList<>();
        }
    }

    private void updateEmergencyContactsDisplay() {
        if (emergencyContactsText == null) return;
        if (emergencyContacts.isEmpty()) {
            emergencyContactsText.setText(
                    "No emergency contacts added\nTap 'Manage Contacts' to add");
            emergencyContactsText.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < emergencyContacts.size(); i++) {
                EmergencyContact c = emergencyContacts.get(i);
                sb.append("📱 ").append(c.getName())
                        .append(" (").append(c.getPhone()).append(")");
                if (i < emergencyContacts.size() - 1) sb.append("\n");
            }
            emergencyContactsText.setText(sb.toString());
            emergencyContactsText.setTextColor(
                    ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }
    }

    private void openContactsManager() {
        startActivityForResult(
                new Intent(this, EmergencyContactsActivity.class), CONTACTS_REQUEST_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CONTACTS_REQUEST_CODE && resultCode == RESULT_OK) {
            loadEmergencyContacts();
            updateEmergencyContactsDisplay();
            Toast.makeText(this, "Contacts updated", Toast.LENGTH_SHORT).show();
        } else if (requestCode == PROFILE_REQUEST_CODE && resultCode == RESULT_OK) {
            loadUserProfile();
            updateProfileDisplay();
            Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
        }
    }

    // ═════════════════════════════════════════════════════════
    //  SENSOR CALLBACKS
    // ═════════════════════════════════════════════════════════
    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isMonitoring) return;
        try {
            if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
                lastAccel[0] = event.values[0];
                lastAccel[1] = event.values[1];
                lastAccel[2] = event.values[2];
                synchronized (ax) {
                    ax.add(event.values[0]);
                    ay.add(event.values[1]);
                    az.add(event.values[2]);
                }
                final float magnitude = calculateMagnitude(lastAccel);
                runOnUiThread(() -> {
                    if (sensorDataText != null)
                        sensorDataText.setText(String.format(
                                "Accel: %.2f m/s² | Samples: %d", magnitude, ax.size()));
                });
            } else if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
                lastGyro[0] = event.values[0];
                lastGyro[1] = event.values[1];
                lastGyro[2] = event.values[2];
                synchronized (gx) {
                    gx.add(event.values[0]);
                    gy.add(event.values[1]);
                    gz.add(event.values[2]);
                }
            }

            trimBuffers();

            long now     = SystemClock.elapsedRealtime();
            int  minSize = Math.min(ax.size(), gx.size());
            if (now - lastInferenceTime > INFERENCE_INTERVAL_MS && minSize >= KUHAR_WINDOW) {
                lastInferenceTime = now;
                new Thread(() -> {
                    try {
                        runKuhar();
                        if (minSize >= SISFALL_WINDOW) runSisfallWithValidation();
                    } catch (Exception e) {
                        Log.e(TAG, "Inference error: " + e.getMessage(), e);
                    }
                }).start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Sensor error: " + e.getMessage(), e);
        }
    }

    // ═════════════════════════════════════════════════════════
    //  INFERENCE
    // ═════════════════════════════════════════════════════════
    private float calculateMagnitude(float[] v) {
        return (float) Math.sqrt(v[0]*v[0] + v[1]*v[1] + v[2]*v[2]);
    }

    private float calculateVariance(List<Float> data, int windowSize) {
        if (data.size() < windowSize) return 0;
        int start = data.size() - windowSize;
        float sum = 0;
        synchronized (data) { for (int i = start; i < data.size(); i++) sum += data.get(i); }
        float mean = sum / windowSize, variance = 0;
        synchronized (data) {
            for (int i = start; i < data.size(); i++) {
                float d = data.get(i) - mean; variance += d * d;
            }
        }
        return variance / windowSize;
    }

    private float getMaxMagnitudeInWindow(int windowSize) {
        if (ax.size() < windowSize) return 0;
        float maxMag = 0;
        int start = ax.size() - windowSize;
        synchronized (ax) {
            for (int i = start; i < ax.size(); i++) {
                float mag = (float) Math.sqrt(
                        ax.get(i)*ax.get(i) + ay.get(i)*ay.get(i) + az.get(i)*az.get(i));
                if (mag > maxMag) maxMag = mag;
            }
        }
        return maxMag;
    }

    private String simplifyActivity(String activity) {
        if (activity.contains("Walking"))  return "Walking";
        if (activity.contains("Standing") || activity.equals("Stand to Sit")
                || activity.equals("Stand to Lie")) return "Standing";
        if (activity.contains("Sitting")  || activity.equals("Sit to Stand")
                || activity.equals("Sit to Lie"))   return "Sitting";
        if (activity.contains("Lying")    || activity.equals("Lie to Sit")
                || activity.equals("Lie to Stand")) return "Lying Down";
        if (activity.contains("Turning")) return "Turning";
        return activity;
    }

    private float normalizeValueKuhar(float value) {
        return (value - TRAINING_MEAN) / TRAINING_STD;
    }

    private void runKuhar() {
        if (kuharTflite == null) return;
        try {
            float accelMag = calculateMagnitude(lastAccel);
            float variance = calculateVariance(ax, 100);
            float gyroMag  = calculateMagnitude(lastGyro);
            boolean isStationary = (Math.abs(accelMag - 9.8f) < 1.2f)
                    && (variance < 0.3f) && (gyroMag < 0.2f);

            float[][][] input = new float[1][KUHAR_WINDOW][6];
            synchronized (ax) {
                if (ax.size() < KUHAR_WINDOW) return;
                int offset = Math.max(0, ax.size() - KUHAR_WINDOW);
                for (int i = 0; i < KUHAR_WINDOW; i++) {
                    int idx = offset + i;
                    input[0][i][0] = normalizeValueKuhar(ax.get(idx));
                    input[0][i][1] = normalizeValueKuhar(ay.get(idx));
                    input[0][i][2] = normalizeValueKuhar(az.get(idx));
                }
            }
            synchronized (gx) {
                if (gx.size() < KUHAR_WINDOW) return;
                int offset = Math.max(0, gx.size() - KUHAR_WINDOW);
                for (int i = 0; i < KUHAR_WINDOW; i++) {
                    int idx = offset + i;
                    input[0][i][3] = normalizeValueKuhar(gx.get(idx));
                    input[0][i][4] = normalizeValueKuhar(gy.get(idx));
                    input[0][i][5] = normalizeValueKuhar(gz.get(idx));
                }
            }

            float[][] output = new float[1][18];
            kuharTflite.run(input, output);

            int best = 0; float max = output[0][0];
            for (int i = 1; i < 18; i++) {
                if (output[0][i] > max) { max = output[0][i]; best = i; }
            }

            String activity        = KUHAR_LABELS[best];
            final float confidence = max;

            if (isStationary) {
                boolean isDynamic = activity.contains("Walking") || activity.contains("Running")
                        || activity.contains("Jumping") || activity.contains("Turning")
                        || activity.contains("to");
                if (isDynamic) {
                    float sit = output[0][7], stand = output[0][8], lie = output[0][9];
                    if      (sit   >= stand && sit   >= lie   && sit   > 0.1f) activity = "Sitting";
                    else if (stand >= sit   && stand >= lie   && stand > 0.1f) activity = "Standing";
                    else if (lie   >= sit   && lie   >= stand && lie   > 0.1f) activity = "Lying Down";
                    else    activity = determineStaticActivity();
                }
            }

            final String displayActivity = simplifyActivity(activity);
            final float  finalConfidence = confidence;

            if (displayActivity.equals(lastDisplayedActivity)) {
                activityConfirmCount++;
            } else {
                lastDisplayedActivity = displayActivity;
                activityConfirmCount  = 1;
            }

            // PHASE 3: always update sync fields so the 30-sec loop
            // has the freshest values without needing its own inference
            lastSyncedActivity   = displayActivity;
            lastSyncedConfidence = finalConfidence;

            if (activityConfirmCount >= ACTIVITY_CONFIRM_THRESHOLD) {
                runOnUiThread(() -> {
                    if (activityText   != null) activityText.setText("Activity: " + displayActivity);
                    if (confidenceText != null) confidenceText.setText(
                            String.format("Confidence: %.1f%%", finalConfidence * 100));
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Kuhar error: " + e.getMessage(), e);
        }
    }

    private String determineStaticActivity() {
        float absX = Math.abs(lastAccel[0]);
        float absY = Math.abs(lastAccel[1]);
        float absZ = Math.abs(lastAccel[2]);
        if (absZ > absX && absZ > absY && absZ > 7.0f) return "Lying Down";
        if (absY > absX && absY > absZ) return (absY > 8.5f) ? "Standing" : "Sitting";
        if (absX > absY && absX > absZ && absX > 6.0f) return "Lying Down";
        double angle = Math.toDegrees(Math.acos(Math.min(absY / 9.8, 1.0)));
        return (angle < 20) ? "Standing" : (angle > 70) ? "Lying Down" : "Sitting";
    }

    private void runSisfallWithValidation() {
        if (sisfallTflite == null) return;
        try {
            if (calculateVariance(ax, 50) < MOVEMENT_THRESHOLD)        { fallConfirmCount = 0; return; }
            if (getMaxMagnitudeInWindow(30) < MIN_IMPACT_THRESHOLD)     { fallConfirmCount = 0; return; }
            if (calculateMagnitude(lastGyro) < GYRO_ROTATION_THRESHOLD) { fallConfirmCount = 0; return; }

            float[][][] input = new float[1][SISFALL_WINDOW][6];
            synchronized (ax) {
                if (ax.size() < SISFALL_WINDOW) return;
                int offset = Math.max(0, ax.size() - SISFALL_WINDOW);
                for (int i = 0; i < SISFALL_WINDOW; i++) {
                    int idx = offset + i;
                    input[0][i][0] = ax.get(idx);
                    input[0][i][1] = ay.get(idx);
                    input[0][i][2] = az.get(idx);
                }
            }
            synchronized (gx) {
                if (gx.size() < SISFALL_WINDOW) return;
                int offset = Math.max(0, gx.size() - SISFALL_WINDOW);
                for (int i = 0; i < SISFALL_WINDOW; i++) {
                    int idx = offset + i;
                    input[0][i][3] = gx.get(idx);
                    input[0][i][4] = gy.get(idx);
                    input[0][i][5] = gz.get(idx);
                }
            }

            float[][] output = new float[1][2];
            sisfallTflite.run(input, output);
            final float fallProb = output[0][1];

            if (fallProb > FALL_THRESHOLD) {
                fallConfirmCount++;
                if (fallConfirmCount >= FALL_CONFIRM_THRESHOLD && !fallDetected) {
                    fallDetected = true;
                    onFallDetected(fallProb);
                }
            } else {
                fallConfirmCount = 0;
            }
        } catch (Exception e) {
            Log.e(TAG, "Sisfall error: " + e.getMessage(), e);
        }
    }

    // ═════════════════════════════════════════════════════════
    //  EMERGENCY SMS
    // ═════════════════════════════════════════════════════════
    private void sendEmergencySMS() {
        if (!checkPermissions()) {
            Toast.makeText(this, "Cannot send SMS – permission denied", Toast.LENGTH_SHORT).show();
            return;
        }
        if (emergencyContacts.isEmpty()) {
            Toast.makeText(this, "Cannot send SMS – no emergency contacts", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            SmsManager smsManager = SmsManager.getDefault();
            StringBuilder msg = new StringBuilder("🚨 EMERGENCY ALERT 🚨\nFall detected!\n\n");
            if (userProfile != null) {
                msg.append("Name: ").append(userProfile.getFullName()).append("\n");
                msg.append("Age: ").append(userProfile.getAge()).append("\n");
                msg.append("Gender: ").append(userProfile.getGender()).append("\n");
                msg.append("Blood Group: ").append(userProfile.getBloodGroup()).append("\n");
                msg.append("Phone: ").append(userProfile.getPhone()).append("\n\n");
            }
            msg.append("Please check on them immediately!\n- ResQMotion");
            String message = msg.toString();

            int ok = 0, fail = 0;
            for (EmergencyContact contact : emergencyContacts) {
                try {
                    if (message.length() > 160) {
                        smsManager.sendMultipartTextMessage(contact.getPhone(), null,
                                smsManager.divideMessage(message), null, null);
                    } else {
                        smsManager.sendTextMessage(
                                contact.getPhone(), null, message, null, null);
                    }
                    ok++;
                } catch (Exception e) {
                    Log.e(TAG, "SMS to " + contact.getName() + " failed: " + e.getMessage());
                    fail++;
                }
            }
            final int sent = ok, failed = fail;
            runOnUiThread(() -> {
                Toast.makeText(this,
                        failed == 0
                                ? "✓ SMS sent to " + sent + " contact(s)"
                                : "⚠ Sent: " + sent + " | Failed: " + failed,
                        Toast.LENGTH_LONG).show();
                statusText.setText("✓ Alert Sent – Monitoring Active");
                statusText.setTextColor(
                        ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            });

            fallDetected = false;
            fallConfirmCount = 0;
            emergencyButton.setEnabled(false);

        } catch (Exception e) {
            Log.e(TAG, "SMS send failed: " + e.getMessage(), e);
            Toast.makeText(this, "Failed to send emergency SMS", Toast.LENGTH_SHORT).show();
        }
    }

    // ═════════════════════════════════════════════════════════
    //  UTILITIES
    // ═════════════════════════════════════════════════════════
    private void trimBuffers() {
        int max = KUHAR_WINDOW + 50;
        synchronized (ax) {
            while (ax.size() > max) { ax.remove(0); ay.remove(0); az.remove(0); }
        }
        synchronized (gx) {
            while (gx.size() > max) { gx.remove(0); gy.remove(0); gz.remove(0); }
        }
    }

    private MappedByteBuffer loadModel(String modelName) throws IOException {
        AssetFileDescriptor fd = getAssets().openFd(modelName);
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        return fis.getChannel().map(FileChannel.MapMode.READ_ONLY,
                fd.getStartOffset(), fd.getDeclaredLength());
    }

    private void loadModels() {
        try {
            kuharTflite   = new Interpreter(loadModel(KUHAR_MODEL));
            sisfallTflite = new Interpreter(loadModel(SISFALL_MODEL));
            modelsLoaded  = true;
            Toast.makeText(this, "✓ AI Models loaded", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            modelsLoaded = false;
            Toast.makeText(this, "ERROR: Failed to load models", Toast.LENGTH_LONG).show();
        }
    }

    private void requestPermissions() {
        ActivityCompat.requestPermissions(this, new String[]{
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        }, PERMISSION_REQ_CODE);
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean all = true;
            for (int r : grantResults)
                if (r != PackageManager.PERMISSION_GRANTED) { all = false; break; }
            Toast.makeText(this,
                    all ? "Permissions granted" : "Some permissions denied",
                    Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}