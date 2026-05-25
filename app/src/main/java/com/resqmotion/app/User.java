package com.resqmotion.app;

public class User {
    private String userId;
    private String name;
    private String email;
    private String phone;
    private int age;
    private String gender;
    private String profilePictureUrl;
    private long createdAt;
    private boolean isMonitoring;

    // Required empty constructor for Firebase
    public User() {
    }

    public User(String userId, String name, String email, String phone, int age, String gender) {
        this.userId = userId;
        this.name = name;
        this.email = email;
        this.phone = phone;
        this.age = age;
        this.gender = gender;
        this.createdAt = System.currentTimeMillis();
        this.isMonitoring = false;
    }

    // Getters and Setters
    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public int getAge() {
        return age;
    }

    public void setAge(int age) {
        this.age = age;
    }

    public String getGender() {
        return gender;
    }

    public void setGender(String gender) {
        this.gender = gender;
    }

    public String getProfilePictureUrl() {
        return profilePictureUrl;
    }

    public void setProfilePictureUrl(String profilePictureUrl) {
        this.profilePictureUrl = profilePictureUrl;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isMonitoring() {
        return isMonitoring;
    }

    public void setMonitoring(boolean monitoring) {
        isMonitoring = monitoring;
    }
}