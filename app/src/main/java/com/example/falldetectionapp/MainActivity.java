//  new main activity.java code for splash and profile setup

package com.example.falldetectionapp;

import android.Manifest;
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
import java.util.List;

public class MainActivity extends AppCompatActivity implements SensorEventListener {

    private static final String TAG = "FallApp";
    private static final int PERMISSION_REQ_CODE = 101;
    private static final int CONTACTS_REQUEST_CODE = 102;
    private static final int PROFILE_REQUEST_CODE = 103;

    private static final String KUHAR_MODEL = "kuhar_cnn_lstm_corrected.tflite";
    private static final String SISFALL_MODEL = "best_cnn_lstm_model.tflite";
    private static final int KUHAR_WINDOW = 300;
    private static final int SISFALL_WINDOW = 200;
    private static final float TRAINING_MEAN = 4.1497445f;
    private static final float TRAINING_STD = 3906.263f;
    private static final float FALL_THRESHOLD = 0.82f;
    private static final float MIN_IMPACT_THRESHOLD = 15.0f;
    private static final float GYRO_ROTATION_THRESHOLD = 0.5f;
    private static final float MOVEMENT_THRESHOLD = 0.5f;
    private static final long INFERENCE_INTERVAL_MS = 500;
    private static final long ALERT_DELAY_MS = 10000;
    private static final int ACTIVITY_CONFIRM_THRESHOLD = 1;
    private static final int FALL_CONFIRM_THRESHOLD = 2;

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
    private float[] lastGyro = new float[3];
    private long lastInferenceTime = 0;
    private boolean isMonitoring = false;
    private boolean fallDetected = false;
    private Handler alertHandler = new Handler();
    private boolean modelsLoaded = false;
    private String lastDisplayedActivity = "";
    private int activityConfirmCount = 0;
    private int fallConfirmCount = 0;

    private TextView activityText, confidenceText, statusText, sensorDataText,
            emergencyContactsText, userProfileText;
    private Button startButton, stopButton, emergencyButton, manageContactsButton, viewProfileButton;
    private View statusIndicator;

    private SharedPreferences prefs;
    private static final String PREFS_NAME = "FallDetectionPrefs";
    private static final String CONTACTS_KEY = "emergency_contacts_json";
    private static final String PROFILE_KEY = "user_profile";
    private List<EmergencyContact> emergencyContacts = new ArrayList<>();
    private UserProfile userProfile;
    private Gson gson;

    private static final String[] KUHAR_LABELS = {
            "Walking Forward", "Walking Left", "Walking Right",
            "Walking Upstairs", "Walking Downstairs",
            "Running", "Jumping", "Sitting", "Standing", "Lying Down",
            "Stand to Sit", "Sit to Stand", "Sit to Lie",
            "Lie to Sit", "Stand to Lie", "Lie to Stand",
            "Turning Left", "Turning Right"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        gson = new Gson();
        loadEmergencyContacts();
        loadUserProfile();

        activityText = findViewById(R.id.activityText);
        confidenceText = findViewById(R.id.confidenceText);
        statusText = findViewById(R.id.statusText);
        sensorDataText = findViewById(R.id.sensorDataText);
        emergencyContactsText = findViewById(R.id.emergencyContactsText);
        userProfileText = findViewById(R.id.userProfileText);
        statusIndicator = findViewById(R.id.statusIndicator);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        emergencyButton = findViewById(R.id.emergencyButton);
        manageContactsButton = findViewById(R.id.manageContactsButton);
        viewProfileButton = findViewById(R.id.viewProfileButton);

        startButton.setOnClickListener(v -> startMonitoring());
        stopButton.setOnClickListener(v -> stopMonitoring());
        emergencyButton.setOnClickListener(v -> cancelAlert());
        manageContactsButton.setOnClickListener(v -> openContactsManager());
        viewProfileButton.setOnClickListener(v -> openProfileView());

        updateButtonStates();
        updateEmergencyContactsDisplay();
        updateProfileDisplay();
        requestPermissions();
        loadModels();

        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);

        if (accel == null || gyro == null) {
            Toast.makeText(this, "ERROR: Required sensors not available!", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Sensors not available");
        } else {
            Log.d(TAG, "Sensors initialized successfully");
        }
    }

    private void loadUserProfile() {
        try {
            String json = prefs.getString(PROFILE_KEY, null);
            if (json != null) {
                userProfile = gson.fromJson(json, UserProfile.class);
                Log.d(TAG, "Loaded profile: " + userProfile.getFullName());
            } else {
                userProfile = null;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error loading profile: " + e.getMessage());
            userProfile = null;
        }
    }

    private void updateProfileDisplay() {
        if (userProfileText == null) return;

        if (userProfile == null) {
            userProfileText.setText("No profile set\nTap 'View Profile' to setup");
            userProfileText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        } else {
            String display = String.format("👤 %s\n🎂 Age: %d | 🩸 %s | 📱 %s",
                    userProfile.getFullName(),
                    userProfile.getAge(),
                    userProfile.getBloodGroup(),
                    userProfile.getPhone());
            userProfileText.setText(display);
            userProfileText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }
    }

    private void openProfileView() {
        Intent intent = new Intent(this, ProfileSetupActivity.class);
        startActivityForResult(intent, PROFILE_REQUEST_CODE);
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
            Log.d(TAG, "Loaded " + emergencyContacts.size() + " emergency contacts");
        } catch (Exception e) {
            Log.e(TAG, "Error loading contacts: " + e.getMessage());
            emergencyContacts = new ArrayList<>();
        }
    }

    private void updateEmergencyContactsDisplay() {
        if (emergencyContactsText == null) return;

        if (emergencyContacts.isEmpty()) {
            emergencyContactsText.setText("No emergency contacts added\nTap 'Manage Contacts' to add");
            emergencyContactsText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
        } else {
            StringBuilder display = new StringBuilder();
            for (int i = 0; i < emergencyContacts.size(); i++) {
                EmergencyContact contact = emergencyContacts.get(i);
                display.append("📱 ").append(contact.getName())
                        .append(" (").append(contact.getPhone()).append(")");
                if (i < emergencyContacts.size() - 1) {
                    display.append("\n");
                }
            }
            emergencyContactsText.setText(display.toString());
            emergencyContactsText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        }
    }

    private void openContactsManager() {
        Intent intent = new Intent(this, EmergencyContactsActivity.class);
        startActivityForResult(intent, CONTACTS_REQUEST_CODE);
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

    private void loadModels() {
        try {
            kuharTflite = new Interpreter(loadModel(KUHAR_MODEL));
            sisfallTflite = new Interpreter(loadModel(SISFALL_MODEL));
            modelsLoaded = true;
            Toast.makeText(this, "✓ AI Models loaded", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            modelsLoaded = false;
            Log.e(TAG, "Model loading failed: " + e.getMessage());
            Toast.makeText(this, "ERROR: Failed to load models", Toast.LENGTH_LONG).show();
        }
    }

    private void startMonitoring() {
        if (!modelsLoaded) {
            Toast.makeText(this, "Cannot start - Models not loaded!", Toast.LENGTH_SHORT).show();
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

        synchronized (ax) {
            ax.clear();
            ay.clear();
            az.clear();
        }
        synchronized (gx) {
            gx.clear();
            gy.clear();
            gz.clear();
        }

        lastDisplayedActivity = "";
        activityConfirmCount = 0;
        fallConfirmCount = 0;

        if (accel != null && gyro != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        }

        statusText.setText("✓ Monitoring Active");
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator_active);
        updateButtonStates();
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show();
    }

    private void stopMonitoring() {
        isMonitoring = false;
        fallDetected = false;
        sensorManager.unregisterListener(this);
        alertHandler.removeCallbacksAndMessages(null);

        synchronized (ax) {
            ax.clear();
            ay.clear();
            az.clear();
        }
        synchronized (gx) {
            gx.clear();
            gy.clear();
            gz.clear();
        }

        fallConfirmCount = 0;
        activityConfirmCount = 0;

        statusText.setText("○ Monitoring Stopped");
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        statusIndicator.setBackgroundResource(R.drawable.circle_indicator);
        activityText.setText("Activity: Waiting...");
        confidenceText.setText("Confidence: 0%");
        sensorDataText.setText("Accel: 0.00 m/s²");
        updateButtonStates();
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show();
    }

    private void cancelAlert() {
        fallDetected = false;
        fallConfirmCount = 0;
        alertHandler.removeCallbacksAndMessages(null);
        statusText.setText("✓ Monitoring Active");
        statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        emergencyButton.setEnabled(false);
        Toast.makeText(this, "Alert cancelled", Toast.LENGTH_SHORT).show();
    }

    private void updateButtonStates() {
        startButton.setEnabled(!isMonitoring);
        stopButton.setEnabled(isMonitoring);
        emergencyButton.setEnabled(fallDetected);
        manageContactsButton.setEnabled(!isMonitoring);
        viewProfileButton.setEnabled(!isMonitoring);
        startButton.setAlpha(isMonitoring ? 0.5f : 1.0f);
        stopButton.setAlpha(isMonitoring ? 1.0f : 0.5f);
        manageContactsButton.setAlpha(isMonitoring ? 0.5f : 1.0f);
        viewProfileButton.setAlpha(isMonitoring ? 0.5f : 1.0f);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isMonitoring && accel != null && gyro != null) {
            sensorManager.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
            sensorManager.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME);
        }
        loadEmergencyContacts();
        updateEmergencyContactsDisplay();
        loadUserProfile();
        updateProfileDisplay();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (isMonitoring) {
            sensorManager.unregisterListener(this);
        }
    }

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
                    if (sensorDataText != null) {
                        sensorDataText.setText(String.format("Accel: %.2f m/s² | Samples: %d", magnitude, ax.size()));
                    }
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

            long now = SystemClock.elapsedRealtime();
            int minSize = Math.min(ax.size(), gx.size());

            if (now - lastInferenceTime > INFERENCE_INTERVAL_MS && minSize >= KUHAR_WINDOW) {
                lastInferenceTime = now;
                new Thread(() -> {
                    try {
                        runKuhar();
                        if (minSize >= SISFALL_WINDOW) {
                            runSisfallWithValidation();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Inference error: " + e.getMessage(), e);
                    }
                }).start();
            }
        } catch (Exception e) {
            Log.e(TAG, "Sensor processing error: " + e.getMessage(), e);
        }
    }

    private float calculateMagnitude(float[] values) {
        return (float) Math.sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]);
    }

    private float calculateVariance(List<Float> data, int windowSize) {
        if (data.size() < windowSize) return 0;
        int start = data.size() - windowSize;
        float sum = 0;
        synchronized (data) {
            for (int i = start; i < data.size(); i++) {
                sum += data.get(i);
            }
        }
        float mean = sum / windowSize;
        float variance = 0;
        synchronized (data) {
            for (int i = start; i < data.size(); i++) {
                float diff = data.get(i) - mean;
                variance += diff * diff;
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
                float mag = (float) Math.sqrt(ax.get(i) * ax.get(i) + ay.get(i) * ay.get(i) + az.get(i) * az.get(i));
                if (mag > maxMag) maxMag = mag;
            }
        }
        return maxMag;
    }

    private String simplifyActivity(String activity) {
        if (activity.contains("Walking")) return "Walking";
        if (activity.contains("Standing") || activity.equals("Stand to Sit") || activity.equals("Stand to Lie"))
            return "Standing";
        if (activity.contains("Sitting") || activity.equals("Sit to Stand") || activity.equals("Sit to Lie"))
            return "Sitting";
        if (activity.contains("Lying") || activity.equals("Lie to Sit") || activity.equals("Lie to Stand"))
            return "Lying Down";
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
            float gyroMag = calculateMagnitude(lastGyro);
            boolean isStationary = (Math.abs(accelMag - 9.8f) < 1.2f) && (variance < 0.3f) && (gyroMag < 0.2f);

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

            int best = 0;
            float max = output[0][0];
            for (int i = 1; i < 18; i++) {
                if (output[0][i] > max) {
                    max = output[0][i];
                    best = i;
                }
            }

            String activity = KUHAR_LABELS[best];
            final float confidence = max;

            if (isStationary) {
                boolean isDynamicActivity = activity.contains("Walking") || activity.contains("Running") ||
                        activity.contains("Jumping") || activity.contains("Turning") || activity.contains("to");
                if (isDynamicActivity) {
                    String orientationActivity = determineStaticActivity();
                    float sitScore = output[0][7];
                    float standScore = output[0][8];
                    float lyingScore = output[0][9];

                    if (sitScore >= standScore && sitScore >= lyingScore && sitScore > 0.1f) {
                        activity = "Sitting";
                    } else if (standScore >= sitScore && standScore >= lyingScore && standScore > 0.1f) {
                        activity = "Standing";
                    } else if (lyingScore >= sitScore && lyingScore >= standScore && lyingScore > 0.1f) {
                        activity = "Lying Down";
                    } else {
                        activity = orientationActivity;
                    }
                }
            }

            final String displayActivity = simplifyActivity(activity);

            if (displayActivity.equals(lastDisplayedActivity)) {
                activityConfirmCount++;
            } else {
                lastDisplayedActivity = displayActivity;
                activityConfirmCount = 1;
            }

            if (activityConfirmCount >= ACTIVITY_CONFIRM_THRESHOLD) {
                runOnUiThread(() -> {
                    if (activityText != null && confidenceText != null) {
                        activityText.setText("Activity: " + displayActivity);
                        confidenceText.setText(String.format("Confidence: %.1f%%", confidence * 100));
                    }
                });
            }

        } catch (Exception e) {
            Log.e(TAG, "Kuhar inference error: " + e.getMessage(), e);
        }
    }

    private String determineStaticActivity() {
        float ax = lastAccel[0];
        float ay = lastAccel[1];
        float az = lastAccel[2];
        float absX = Math.abs(ax);
        float absY = Math.abs(ay);
        float absZ = Math.abs(az);

        if (absZ > absX && absZ > absY && absZ > 7.0f) return "Lying Down";

        if (absY > absX && absY > absZ) {
            if (absY > 8.5f) return "Standing";
            else if (absY > 5.0f) return "Sitting";
            else return "Sitting";
        }

        if (absX > absY && absX > absZ && absX > 6.0f) return "Lying Down";

        double angleFromVertical = Math.toDegrees(Math.acos(Math.min(absY / 9.8, 1.0)));
        if (angleFromVertical < 20) return "Standing";
        else if (angleFromVertical > 70) return "Lying Down";
        else return "Sitting";
    }

    private void runSisfallWithValidation() {
        if (sisfallTflite == null) return;

        try {
            float variance = calculateVariance(ax, 50);
            if (variance < MOVEMENT_THRESHOLD) {
                fallConfirmCount = 0;
                return;
            }

            float maxAccel = getMaxMagnitudeInWindow(30);
            if (maxAccel < MIN_IMPACT_THRESHOLD) {
                fallConfirmCount = 0;
                return;
            }

            float gyroMag = calculateMagnitude(lastGyro);
            if (gyroMag < GYRO_ROTATION_THRESHOLD) {
                fallConfirmCount = 0;
                return;
            }

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
            final float fallProbability = output[0][1];

            if (fallProbability > FALL_THRESHOLD) {
                fallConfirmCount++;
                if (fallConfirmCount >= FALL_CONFIRM_THRESHOLD && !fallDetected) {
                    fallDetected = true;
                    onFallDetected(fallProbability);
                }
            } else {
                fallConfirmCount = 0;
            }

        } catch (Exception e) {
            Log.e(TAG, "Sisfall inference error: " + e.getMessage(), e);
        }
    }

    private void onFallDetected(float probability) {
        runOnUiThread(() -> {
            statusText.setText(String.format("⚠ FALL DETECTED (%.0f%%) - Alert in 10s", probability * 100));
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
            emergencyButton.setEnabled(true);
            Toast.makeText(this, "FALL DETECTED! Cancel within 10 seconds", Toast.LENGTH_LONG).show();
        });

        alertHandler.postDelayed(() -> {
            if (fallDetected) {
                sendEmergencySMS();
            }
        }, ALERT_DELAY_MS);
    }

    private void sendEmergencySMS() {
        if (!checkPermissions()) {
            Toast.makeText(this, "Cannot send SMS - permission denied", Toast.LENGTH_SHORT).show();
            return;
        }
        if (emergencyContacts.isEmpty()) {
            Toast.makeText(this, "Cannot send SMS - no emergency contacts", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            SmsManager smsManager = SmsManager.getDefault();

            StringBuilder messageBuilder = new StringBuilder();
            messageBuilder.append("🚨 EMERGENCY ALERT 🚨\n");
            messageBuilder.append("Fall detected!\n\n");

            if (userProfile != null) {
                messageBuilder.append("Person Details:\n");
                messageBuilder.append("Name: ").append(userProfile.getFullName()).append("\n");
                messageBuilder.append("Age: ").append(userProfile.getAge()).append(" years\n");
                messageBuilder.append("Gender: ").append(userProfile.getGender()).append("\n");
                messageBuilder.append("Blood Group: ").append(userProfile.getBloodGroup()).append("\n");
                messageBuilder.append("Phone: ").append(userProfile.getPhone()).append("\n\n");
            }

            messageBuilder.append("Please check on them immediately!\n");
            messageBuilder.append("- Fall Detection System");

            String message = messageBuilder.toString();

            int successCount = 0;
            int failCount = 0;

            for (EmergencyContact contact : emergencyContacts) {
                try {
                    if (message.length() > 160) {
                        ArrayList<String> parts = smsManager.divideMessage(message);
                        smsManager.sendMultipartTextMessage(contact.getPhone(), null, parts, null, null);
                    } else {
                        smsManager.sendTextMessage(contact.getPhone(), null, message, null, null);
                    }
                    Log.d(TAG, "Emergency SMS sent to: " + contact.getName() + " (" + contact.getPhone() + ")");
                    successCount++;
                } catch (Exception e) {
                    Log.e(TAG, "Failed to send SMS to " + contact.getName() + ": " + e.getMessage());
                    failCount++;
                }
            }

            final int sent = successCount;
            final int failed = failCount;

            runOnUiThread(() -> {
                String resultMsg;
                if (failed == 0) {
                    resultMsg = "✓ Emergency SMS sent to " + sent + " contact(s)";
                    Toast.makeText(this, resultMsg, Toast.LENGTH_LONG).show();
                } else {
                    resultMsg = "Sent to " + sent + ", Failed: " + failed;
                    Toast.makeText(this, "⚠ " + resultMsg, Toast.LENGTH_LONG).show();
                }

                statusText.setText("✓ Alert Sent - Monitoring Active");
                statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_orange_dark));
            });

            fallDetected = false;
            fallConfirmCount = 0;
            emergencyButton.setEnabled(false);

        } catch (Exception e) {
            Log.e(TAG, "Failed to send SMS: " + e.getMessage(), e);
            Toast.makeText(this, "Failed to send emergency SMS", Toast.LENGTH_SHORT).show();
        }
    }

    private void trimBuffers() {
        int max = KUHAR_WINDOW + 50;
        synchronized (ax) {
            while (ax.size() > max) {
                ax.remove(0);
                ay.remove(0);
                az.remove(0);
            }
        }
        synchronized (gx) {
            while (gx.size() > max) {
                gx.remove(0);
                gy.remove(0);
                gz.remove(0);
            }
        }
    }

    private MappedByteBuffer loadModel(String modelName) throws IOException {
        AssetFileDescriptor fd = getAssets().openFd(modelName);
        FileInputStream fis = new FileInputStream(fd.getFileDescriptor());
        FileChannel channel = fis.getChannel();
        return channel.map(FileChannel.MapMode.READ_ONLY, fd.getStartOffset(), fd.getDeclaredLength());
    }

    private void requestPermissions() {
        String[] permissions = {
                Manifest.permission.SEND_SMS,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };
        ActivityCompat.requestPermissions(this, permissions, PERMISSION_REQ_CODE);
    }

    private boolean checkPermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                Toast.makeText(this, "Permissions granted", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Some permissions denied", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (kuharTflite != null) {
            try {
                kuharTflite.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing Kuhar model: " + e.getMessage());
            }
        }
        if (sisfallTflite != null) {
            try {
                sisfallTflite.close();
            } catch (Exception e) {
                Log.e(TAG, "Error closing SisFall model: " + e.getMessage());
            }
        }
        alertHandler.removeCallbacksAndMessages(null);
    }
}