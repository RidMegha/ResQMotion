package com.resqmotion.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class FamilyMemberAdapter
        extends RecyclerView.Adapter<FamilyMemberAdapter.ViewHolder> {

    public interface OnFamilyMemberActionListener {
        void onRemove(FamilyMember member, int position);
        void onCall(FamilyMember member);
    }

    private final List<FamilyMember>           members;
    private final OnFamilyMemberActionListener listener;
    private final Context                      context;

    public FamilyMemberAdapter(Context context,
                               List<FamilyMember> members,
                               OnFamilyMemberActionListener listener) {
        this.context  = context;
        this.members  = members;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_family_member, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder h, int position) {
        FamilyMember m = members.get(position);

        h.nameText.setText(m.getName());
        h.phoneText.setText(m.getPhone() != null ? m.getPhone() : "");

        // ── PENDING ───────────────────────────────────────────
        if ("pending".equals(m.getStatus())) {
            h.activityText.setText("⏳ Waiting for approval…");
            h.activityText.setTextColor(
                    ContextCompat.getColor(context, android.R.color.holo_orange_dark));
            h.batteryText.setText("");
            h.lastSeenText.setText("Request sent – they need to accept");
            h.callButton.setVisibility(View.GONE);
            h.itemView.setBackgroundColor(0xFFFFFDE7);
            return;
        }

        // ── DECLINED ──────────────────────────────────────────
        if ("declined".equals(m.getStatus())) {
            h.activityText.setText("✗ Request declined");
            h.activityText.setTextColor(
                    ContextCompat.getColor(context, android.R.color.holo_red_dark));
            h.batteryText.setText("");
            h.lastSeenText.setText("Remove and try again if needed");
            h.callButton.setVisibility(View.GONE);
            h.itemView.setBackgroundColor(0xFFFFEBEE);
            h.removeButton.setOnClickListener(v -> listener.onRemove(m, h.getAdapterPosition()));
            return;
        }

        // ── ACCEPTED – show live data ─────────────────────────
        h.callButton.setVisibility(View.VISIBLE);
        h.itemView.setBackgroundColor(0xFFFFFFFF);

        if (m.isFallAlert()) {
            h.activityText.setText("🚨 FALL DETECTED!");
            h.activityText.setTextColor(
                    ContextCompat.getColor(context, android.R.color.holo_red_dark));
            h.itemView.setBackgroundColor(0xFFFFEBEE);
            h.callButton.setText("📞 Call Now!");
            h.callButton.setBackgroundTintList(
                    ContextCompat.getColorStateList(context, android.R.color.holo_red_dark));
        } else if (!m.isMonitoring()) {
            h.activityText.setText("○ Not monitoring");
            h.activityText.setTextColor(
                    ContextCompat.getColor(context, android.R.color.darker_gray));
            h.callButton.setText("📞 Call");
            h.callButton.setBackgroundTintList(
                    ContextCompat.getColorStateList(context, android.R.color.holo_blue_dark));
        } else {
            h.activityText.setText("✓ " + m.getCurrentActivity()
                    + "  (" + String.format("%.0f%%", m.getCurrentConfidence() * 100) + ")");
            h.activityText.setTextColor(
                    ContextCompat.getColor(context, android.R.color.holo_green_dark));
            h.callButton.setText("📞 Call");
            h.callButton.setBackgroundTintList(
                    ContextCompat.getColorStateList(context, android.R.color.holo_blue_dark));
        }

        // Battery
        int bat = m.getCurrentBattery();
        h.batteryText.setText(bat >= 0 ? "🔋 " + bat + "%" : "🔋 --");

        // Last seen
        long diff = System.currentTimeMillis() - m.getLastUpdated();
        if (m.getLastUpdated() == 0) {
            h.lastSeenText.setText("Never synced");
        } else if (diff < 60_000) {
            h.lastSeenText.setText("Updated just now");
        } else if (diff < 3_600_000) {
            h.lastSeenText.setText("Updated " + (diff / 60_000) + " min ago");
        } else {
            h.lastSeenText.setText("Updated " + (diff / 3_600_000) + " hr ago");
        }

        h.callButton.setOnClickListener(v   -> listener.onCall(m));
        h.removeButton.setOnClickListener(v -> listener.onRemove(m, h.getAdapterPosition()));
    }

    @Override
    public int getItemCount() { return members.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView nameText, phoneText, activityText, batteryText, lastSeenText;
        Button   callButton, removeButton;

        ViewHolder(View itemView) {
            super(itemView);
            nameText     = itemView.findViewById(R.id.memberName);
            phoneText    = itemView.findViewById(R.id.memberPhone);
            activityText = itemView.findViewById(R.id.memberActivity);
            batteryText  = itemView.findViewById(R.id.memberBattery);
            lastSeenText = itemView.findViewById(R.id.memberLastSeen);
            callButton   = itemView.findViewById(R.id.memberCallButton);
            removeButton = itemView.findViewById(R.id.memberRemoveButton);
        }
    }
}