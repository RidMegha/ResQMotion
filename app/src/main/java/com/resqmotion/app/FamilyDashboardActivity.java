package com.resqmotion.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import java.util.ArrayList;
import java.util.List;

public class FamilyDashboardActivity extends AppCompatActivity
        implements FamilyMemberAdapter.OnFamilyMemberActionListener {

    private static final String TAG          = "FamilyDashboard";
    private static final String DATABASE_URL =
            "https://resqmotion-default-rtdb.asia-southeast1.firebasedatabase.app/";
    private static final int MAX_FAMILY = 5;

    // ── Firebase ──────────────────────────────────────────────
    private FirebaseAuth      mAuth;
    private DatabaseReference mDatabase;
    private String            myUid;

    // ── Listeners ─────────────────────────────────────────────
    private ValueEventListener myStatusListener;
    private DatabaseReference  myStatusRef;
    private ValueEventListener monitoringListListener;
    private DatabaseReference  monitoringListRef;
    private ValueEventListener incomingRequestsListener;
    private DatabaseReference  incomingRequestsRef;
    private final List<ValueEventListener> memberListeners  = new ArrayList<>();
    private final List<DatabaseReference>  memberStatusRefs = new ArrayList<>();

    // ── UI ────────────────────────────────────────────────────
    private TextView     myActivityText, myConfidenceText, myBatteryText,
            myStatusLabel, emptyFamilyText, pendingBadge;
    private RecyclerView familyRecyclerView;
    private Button       addFamilyButton, viewRequestsButton, myWatchersButton;

    // ── Data ──────────────────────────────────────────────────
    private final List<FamilyMember> familyMembers = new ArrayList<>();
    private FamilyMemberAdapter adapter;
    private boolean fallDialogShowing = false;

    // ═════════════════════════════════════════════════════════
    //  LIFECYCLE
    // ═════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_family_dashboard);

        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) { finish(); return; }
        myUid     = currentUser.getUid();
        mDatabase = FirebaseDatabase.getInstance(DATABASE_URL).getReference();

        // ── Bind views ────────────────────────────────────────
        myActivityText     = findViewById(R.id.myActivityText);
        myConfidenceText   = findViewById(R.id.myConfidenceText);
        myBatteryText      = findViewById(R.id.myBatteryText);
        myStatusLabel      = findViewById(R.id.myStatusLabel);
        emptyFamilyText    = findViewById(R.id.emptyFamilyText);
        pendingBadge       = findViewById(R.id.pendingBadge);
        familyRecyclerView = findViewById(R.id.familyRecyclerView);
        addFamilyButton    = findViewById(R.id.addFamilyButton);
        viewRequestsButton = findViewById(R.id.viewRequestsButton);
        myWatchersButton   = findViewById(R.id.myWatchersButton);   // NEW

        // ── RecyclerView ──────────────────────────────────────
        adapter = new FamilyMemberAdapter(this, familyMembers, this);
        familyRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        familyRecyclerView.setAdapter(adapter);

        // ── Button listeners ──────────────────────────────────
        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        addFamilyButton.setOnClickListener(v -> {
            if (familyMembers.size() >= MAX_FAMILY) {
                Toast.makeText(this,
                        "Max " + MAX_FAMILY + " family members allowed",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            startActivity(new Intent(this, AddFamilyMemberActivity.class));
        });

        viewRequestsButton.setOnClickListener(v ->
                startActivity(new Intent(this, IncomingRequestsActivity.class)));

        // NEW — opens MyWatchersActivity
        myWatchersButton.setOnClickListener(v ->
                startActivity(new Intent(this, MyWatchersActivity.class)));

        // ── Start real-time listeners ─────────────────────────
        listenToMyOwnStatus();
        listenToFamilyList();
        listenToIncomingRequests();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        detachAllListeners();
    }

    // ═════════════════════════════════════════════════════════
    //  LISTENER 1 – MY OWN STATUS (top card)
    // ═════════════════════════════════════════════════════════
    private void listenToMyOwnStatus() {
        myStatusRef      = mDatabase.child("users").child(myUid).child("current_status");
        myStatusListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                if (!snapshot.exists()) {
                    myStatusLabel.setText("○ Not monitoring");
                    myActivityText.setText("Start monitoring on the main screen");
                    myBatteryText.setText("");
                    myConfidenceText.setText("");
                    return;
                }
                try {
                    Boolean monitoring = snapshot.child("monitoring").getValue(Boolean.class);
                    String  activity   = snapshot.child("activity").getValue(String.class);
                    Double  confidence = snapshot.child("confidence").getValue(Double.class);
                    Long    battery    = snapshot.child("battery").getValue(Long.class);
                    Boolean fallAlert  = snapshot.child("fall_alert").getValue(Boolean.class);

                    if (Boolean.TRUE.equals(fallAlert)) {
                        myStatusLabel.setText("🚨 FALL DETECTED ON MY DEVICE!");
                        myActivityText.setText("Check the main screen");
                    } else if (Boolean.TRUE.equals(monitoring)) {
                        myStatusLabel.setText("✓ My Monitoring Active");
                        myActivityText.setText(activity != null ? activity : "Detecting…");
                    } else {
                        myStatusLabel.setText("○ Not monitoring");
                        myActivityText.setText("Start monitoring on the main screen");
                    }

                    myConfidenceText.setText(confidence != null
                            ? String.format("Confidence: %.1f%%", confidence * 100) : "");
                    myBatteryText.setText(battery != null && battery >= 0
                            ? "🔋 " + battery + "%" : "🔋 --");

                } catch (Exception e) {
                    Log.e(TAG, "Error reading my status: " + e.getMessage());
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "My status listener error: " + error.getMessage());
            }
        };
        myStatusRef.addValueEventListener(myStatusListener);
    }

    // ═════════════════════════════════════════════════════════
    //  LISTENER 2 – FAMILY LIST (users/{myUid}/monitoring/)
    // ═════════════════════════════════════════════════════════
    private void listenToFamilyList() {
        monitoringListRef      = mDatabase.child("users").child(myUid).child("monitoring");
        monitoringListListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                detachMemberListeners();
                familyMembers.clear();

                for (DataSnapshot memberSnap : snapshot.getChildren()) {
                    try {
                        FamilyMember member = memberSnap.getValue(FamilyMember.class);
                        if (member != null) {
                            member.setUid(memberSnap.getKey());
                            familyMembers.add(member);
                            if ("accepted".equals(member.getStatus()))
                                attachMemberStatusListener(member);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing member: " + e.getMessage());
                    }
                }

                adapter.notifyDataSetChanged();
                emptyFamilyText.setVisibility(
                        familyMembers.isEmpty() ? View.VISIBLE : View.GONE);
                updateAddButtonState();
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Family list error: " + error.getMessage());
            }
        };
        monitoringListRef.addValueEventListener(monitoringListListener);
    }

    // ═════════════════════════════════════════════════════════
    //  LISTENER 3 – EACH MEMBER'S LIVE STATUS
    // ═════════════════════════════════════════════════════════
    private void attachMemberStatusListener(FamilyMember member) {
        DatabaseReference ref = mDatabase.child("users")
                .child(member.getUid()).child("current_status");

        ValueEventListener listener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                int index = indexOfMember(member.getUid());
                if (index < 0) return;
                try {
                    FamilyMember m     = familyMembers.get(index);
                    Boolean monitoring = snapshot.child("monitoring").getValue(Boolean.class);
                    String  activity   = snapshot.child("activity").getValue(String.class);
                    Double  confidence = snapshot.child("confidence").getValue(Double.class);
                    Long    battery    = snapshot.child("battery").getValue(Long.class);
                    Boolean fallAlert  = snapshot.child("fall_alert").getValue(Boolean.class);
                    Long    timestamp  = snapshot.child("timestamp").getValue(Long.class);

                    m.setMonitoring(Boolean.TRUE.equals(monitoring));
                    m.setCurrentActivity(activity != null ? activity : "Unknown");
                    m.setCurrentConfidence(confidence != null ? confidence.floatValue() : 0f);
                    m.setCurrentBattery(battery != null ? battery.intValue() : -1);
                    m.setFallAlert(Boolean.TRUE.equals(fallAlert));
                    m.setLastUpdated(timestamp != null ? timestamp : 0L);

                    runOnUiThread(() -> adapter.notifyItemChanged(index));

                    if (Boolean.TRUE.equals(fallAlert)) showFallAlertDialog(m);

                } catch (Exception e) {
                    Log.e(TAG, "Error reading member status: " + e.getMessage());
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Member status error: " + error.getMessage());
            }
        };

        ref.addValueEventListener(listener);
        memberListeners.add(listener);
        memberStatusRefs.add(ref);
    }

    // ═════════════════════════════════════════════════════════
    //  LISTENER 4 – INCOMING REQUESTS BADGE
    // ═════════════════════════════════════════════════════════
    private void listenToIncomingRequests() {
        incomingRequestsRef      = mDatabase.child("users").child(myUid)
                .child("incoming_requests");
        incomingRequestsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                int pending = 0;
                for (DataSnapshot req : snapshot.getChildren()) {
                    String status = req.child("status").getValue(String.class);
                    if ("pending".equals(status)) pending++;
                }
                if (pending > 0) {
                    pendingBadge.setVisibility(View.VISIBLE);
                    pendingBadge.setText("🔔 " + pending + " pending request"
                            + (pending > 1 ? "s" : "") + " — tap 'Requests' to review");
                } else {
                    pendingBadge.setVisibility(View.GONE);
                }
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Requests badge error: " + error.getMessage());
            }
        };
        incomingRequestsRef.addValueEventListener(incomingRequestsListener);
    }

    // ═════════════════════════════════════════════════════════
    //  ADAPTER CALLBACKS
    // ═════════════════════════════════════════════════════════
    @Override
    public void onRemove(FamilyMember member, int position) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + member.getName() + "?")
                .setMessage("You will stop monitoring " + member.getName()
                        + ".\n\nIf you want to reconnect later, they will "
                        + "need to accept again.")
                .setPositiveButton("Remove", (dialog, which) -> {
                    // 1. Remove from my monitoring list
                    mDatabase.child("users").child(myUid)
                            .child("monitoring").child(member.getUid())
                            .removeValue();
                    // 2. Remove from their watchers
                    mDatabase.child("users").child(member.getUid())
                            .child("watchers").child(myUid)
                            .removeValue();
                    // 3. Remove from their incoming_requests (full reset)
                    mDatabase.child("users").child(member.getUid())
                            .child("incoming_requests").child(myUid)
                            .removeValue();

                    Toast.makeText(this,
                            member.getName() + " removed from family list",
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onCall(FamilyMember member) {
        try {
            startActivity(new Intent(Intent.ACTION_DIAL,
                    Uri.parse("tel:" + member.getPhone())));
        } catch (Exception e) {
            Toast.makeText(this, "Cannot open dialler", Toast.LENGTH_SHORT).show();
        }
    }

    // ═════════════════════════════════════════════════════════
    //  FALL ALERT DIALOG
    // ═════════════════════════════════════════════════════════
    private void showFallAlertDialog(FamilyMember member) {
        if (fallDialogShowing) return;
        fallDialogShowing = true;
        runOnUiThread(() ->
                new AlertDialog.Builder(this)
                        .setTitle("🚨 FALL DETECTED!")
                        .setMessage(member.getName()
                                + " may have fallen!\n\nDo you want to call them now?")
                        .setPositiveButton("📞 Call Now", (d, w) -> {
                            fallDialogShowing = false;
                            onCall(member);
                        })
                        .setNegativeButton("Dismiss", (d, w) -> fallDialogShowing = false)
                        .setCancelable(false)
                        .show()
        );
    }

    // ═════════════════════════════════════════════════════════
    //  HELPERS
    // ═════════════════════════════════════════════════════════
    private int indexOfMember(String uid) {
        for (int i = 0; i < familyMembers.size(); i++)
            if (uid.equals(familyMembers.get(i).getUid())) return i;
        return -1;
    }

    private void updateAddButtonState() {
        boolean atMax = familyMembers.size() >= MAX_FAMILY;
        addFamilyButton.setEnabled(!atMax);
        addFamilyButton.setAlpha(atMax ? 0.5f : 1.0f);
        addFamilyButton.setText(atMax
                ? "Max " + MAX_FAMILY + " reached"
                : "+ Add Family Member");
    }

    private void detachMemberListeners() {
        for (int i = 0; i < memberListeners.size(); i++)
            memberStatusRefs.get(i).removeEventListener(memberListeners.get(i));
        memberListeners.clear();
        memberStatusRefs.clear();
    }

    private void detachAllListeners() {
        detachMemberListeners();
        if (myStatusRef             != null && myStatusListener             != null)
            myStatusRef.removeEventListener(myStatusListener);
        if (monitoringListRef       != null && monitoringListListener       != null)
            monitoringListRef.removeEventListener(monitoringListListener);
        if (incomingRequestsRef     != null && incomingRequestsListener     != null)
            incomingRequestsRef.removeEventListener(incomingRequestsListener);
    }
}