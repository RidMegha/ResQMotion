package com.resqmotion.app;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class IncomingRequestsActivity extends AppCompatActivity {

    private static final String TAG          = "IncomingRequests";
    private static final String DATABASE_URL =
            "https://resqmotion-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private FirebaseAuth      mAuth;
    private DatabaseReference mDatabase;
    private String            myUid;

    private RecyclerView   requestsRecycler;
    private TextView       emptyText;
    private RequestAdapter adapter;
    private ValueEventListener requestsListener;
    private DatabaseReference  requestsRef;

    private final List<RequestItem> requests = new ArrayList<>();

    static class RequestItem {
        String senderUid, name, phone, status;
        long   timestamp;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_incoming_requests);

        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) { finish(); return; }

        myUid     = currentUser.getUid();
        mDatabase = FirebaseDatabase.getInstance(DATABASE_URL).getReference();

        requestsRecycler = findViewById(R.id.requestsRecycler);
        emptyText        = findViewById(R.id.emptyRequestsText);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        adapter = new RequestAdapter();
        requestsRecycler.setLayoutManager(new LinearLayoutManager(this));
        requestsRecycler.setAdapter(adapter);

        listenToRequests();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (requestsRef != null && requestsListener != null)
            requestsRef.removeEventListener(requestsListener);
    }

    // ─────────────────────────────────────────────────────────
    private void listenToRequests() {
        requestsRef      = mDatabase.child("users").child(myUid).child("incoming_requests");
        requestsListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                requests.clear();
                for (DataSnapshot snap : snapshot.getChildren()) {
                    try {
                        RequestItem item = new RequestItem();
                        item.senderUid = snap.getKey();
                        item.name      = snap.child("name").getValue(String.class);
                        item.phone     = snap.child("phone").getValue(String.class);
                        item.status    = snap.child("status").getValue(String.class);
                        Long ts        = snap.child("timestamp").getValue(Long.class);
                        item.timestamp = ts != null ? ts : 0L;
                        if (item.name   == null) item.name   = "Unknown";
                        if (item.phone  == null) item.phone  = "";
                        if (item.status == null) item.status = "pending";
                        requests.add(item);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing request: " + e.getMessage());
                    }
                }
                adapter.notifyDataSetChanged();
                emptyText.setVisibility(requests.isEmpty() ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Requests listener error: " + error.getMessage());
            }
        };
        requestsRef.addValueEventListener(requestsListener);
    }

    // ─────────────────────────────────────────────────────────
    //  ACCEPT
    // ─────────────────────────────────────────────────────────
    private void acceptRequest(RequestItem item) {
        String senderUid = item.senderUid;
        long   now       = System.currentTimeMillis();

        // 1. Update my incoming_requests status
        mDatabase.child("users").child(myUid)
                .child("incoming_requests").child(senderUid)
                .child("status").setValue("accepted");

        // 2. Add sender to my watchers
        Map<String, Object> watcherData = new HashMap<>();
        watcherData.put("name",          item.name);
        watcherData.put("phone",         item.phone);
        watcherData.put("acceptedSince", now);
        mDatabase.child("users").child(myUid)
                .child("watchers").child(senderUid)
                .setValue(watcherData);

        // 3. Update sender's monitoring list so they see accepted
        mDatabase.child("users").child(senderUid)
                .child("monitoring").child(myUid)
                .child("status").setValue("accepted")
                .addOnSuccessListener(unused ->
                        Toast.makeText(this,
                                "✅ Accepted " + item.name
                                        + " — they can now monitor you",
                                Toast.LENGTH_LONG).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Accept failed: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    // ─────────────────────────────────────────────────────────
    //  DECLINE
    // ─────────────────────────────────────────────────────────
    private void declineRequest(RequestItem item) {
        // 1. Update my incoming_requests
        mDatabase.child("users").child(myUid)
                .child("incoming_requests").child(item.senderUid)
                .child("status").setValue("declined");

        // 2. Update sender's monitoring list
        mDatabase.child("users").child(item.senderUid)
                .child("monitoring").child(myUid)
                .child("status").setValue("declined")
                .addOnSuccessListener(unused ->
                        Toast.makeText(this,
                                "Declined request from " + item.name,
                                Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e ->
                        Toast.makeText(this,
                                "Decline failed: " + e.getMessage(),
                                Toast.LENGTH_SHORT).show());
    }

    // ─────────────────────────────────────────────────────────
    //  INLINE ADAPTER
    // ─────────────────────────────────────────────────────────
    private class RequestAdapter extends RecyclerView.Adapter<RequestAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_incoming_request, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            RequestItem item = requests.get(position);
            h.nameText.setText(item.name);
            h.phoneText.setText(item.phone);

            switch (item.status) {
                case "accepted":
                    h.statusText.setText("✓ Accepted – they can monitor you");
                    h.statusText.setTextColor(0xFF4CAF50);
                    h.acceptButton.setVisibility(View.GONE);
                    h.declineButton.setVisibility(View.GONE);
                    break;
                case "declined":
                    h.statusText.setText("✗ Declined");
                    h.statusText.setTextColor(0xFFF44336);
                    h.acceptButton.setVisibility(View.GONE);
                    h.declineButton.setVisibility(View.GONE);
                    break;
                default: // pending
                    h.statusText.setText("⏳ Pending your decision");
                    h.statusText.setTextColor(0xFFFF9800);
                    h.acceptButton.setVisibility(View.VISIBLE);
                    h.declineButton.setVisibility(View.VISIBLE);
                    h.acceptButton.setOnClickListener(v  -> acceptRequest(item));
                    h.declineButton.setOnClickListener(v -> declineRequest(item));
                    break;
            }
        }

        @Override
        public int getItemCount() { return requests.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView nameText, phoneText, statusText;
            Button   acceptButton, declineButton;
            VH(View v) {
                super(v);
                nameText      = v.findViewById(R.id.requestName);
                phoneText     = v.findViewById(R.id.requestPhone);
                statusText    = v.findViewById(R.id.requestStatus);
                acceptButton  = v.findViewById(R.id.acceptButton);
                declineButton = v.findViewById(R.id.declineButton);
            }
        }
    }
}