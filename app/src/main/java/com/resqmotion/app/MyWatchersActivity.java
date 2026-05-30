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

/**
 * MyWatchersActivity  —  "Who is monitoring me?"
 *
 * Shows Dad (or any user) the full list of people currently
 * watching their health data, with the ability to revoke
 * access from any individual watcher.
 *
 * Firebase nodes read/written:
 *   users/{myUid}/watchers/               ← list of who can see me
 *   users/{watcherUid}/monitoring/{myUid} ← revoked on removal
 *   users/{myUid}/incoming_requests/{watcherUid} ← cleaned up too
 */
public class MyWatchersActivity extends AppCompatActivity {

    private static final String TAG          = "MyWatchers";
    private static final String DATABASE_URL =
            "https://resqmotion-default-rtdb.asia-southeast1.firebasedatabase.app/";

    private FirebaseAuth      mAuth;
    private DatabaseReference mDatabase;
    private String            myUid;

    private RecyclerView    watchersRecycler;
    private TextView        emptyText, watcherCountText;
    private WatcherAdapter  adapter;

    private ValueEventListener watchersListener;
    private DatabaseReference  watchersRef;

    private final List<WatcherItem> watchers = new ArrayList<>();

    // ── Simple data class for one watcher row ─────────────────
    static class WatcherItem {
        String watcherUid, name, phone, acceptedSince;
    }

    // ═════════════════════════════════════════════════════════
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my_watchers);

        mAuth = FirebaseAuth.getInstance();
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser == null) { finish(); return; }

        myUid     = currentUser.getUid();
        mDatabase = FirebaseDatabase.getInstance(DATABASE_URL).getReference();

        watchersRecycler = findViewById(R.id.watchersRecycler);
        emptyText        = findViewById(R.id.emptyWatchersText);
        watcherCountText = findViewById(R.id.watcherCountText);

        findViewById(R.id.backButton).setOnClickListener(v -> finish());

        adapter = new WatcherAdapter();
        watchersRecycler.setLayoutManager(new LinearLayoutManager(this));
        watchersRecycler.setAdapter(adapter);

        listenToWatchers();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (watchersRef != null && watchersListener != null)
            watchersRef.removeEventListener(watchersListener);
    }

    // ═════════════════════════════════════════════════════════
    //  LISTEN TO MY WATCHERS LIST
    // ═════════════════════════════════════════════════════════
    private void listenToWatchers() {
        watchersRef      = mDatabase.child("users").child(myUid).child("watchers");
        watchersListener = new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot snapshot) {
                watchers.clear();

                for (DataSnapshot snap : snapshot.getChildren()) {
                    try {
                        WatcherItem item    = new WatcherItem();
                        item.watcherUid     = snap.getKey();
                        item.name           = snap.child("name").getValue(String.class);
                        item.phone          = snap.child("phone").getValue(String.class);
                        Long ts             = snap.child("acceptedSince").getValue(Long.class);

                        if (item.name  == null) item.name  = "Unknown";
                        if (item.phone == null) item.phone = "";

                        // Format accepted date
                        if (ts != null && ts > 0) {
                            java.text.SimpleDateFormat sdf =
                                    new java.text.SimpleDateFormat(
                                            "dd MMM yyyy", java.util.Locale.getDefault());
                            item.acceptedSince = "Monitoring since " + sdf.format(new java.util.Date(ts));
                        } else {
                            item.acceptedSince = "Monitoring since unknown";
                        }

                        watchers.add(item);
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing watcher: " + e.getMessage());
                    }
                }

                adapter.notifyDataSetChanged();

                // Update count text
                int count = watchers.size();
                if (count == 0) {
                    watcherCountText.setText("Nobody is monitoring you yet.");
                    emptyText.setVisibility(View.VISIBLE);
                } else {
                    watcherCountText.setText(count + " person" + (count > 1 ? "s are" : " is")
                            + " currently monitoring your health data.");
                    emptyText.setVisibility(View.GONE);
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError error) {
                Log.e(TAG, "Watchers listener error: " + error.getMessage());
            }
        };
        watchersRef.addValueEventListener(watchersListener);
    }

    // ═════════════════════════════════════════════════════════
    //  REVOKE ACCESS
    // ═════════════════════════════════════════════════════════
    private void revokeAccess(WatcherItem item) {
        new AlertDialog.Builder(this)
                .setTitle("Remove " + item.name + "?")
                .setMessage(item.name + " will no longer be able to monitor your "
                        + "health data or receive your fall alerts.\n\n"
                        + "They will need to send a new request and you will need "
                        + "to accept again if you change your mind.")
                .setPositiveButton("Remove Access", (dialog, which) -> {
                    String watcherUid = item.watcherUid;

                    // 1. Remove from MY watchers list
                    mDatabase.child("users").child(myUid)
                            .child("watchers").child(watcherUid)
                            .removeValue();

                    // 2. Remove from MY incoming_requests (full cleanup)
                    mDatabase.child("users").child(myUid)
                            .child("incoming_requests").child(watcherUid)
                            .removeValue();

                    // 3. Remove ME from THEIR monitoring list
                    //    so their dashboard stops showing my data
                    mDatabase.child("users").child(watcherUid)
                            .child("monitoring").child(myUid)
                            .removeValue()
                            .addOnSuccessListener(unused ->
                                    Toast.makeText(this,
                                            "✅ " + item.name
                                                    + " can no longer monitor you",
                                            Toast.LENGTH_LONG).show())
                            .addOnFailureListener(e ->
                                    Toast.makeText(this,
                                            "Error: " + e.getMessage(),
                                            Toast.LENGTH_SHORT).show());
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ═════════════════════════════════════════════════════════
    //  INLINE ADAPTER
    // ═════════════════════════════════════════════════════════
    private class WatcherAdapter extends RecyclerView.Adapter<WatcherAdapter.VH> {

        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_watcher, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            WatcherItem item = watchers.get(position);
            h.nameText.setText(item.name);
            h.phoneText.setText(item.phone);
            h.sinceText.setText(item.acceptedSince);
            h.revokeButton.setOnClickListener(v -> revokeAccess(item));
        }

        @Override
        public int getItemCount() { return watchers.size(); }

        class VH extends RecyclerView.ViewHolder {
            TextView nameText, phoneText, sinceText;
            Button   revokeButton;
            VH(View v) {
                super(v);
                nameText     = v.findViewById(R.id.watcherName);
                phoneText    = v.findViewById(R.id.watcherPhone);
                sinceText    = v.findViewById(R.id.watcherSince);
                revokeButton = v.findViewById(R.id.revokeButton);
            }
        }
    }
}