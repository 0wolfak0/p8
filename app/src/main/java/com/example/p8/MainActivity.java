package com.example.p8;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private ImageView imageView;
    private Button btnTakePhoto, btnSubmit, btnSendEmail;
    private TextView coordinates;
    private Uri imageUri;
    private double latitude = 0, longitude = 0;

    private FusedLocationProviderClient fusedLocationClient;
    private FirebaseAuth mAuth;
    private StorageReference storageRef;
    private DatabaseReference dbRef;

    private static final int REQUEST_IMAGE_CAPTURE = 1;
    private static final int REQUEST_PERMISSIONS = 100;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        btnTakePhoto = findViewById(R.id.btnTakePhoto);
        btnSubmit = findViewById(R.id.btnSubmit);
        btnSendEmail = findViewById(R.id.btnSendEmail);
        coordinates = findViewById(R.id.coordinates);

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        mAuth = FirebaseAuth.getInstance();

        if (mAuth.getCurrentUser() == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        storageRef = FirebaseStorage.getInstance().getReference();
        dbRef = FirebaseDatabase.getInstance().getReference("uploads")
                .child(mAuth.getCurrentUser().getUid());

        btnSendEmail.setEnabled(false);
        btnSubmit.setEnabled(false);

        btnTakePhoto.setOnClickListener(v -> checkPermissionsAndTakePhoto());
        btnSubmit.setOnClickListener(v -> submitData());
        btnSendEmail.setOnClickListener(v -> sendEmailWithPhoto());
    }

    private void checkPermissionsAndTakePhoto() {
        String[] permissions = new String[] {
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
        };

        boolean allPermissionsGranted = true;
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(this, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                allPermissionsGranted = false;
                break;
            }
        }

        if (!allPermissionsGranted) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        } else {
            takePhoto();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                takePhoto();
            } else {
                Toast.makeText(this, "Permissions required for camera and location",
                        Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void takePhoto() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
        }
    }

    private void getCurrentLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }

        fusedLocationClient.getLastLocation()
                .addOnSuccessListener(this, location -> {
                    if (location != null) {
                        latitude = location.getLatitude();
                        longitude = location.getLongitude();
                        coordinates.setText(String.format("Location: %.6f, %.6f", latitude, longitude));
                    } else {
                        coordinates.setText("Location: Unable to get location");
                    }
                });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == RESULT_OK) {
            Bundle extras = data.getExtras();
            Bitmap imageBitmap = (Bitmap) (extras != null ? extras.get("data") : null);
            if (imageBitmap == null) {
                Toast.makeText(this, "No image captured", Toast.LENGTH_SHORT).show();
                return;
            }

            imageView.setImageBitmap(imageBitmap);
            saveImageAndGetUri(imageBitmap);

            // Fetch location after capture for freshness
            getCurrentLocation();

            btnSubmit.setEnabled(true);
            btnSendEmail.setEnabled(true);
        }
    }

    private void saveImageAndGetUri(Bitmap bitmap) {
        try {
            File tempFile = new File(getCacheDir(), "temp_photo_" + System.currentTimeMillis() + ".jpg");
            FileOutputStream out = new FileOutputStream(tempFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
            out.close();
            imageUri = androidx.core.content.FileProvider.getUriForFile(
                    this,
                    getPackageName() + ".fileprovider",
                    tempFile);
        } catch (IOException e) {
            e.printStackTrace();
            Toast.makeText(this, "Error saving image", Toast.LENGTH_SHORT).show();
        }
    }

    private void submitData() {
        if (imageUri == null) {
            Toast.makeText(this, "No photo captured!", Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, "Uploading...", Toast.LENGTH_SHORT).show();

        StorageReference photoRef = storageRef.child("photos/" + System.currentTimeMillis() + ".jpg");

        photoRef.putFile(imageUri)
                .addOnSuccessListener(taskSnapshot ->
                        photoRef.getDownloadUrl().addOnSuccessListener(downloadUri ->
                                saveToDatabase(downloadUri.toString())))
                .addOnFailureListener(e ->
                        Toast.makeText(MainActivity.this, "Upload failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void saveToDatabase(String photoUrl) {
        String uploadId = dbRef.push().getKey();

        Map<String, Object> uploadData = new HashMap<>();
        uploadData.put("photoUrl", photoUrl);
        uploadData.put("latitude", latitude);
        uploadData.put("longitude", longitude);
        uploadData.put("timestamp", System.currentTimeMillis());
        uploadData.put("userId", mAuth.getCurrentUser().getUid());

        dbRef.child(uploadId).setValue(uploadData)
                .addOnSuccessListener(aVoid -> {
                    Toast.makeText(MainActivity.this, "Data submitted successfully!", Toast.LENGTH_SHORT).show();
                    resetUI();
                })
                .addOnFailureListener(e ->
                        Toast.makeText(MainActivity.this, "Database error: " + e.getMessage(), Toast.LENGTH_SHORT).show());
    }

    private void sendEmailWithPhoto() {
        if (imageUri == null) {
            Toast.makeText(this, "No photo to send", Toast.LENGTH_SHORT).show();
            return;
        }

        String subject = "Photo and Location from My App";
        String body = String.format("Here is the photo and location:\nLatitude: %.6f\nLongitude: %.6f",
                latitude, longitude);

        Intent emailIntent = new Intent(Intent.ACTION_SEND);
        emailIntent.setType("image/jpeg");
        emailIntent.putExtra(Intent.EXTRA_SUBJECT, subject);
        emailIntent.putExtra(Intent.EXTRA_TEXT, body);
        emailIntent.putExtra(Intent.EXTRA_STREAM, imageUri);
        emailIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(emailIntent, "Send email using:"));
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "No email clients installed.", Toast.LENGTH_SHORT).show();
        }
    }

    private void resetUI() {
        imageView.setImageResource(android.R.color.darker_gray);
        coordinates.setText("Location: Not captured");
        btnSubmit.setEnabled(false);
        btnSendEmail.setEnabled(false);
        imageUri = null;
        latitude = 0;
        longitude = 0;
    }
}
