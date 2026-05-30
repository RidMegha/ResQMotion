package com.resqmotion.app;

/**
 * Data model for a family member the current user monitors.
 * Stored under: users/{watcher-uid}/monitoring/{target-uid}/
 * NOTE: All fields must have a no-arg constructor + getters/setters for Firebase.
 */
public class FamilyMember {

    private String  uid;
    private String  name;
    private String  phone;
    private String  email;
    private String  status;           // "pending" | "accepted" | "declined"
    private long    connectedSince;

    // Live fields – NOT stored in Firebase under monitoring/
    // Populated at runtime from users/{uid}/current_status/
    private transient String  currentActivity   = "Unknown";
    private transient float   currentConfidence = 0f;
    private transient int     currentBattery    = -1;
    private transient boolean isMonitoring      = false;
    private transient boolean fallAlert         = false;
    private transient long    lastUpdated       = 0L;

    // Required by Firebase
    public FamilyMember() {}

    public FamilyMember(String uid, String name, String phone,
                        String email, String status) {
        this.uid            = uid;
        this.name           = name;
        this.phone          = phone;
        this.email          = email;
        this.status         = status;
        this.connectedSince = System.currentTimeMillis();
    }

    public String  getUid()               { return uid; }
    public String  getName()              { return name; }
    public String  getPhone()             { return phone; }
    public String  getEmail()             { return email; }
    public String  getStatus()            { return status; }
    public long    getConnectedSince()    { return connectedSince; }
    public String  getCurrentActivity()   { return currentActivity; }
    public float   getCurrentConfidence() { return currentConfidence; }
    public int     getCurrentBattery()    { return currentBattery; }
    public boolean isMonitoring()         { return isMonitoring; }
    public boolean isFallAlert()          { return fallAlert; }
    public long    getLastUpdated()       { return lastUpdated; }

    public void setUid(String uid)                     { this.uid = uid; }
    public void setName(String name)                   { this.name = name; }
    public void setPhone(String phone)                 { this.phone = phone; }
    public void setEmail(String email)                 { this.email = email; }
    public void setStatus(String status)               { this.status = status; }
    public void setConnectedSince(long connectedSince) { this.connectedSince = connectedSince; }
    public void setCurrentActivity(String a)           { this.currentActivity = a; }
    public void setCurrentConfidence(float c)          { this.currentConfidence = c; }
    public void setCurrentBattery(int b)               { this.currentBattery = b; }
    public void setMonitoring(boolean m)               { this.isMonitoring = m; }
    public void setFallAlert(boolean f)                { this.fallAlert = f; }
    public void setLastUpdated(long t)                 { this.lastUpdated = t; }
}