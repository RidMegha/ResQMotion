package com.resqmotion.app;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;

import java.util.HashMap;
import java.util.Map;

public class AddFamilyMemberActivity extends AppCompatActivity {

    private static final String TAG          = "AddFamilyMember";
    private static final String DATABASE_URL =
            "https://resqmotion-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private FirebaseAuth      mAuth;
    private DatabaseReference mDatabase;
    private String            myUid;
    private String            myName;
    private String            myPhone;

    private EditText    codeInput, phoneEmailInput;
    private Button      sendByCodeButton, sendByContactButton, backButton;
    private ProgressBar progressBar;
    private TextView    myInviteCodeText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_family_member);

        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) { finish(); return; }

        myUid     = currentUser.getUid();
        mDatabase = FirebaseDatabase.getInstance(DATABASE_URL).getReference();

        codeInput           = findViewById(R.id.inviteCodeInput);
        phoneEmailInput     = findViewById(R.id.phoneEmailInput);
        sendByCodeButton    = findViewById(R.id.sendByCodeButton);
        sendByContactButton = findViewById(R.id.sendByContactButton);
        progressBar         = findViewById(R.id.addMemberProgress);
        myInviteCodeText    = findViewById(R.id.myInviteCodeText);
        backButton          = findViewById(R.id.backButton);

        backButton.setOnClickListener(v -> finish());
        sendByCodeButton.setOnClickListener(v -> sendRequestByCode());
        sendByContactButton.setOnClickListener(v -> sendRequestByPhoneOrEmail());

        loadMyProfile();
    }

    // ─────────────────────────────────────────────────────────
    //  LOAD MY PROFILE + INVITE CODE
    // ─────────────────────────────────────────────────────────
    private void loadMyProfile() {
        mDatabase.child("users").child(myUid).get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.exists()) return;
                    try {
                        User me = snapshot.getValue(User.class);
                        if (me != null) {
                            myName  = me.getName();
                            myPhone = me.getPhone();
                        }

                        String code = snapshot.child("invite_code").getValue(String.class);
                        if (code == null || code.isEmpty()) {
                            code = generateInviteCode(myUid);
                            mDatabase.child("users").child(myUid)
                                    .child("invite_code").setValue(code);
                        }
                        final String finalCode = code;
                        runOnUiThread(() -> myInviteCodeText.setText(
                                "Your invite code:  " + finalCode
                                        + "\n\nShare this with family members so they can add you."));
                    } catch (Exception e) {
                        Log.e(TAG, "Error loading profile: " + e.getMessage());
                    }
                })
                .addOnFailureListener(e ->
                        Log.e(TAG, "Profile load failed: " + e.getMessage()));
    }

    private String generateInviteCode(String uid) {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder code = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            int idx = Math.abs(uid.charAt(i % uid.length())) % chars.length();
            code.append(chars.charAt(idx));
        }
        return code.toString();
    }

    // ─────────────────────────────────────────────────────────
    //  OPTION A – BY INVITE CODE
    // ─────────────────────────────────────────────────────────
    private void sendRequestByCode() {
        String code = codeInput.getText().toString().trim().toUpperCase();
        if (code.length() != 6) {
            codeInput.setError("Enter a valid 6-character code");
            codeInput.requestFocus();
            return;
        }
        showLoading(true);

        Query query = mDatabase.child("users").orderByChild("invite_code").equalTo(code);
        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists() || !snapshot.hasChildren()) {
                    showLoading(false);
                    codeInput.setError("No user found with this code");
                    return;
                }
                DataSnapshot userSnap = snapshot.getChildren().iterator().next();
                processFoundUser(userSnap.getKey(), userSnap);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                showLoading(false);
                Toast.makeText(AddFamilyMemberActivity.this,
                        "Search failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ─────────────────────────────────────────────────────────
    //  OPTION B – BY PHONE OR EMAIL
    // ─────────────────────────────────────────────────────────
    private void sendRequestByPhoneOrEmail() {
        String input = phoneEmailInput.getText().toString().trim();
        if (TextUtils.isEmpty(input)) {
            phoneEmailInput.setError("Enter a phone number or email");
            phoneEmailInput.requestFocus();
            return;
        }
        showLoading(true);

        boolean isEmail = android.util.Patterns.EMAIL_ADDRESS.matcher(input).matches();
        String  field   = isEmail ? "email" : "phone";

        Query query = mDatabase.child("users").orderByChild(field).equalTo(input);
        query.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists() || !snapshot.hasChildren()) {
                    showLoading(false);
                    phoneEmailInput.setError("No user found with this " + field);
                    return;
                }
                DataSnapshot userSnap = snapshot.getChildren().iterator().next();
                processFoundUser(userSnap.getKey(), userSnap);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                showLoading(false);
                Toast.makeText(AddFamilyMemberActivity.this,
                        "Search failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ─────────────────────────────────────────────────────────
    //  PROCESS FOUND USER
    // ─────────────────────────────────────────────────────────
    private void processFoundUser(String targetUid, DataSnapshot userSnap) {
        if (myUid.equals(targetUid)) {
            showLoading(false);
            Toast.makeText(this, "You can't add yourself!", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            User targetUser = userSnap.getValue(User.class);
            if (targetUser == null) {
                showLoading(false);
                Toast.makeText(this, "User data not found", Toast.LENGTH_SHORT).show();
                return;
            }

            String targetName  = targetUser.getName();
            String targetPhone = targetUser.getPhone();
            String targetEmail = targetUser.getEmail();

            // Check if already in my monitoring list
            mDatabase.child("users").child(myUid)
                    .child("monitoring").child(targetUid).get()
                    .addOnSuccessListener(existSnap -> {
                        if (existSnap.exists()) {
                            showLoading(false);
                            String status = existSnap.child("status").getValue(String.class);
                            if ("pending".equals(status))
                                Toast.makeText(this,
                                        "Request already sent to " + targetName,
                                        Toast.LENGTH_SHORT).show();
                            else
                                Toast.makeText(this,
                                        targetName + " is already in your family list",
                                        Toast.LENGTH_SHORT).show();
                            return;
                        }
                        sendRequest(targetUid, targetName, targetPhone, targetEmail);
                    })
                    .addOnFailureListener(e -> {
                        showLoading(false);
                        Toast.makeText(this, "Error: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    });

        } catch (Exception e) {
            showLoading(false);
            Log.e(TAG, "Error processing user: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────
    //  SEND REQUEST – TWO FIREBASE WRITES
    // ─────────────────────────────────────────────────────────
    private void sendRequest(String targetUid, String targetName,
                             String targetPhone, String targetEmail) {
        long now = System.currentTimeMillis();

        // Write 1: to Dad's incoming_requests
        Map<String, Object> requestData = new HashMap<>();
        requestData.put("name",      myName  != null ? myName  : "Unknown");
        requestData.put("phone",     myPhone != null ? myPhone : "");
        requestData.put("senderUid", myUid);
        requestData.put("status",    "pending");
        requestData.put("timestamp", now);

        mDatabase.child("users").child(targetUid)
                .child("incoming_requests").child(myUid)
                .setValue(requestData)
                .addOnSuccessListener(unused -> {
                    // Write 2: to my monitoring list
                    Map<String, Object> monitorData = new HashMap<>();
                    monitorData.put("name",           targetName);
                    monitorData.put("phone",          targetPhone  != null ? targetPhone  : "");
                    monitorData.put("email",          targetEmail  != null ? targetEmail  : "");
                    monitorData.put("status",         "pending");
                    monitorData.put("connectedSince", now);

                    mDatabase.child("users").child(myUid)
                            .child("monitoring").child(targetUid)
                            .setValue(monitorData)
                            .addOnSuccessListener(u2 -> {
                                showLoading(false);
                                Toast.makeText(this,
                                        "✅ Request sent to " + targetName
                                                + "!\nWaiting for their approval.",
                                        Toast.LENGTH_LONG).show();
                                finish();
                            })
                            .addOnFailureListener(e -> {
                                showLoading(false);
                                Toast.makeText(this,
                                        "Failed to save: " + e.getMessage(),
                                        Toast.LENGTH_SHORT).show();
                            });
                })
                .addOnFailureListener(e -> {
                    showLoading(false);
                    Toast.makeText(this,
                            "Failed to send request: " + e.getMessage(),
                            Toast.LENGTH_SHORT).show();
                });
    }

    private void showLoading(boolean show) {
        runOnUiThread(() -> {
            progressBar.setVisibility(show ? View.VISIBLE : View.GONE);
            sendByCodeButton.setEnabled(!show);
            sendByContactButton.setEnabled(!show);
        });
    }
}