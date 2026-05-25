package com.resqmotion.app;

import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class RegisterActivity extends AppCompatActivity {

    private static final String TAG = "RegisterActivity";

    // ⭐ YOUR ACTUAL DATABASE URL (Asia-Southeast region)
    private static final String DATABASE_URL = "https://resqmotion-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private EditText nameInput, emailInput, phoneInput, ageInput, passwordInput, confirmPasswordInput;
    private RadioGroup genderGroup;
    private Button registerButton;
    private TextView loginLink;
    private ProgressBar registerProgress;

    private FirebaseAuth mAuth;
    private DatabaseReference mDatabase;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        // Initialize Firebase
        try {
            mAuth = FirebaseAuth.getInstance();
            mDatabase = FirebaseDatabase.getInstance(DATABASE_URL).getReference();

            Log.d(TAG, "✅ Firebase initialized");
            Log.d(TAG, "✅ Database URL: " + DATABASE_URL);
        } catch (Exception e) {
            Log.e(TAG, "❌ Firebase init error", e);
            Toast.makeText(this, "Setup error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }

        // Initialize views
        nameInput = findViewById(R.id.nameInput);
        emailInput = findViewById(R.id.emailInput);
        phoneInput = findViewById(R.id.phoneInput);
        ageInput = findViewById(R.id.ageInput);
        passwordInput = findViewById(R.id.passwordInput);
        confirmPasswordInput = findViewById(R.id.confirmPasswordInput);
        genderGroup = findViewById(R.id.genderGroup);
        registerButton = findViewById(R.id.registerButton);
        loginLink = findViewById(R.id.loginLink);
        registerProgress = findViewById(R.id.registerProgress);

        registerButton.setOnClickListener(v -> registerUser());

        loginLink.setOnClickListener(v -> {
            Intent intent = new Intent(RegisterActivity.this, LoginActivity.class);
            startActivity(intent);
            finish();
        });
    }

    private void registerUser() {
        String name = nameInput.getText().toString().trim();
        String email = emailInput.getText().toString().trim();
        String phone = phoneInput.getText().toString().trim();
        String ageStr = ageInput.getText().toString().trim();
        String password = passwordInput.getText().toString().trim();
        String confirmPassword = confirmPasswordInput.getText().toString().trim();

        int selectedGenderId = genderGroup.getCheckedRadioButtonId();
        RadioButton selectedGenderButton = findViewById(selectedGenderId);
        String gender = selectedGenderButton.getText().toString();

        // Validation
        if (TextUtils.isEmpty(name)) {
            nameInput.setError("Name is required");
            nameInput.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(email)) {
            emailInput.setError("Email is required");
            emailInput.requestFocus();
            return;
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError("Please enter a valid email");
            emailInput.requestFocus();
            return;
        }

        if (TextUtils.isEmpty(phone)) {
            phoneInput.setError("Phone number is required");
            phoneInput.requestFocus();
            return;
        }

        if (phone.length() < 10) {
            phoneInput.setError("Please enter a valid phone number");
            phoneInput.requestFocus();
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

        if (TextUtils.isEmpty(password)) {
            passwordInput.setError("Password is required");
            passwordInput.requestFocus();
            return;
        }

        if (password.length() < 6) {
            passwordInput.setError("Password must be at least 6 characters");
            passwordInput.requestFocus();
            return;
        }

        if (!password.equals(confirmPassword)) {
            confirmPasswordInput.setError("Passwords do not match");
            confirmPasswordInput.requestFocus();
            return;
        }

        // Show progress
        registerProgress.setVisibility(View.VISIBLE);
        registerButton.setEnabled(false);

        // Create user in Firebase Authentication
        mAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        Log.d(TAG, "✅ Auth successful");

                        FirebaseUser firebaseUser = mAuth.getCurrentUser();
                        if (firebaseUser != null) {
                            String userId = firebaseUser.getUid();
                            Log.d(TAG, "✅ User ID: " + userId);

                            // Create User object
                            User user = new User(userId, name, email, phone, age, gender);

                            // Save to Database
                            mDatabase.child("users").child(userId).setValue(user)
                                    .addOnCompleteListener(dbTask -> {
                                        registerProgress.setVisibility(View.GONE);
                                        registerButton.setEnabled(true);

                                        if (dbTask.isSuccessful()) {
                                            Log.d(TAG, "✅ User saved to database");
                                            Toast.makeText(RegisterActivity.this,
                                                    "Registration successful! Welcome " + name,
                                                    Toast.LENGTH_SHORT).show();

                                            // Go to MainActivity
                                            Intent intent = new Intent(RegisterActivity.this, MainActivity.class);
                                            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                            startActivity(intent);
                                            finish();
                                        } else {
                                            Log.e(TAG, "❌ Database save failed", dbTask.getException());
                                            Toast.makeText(RegisterActivity.this,
                                                    "Failed to save user data: " +
                                                            (dbTask.getException() != null ? dbTask.getException().getMessage() : "Unknown error"),
                                                    Toast.LENGTH_LONG).show();
                                        }
                                    })
                                    .addOnFailureListener(e -> {
                                        Log.e(TAG, "❌ Database error", e);
                                        registerProgress.setVisibility(View.GONE);
                                        registerButton.setEnabled(true);
                                        Toast.makeText(RegisterActivity.this,
                                                "Database error: " + e.getMessage(),
                                                Toast.LENGTH_LONG).show();
                                    });
                        }
                    } else {
                        registerProgress.setVisibility(View.GONE);
                        registerButton.setEnabled(true);

                        Log.e(TAG, "❌ Registration failed", task.getException());

                        String errorMsg = "Registration failed";
                        if (task.getException() != null) {
                            errorMsg = task.getException().getMessage();
                        }

                        Toast.makeText(RegisterActivity.this, errorMsg, Toast.LENGTH_LONG).show();
                    }
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "❌ Auth error", e);
                    registerProgress.setVisibility(View.GONE);
                    registerButton.setEnabled(true);
                    Toast.makeText(RegisterActivity.this,
                            "Connection error: " + e.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
    }
}