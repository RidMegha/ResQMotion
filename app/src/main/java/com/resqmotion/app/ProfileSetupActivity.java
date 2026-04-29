package com.resqmotion.app;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.gson.Gson;

public class ProfileSetupActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "FallDetectionPrefs";
    private static final String KEY_PROFILE_SETUP = "profile_setup_complete";
    private static final String KEY_USER_PROFILE = "user_profile";

    private EditText firstNameInput, lastNameInput, ageInput, phoneInput;
    private Spinner genderSpinner, bloodGroupSpinner;
    private Button saveButton;

    private SharedPreferences prefs;
    private Gson gson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_profile_setup);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        gson = new Gson();

        firstNameInput = findViewById(R.id.firstNameInput);
        lastNameInput = findViewById(R.id.lastNameInput);
        ageInput = findViewById(R.id.ageInput);
        phoneInput = findViewById(R.id.phoneInput);
        genderSpinner = findViewById(R.id.genderSpinner);
        bloodGroupSpinner = findViewById(R.id.bloodGroupSpinner);
        saveButton = findViewById(R.id.saveButton);

        String[] genders = {"Select Gender", "Male", "Female", "Other"};
        ArrayAdapter<String> genderAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, genders);
        genderSpinner.setAdapter(genderAdapter);

        String[] bloodGroups = {"Select Blood Group", "A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-"};
        ArrayAdapter<String> bloodAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, bloodGroups);
        bloodGroupSpinner.setAdapter(bloodAdapter);

        loadProfile();

        saveButton.setOnClickListener(v -> saveProfile());
    }

    private void loadProfile() {
        String json = prefs.getString(KEY_USER_PROFILE, null);
        if (json != null) {
            UserProfile profile = gson.fromJson(json, UserProfile.class);

            firstNameInput.setText(profile.getFirstName());
            lastNameInput.setText(profile.getLastName());
            ageInput.setText(String.valueOf(profile.getAge()));
            phoneInput.setText(profile.getPhone());

            setSpinnerValue(genderSpinner, profile.getGender());
            setSpinnerValue(bloodGroupSpinner, profile.getBloodGroup());
        }
    }

    private void setSpinnerValue(Spinner spinner, String value) {
        ArrayAdapter adapter = (ArrayAdapter) spinner.getAdapter();
        for (int i = 0; i < adapter.getCount(); i++) {
            if (adapter.getItem(i).toString().equals(value)) {
                spinner.setSelection(i);
                break;
            }
        }
    }

    private void saveProfile() {
        String firstName = firstNameInput.getText().toString().trim();
        String lastName = lastNameInput.getText().toString().trim();
        String ageStr = ageInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim();
        String gender = genderSpinner.getSelectedItem().toString();
        String bloodGroup = bloodGroupSpinner.getSelectedItem().toString();

        if (TextUtils.isEmpty(firstName)) {
            firstNameInput.setError("First name is required");
            firstNameInput.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(lastName)) {
            lastNameInput.setError("Last name is required");
            lastNameInput.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(ageStr)) {
            ageInput.setError("Age is required");
            ageInput.requestFocus();
            return;
        }

        int age;
        try {
            age = Integer.parseInt(ageStr);
            if (age < 1 || age > 120) {
                ageInput.setError("Please enter a valid age");
                ageInput.requestFocus();
                return;
            }
        } catch (NumberFormatException e) {
            ageInput.setError("Please enter a valid age");
            ageInput.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(phone) || phone.length() < 10) {
            phoneInput.setError("Please enter a valid phone number");
            phoneInput.requestFocus();
            return;
        }

        if (gender.equals("Select Gender")) {
            Toast.makeText(this, "Please select your gender", Toast.LENGTH_SHORT).show();
            return;
        }

        if (bloodGroup.equals("Select Blood Group")) {
            Toast.makeText(this, "Please select your blood group", Toast.LENGTH_SHORT).show();
            return;
        }

        UserProfile profile = new UserProfile(firstName, lastName, age, gender, bloodGroup, phone);

        String json = gson.toJson(profile);
        prefs.edit()
                .putString(KEY_USER_PROFILE, json)
                .putBoolean(KEY_PROFILE_SETUP, true)
                .apply();

        Toast.makeText(this, "Profile saved successfully!", Toast.LENGTH_SHORT).show();

        Intent intent = new Intent(ProfileSetupActivity.this, MainActivity.class);
        startActivity(intent);
        setResult(RESULT_OK);
        finish();
    }

    @Override
    public void onBackPressed() {
        // Check if profile is already setup
        boolean profileSetup = prefs.getBoolean(KEY_PROFILE_SETUP, false);
        if (profileSetup) {
            // Allow going back if editing profile
            super.onBackPressed();
        }
        // Do nothing if first time setup (prevent going back to splash)
    }
}