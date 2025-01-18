package com.capongasoftware.cameradiscreta;

import static android.widget.Toast.LENGTH_LONG;
import static android.widget.Toast.LENGTH_SHORT;
import static android.widget.Toast.makeText;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {

    private static final int REQUEST_PERMISSIONS = 100;

    private Button rearButton;
    private Button frontButton;
    private Button stopButton;
    private Button donateButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        rearButton = findViewById(R.id.button_rear);
        frontButton = findViewById(R.id.button_front);
        stopButton = findViewById(R.id.button_stop);
        donateButton = findViewById(R.id.button_donate);

        stopButton.setEnabled(false);

        // Solicita permissões necessárias, se API >= 23
        requestNeededPermissions();

        // Botão "Câmera Traseira"
        rearButton.setOnClickListener(v -> {
            pararTudo();
            stopButton.setEnabled(true);
            highlightButton(rearButton);

            Intent serviceIntent = new Intent(this, VideoRecordingService.class);
            serviceIntent.putExtra(VideoRecordingService.EXTRA_CAMERA_ID, "0"); // 0 -> traseira
            startVideoService(serviceIntent);
        });

        // Botão "Câmera Frontal"
        frontButton.setOnClickListener(v -> {
            pararTudo();
            stopButton.setEnabled(true);
            highlightButton(frontButton);

            Intent serviceIntent = new Intent(this, VideoRecordingService.class);
            serviceIntent.putExtra(VideoRecordingService.EXTRA_CAMERA_ID, "1"); // 1 -> frontal
            startVideoService(serviceIntent);
        });

        // Botão "Parar"
        stopButton.setOnClickListener(v -> {
            pararTudo();
            makeText(this, "Gravação finalizada! Verifique o vídeo na galeria em 'Movies/CameraDiscreta'.",
                    LENGTH_LONG).show();
            stopButton.setEnabled(false);
        });

        // Botão "Doar"
        donateButton.setOnClickListener(v ->
                makeText(this, "CHAVE PIX: leandrosscerj@gmail.com", LENGTH_LONG).show());
    }

    /**
     * Se API >= 26, usar startForegroundService; caso contrário, startService normal.
     */
    private void startVideoService(Intent serviceIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
    }

    private void pararTudo() {
        resetButtons();
        Intent serviceIntent = new Intent(this, VideoRecordingService.class);
        stopService(serviceIntent);
    }

    private void highlightButton(Button button) {
        resetButtons();
        button.setEnabled(false);
    }

    private void resetButtons() {
        rearButton.setEnabled(true);
        frontButton.setEnabled(true);
        donateButton.setEnabled(true);
    }

    /**
     * Solicita CAMERA + RECORD_AUDIO e, se API < 29, WRITE_EXTERNAL_STORAGE.
     * Em API < 23, não faz runtime permissions, pois não existe.
     */
    private void requestNeededPermissions() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Em APIs < 23, as permissões são concedidas na instalação
            return;
        }

        boolean needWriteStorage = (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q);
        if (needWriteStorage) {
            String[] perms = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            };
            checkAndRequest(perms);
        } else {
            String[] perms = {
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
            checkAndRequest(perms);
        }
    }

    private void checkAndRequest(String[] permissions) {
        boolean needRequest = false;
        for (String perm : permissions) {
            if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                needRequest = true;
                break;
            }
        }
        if (needRequest) {
            ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_PERMISSIONS) {
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    makeText(this, "Permissões necessárias não foram concedidas!", LENGTH_SHORT).show();
                    finish();
                    return;
                }
            }
            makeText(this, "Permissões concedidas!", LENGTH_SHORT).show();
        }
    }
}
