package com.resqmotion.app;

import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class EmergencyContactsActivity extends AppCompatActivity {

    private static final String PREFS_NAME = "FallDetectionPrefs";
    private static final String CONTACTS_KEY = "emergency_contacts_json";

    private RecyclerView contactsRecyclerView;
    private TextView emptyStateText;
    private Button addContactButton, backButton;

    private List<EmergencyContact> contacts = new ArrayList<>();
    private ContactsAdapter adapter;
    private SharedPreferences prefs;
    private Gson gson;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_contacts);

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        gson = new Gson();

        // Initialize views
        contactsRecyclerView = findViewById(R.id.contactsRecyclerView);
        emptyStateText = findViewById(R.id.emptyStateText);
        addContactButton = findViewById(R.id.addContactButton);
        backButton = findViewById(R.id.backButton);

        // Setup RecyclerView
        contactsRecyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ContactsAdapter(contacts, this::deleteContact);
        contactsRecyclerView.setAdapter(adapter);

        // Load contacts
        loadContacts();
        updateUI();

        // Button listeners
        addContactButton.setOnClickListener(v -> showAddContactDialog());
        backButton.setOnClickListener(v -> finish());
    }

    private void showAddContactDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Emergency Contact");

        // Create layout with two input fields
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        // Name input
        TextView nameLabel = new TextView(this);
        nameLabel.setText("Contact Name:");
        nameLabel.setTextSize(14);
        nameLabel.setPadding(0, 0, 0, 8);
        layout.addView(nameLabel);

        EditText nameInput = new EditText(this);
        nameInput.setHint("e.g., Mom, Dad, John");
        nameInput.setInputType(InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        layout.addView(nameInput);

        // Phone input
        TextView phoneLabel = new TextView(this);
        phoneLabel.setText("Phone Number:");
        phoneLabel.setTextSize(14);
        phoneLabel.setPadding(0, 24, 0, 8);
        layout.addView(phoneLabel);

        EditText phoneInput = new EditText(this);
        phoneInput.setHint("e.g., +1234567890");
        phoneInput.setInputType(InputType.TYPE_CLASS_PHONE);
        layout.addView(phoneInput);

        builder.setView(layout);

        builder.setPositiveButton("Add", (dialog, which) -> {
            String name = nameInput.getText().toString().trim();
            String phone = phoneInput.getText().toString().trim();

            if (name.isEmpty() || phone.isEmpty()) {
                Toast.makeText(this, "Please fill both fields", Toast.LENGTH_SHORT).show();
                return;
            }

            // Validate phone number (basic validation)
            if (phone.length() < 10) {
                Toast.makeText(this, "Please enter a valid phone number", Toast.LENGTH_SHORT).show();
                return;
            }

            // Check for duplicates
            for (EmergencyContact contact : contacts) {
                if (contact.getPhone().equals(phone)) {
                    Toast.makeText(this, "This number is already added", Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            // Add contact
            EmergencyContact newContact = new EmergencyContact(name, phone);
            contacts.add(newContact);
            saveContacts();
            updateUI();
            Toast.makeText(this, "Contact added: " + name, Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void deleteContact(int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        EmergencyContact contact = contacts.get(position);

        builder.setTitle("Delete Contact");
        builder.setMessage("Remove " + contact.getName() + " from emergency contacts?");

        builder.setPositiveButton("Delete", (dialog, which) -> {
            contacts.remove(position);
            saveContacts();
            updateUI();
            Toast.makeText(this, "Contact removed", Toast.LENGTH_SHORT).show();
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private void loadContacts() {
        String json = prefs.getString(CONTACTS_KEY, null);
        if (json != null) {
            Type type = new TypeToken<List<EmergencyContact>>(){}.getType();
            contacts.clear();
            contacts.addAll(gson.fromJson(json, type));
        }
    }

    private void saveContacts() {
        String json = gson.toJson(contacts);
        prefs.edit().putString(CONTACTS_KEY, json).apply();
        setResult(RESULT_OK);
    }

    private void updateUI() {
        if (contacts.isEmpty()) {
            emptyStateText.setVisibility(View.VISIBLE);
            contactsRecyclerView.setVisibility(View.GONE);
        } else {
            emptyStateText.setVisibility(View.GONE);
            contactsRecyclerView.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
        }
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }
}