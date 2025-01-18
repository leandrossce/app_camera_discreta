package com.capongasoftware.cameradiscreta;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.media.MediaRecorder;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Surface;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Random;

public class VideoRecordingService extends Service {

    private static final String TAG = "VideoRecordingService";
    public static final String EXTRA_CAMERA_ID = "CAMERA_ID";

    // ID do canal para Foreground Service
    private static final String CHANNEL_ID = "camera_discreta_channel";
    private static final int NOTIFICATION_ID = 777;

    // Managers e objetos de câmera
    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;

    // Recorder e superfícies
    private MediaRecorder mediaRecorder;
    private SurfaceTexture dummySurfaceTexture;
    private Surface dummyPreviewSurface;

    private String cameraId = "0"; // 0 = traseira, 1 = frontal...
    private File outputFile;       // Arquivo de saída

    // Resolução fixa (mais segura que 1280x720,
    // mas ideal seria buscar via CameraCharacteristics)
    private static final int VIDEO_WIDTH = 640;
    private static final int VIDEO_HEIGHT = 480;

    @Override
    public void onCreate() {
        super.onCreate();
        cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildForegroundNotification());
        }

        Log.d(TAG, "Service created.");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && intent.hasExtra(EXTRA_CAMERA_ID)) {
            cameraId = intent.getStringExtra(EXTRA_CAMERA_ID);
        } else {
            cameraId = "0";
        }

        // Se API < 21, não há Camera2
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            Log.e(TAG, "API < 21 não suporta Camera2. Encerrando.");
            stopSelf();
            return START_NOT_STICKY;
        }

        try {
            initializeMediaRecorder();
            startCameraSession(cameraId);
        } catch (Exception e) {
            Log.e(TAG, "Error starting recording.", e);
            stopSelf();
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stopRecording();
        super.onDestroy();
        Log.d(TAG, "Service destroyed.");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Cria o canal de notificação (API >= 26).
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Camera Discreta Channel",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Canal para gravação discreta de vídeo");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    /**
     * Notificação para rodar em primeiro plano (FGS).
     */
    private Notification buildForegroundNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Gravando vídeo discretamente")
                .setContentText("Seu vídeo está sendo gravado em segundo plano.")
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * Prepara MediaRecorder e define onde salvar o vídeo
     * (pasta pública se < 29, senão pasta privada + copiar p/ MediaStore).
     */
    private void initializeMediaRecorder() throws IOException {
        String randomFileName = generateRandomFileName(10) + ".mp4";

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Em versões < 29, grava direto em /Movies/CameraDiscreta
            File publicMoviesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES);
            File customDir = new File(publicMoviesDir, "CameraDiscreta");
            if (!customDir.exists()) {
                customDir.mkdirs();
            }
            outputFile = new File(customDir, randomFileName);
        } else {
            // Em versões >= 29, grava em pasta privada e depois copia p/ MediaStore
            outputFile = new File(getExternalFilesDir(Environment.DIRECTORY_MOVIES), randomFileName);
        }

        mediaRecorder = new MediaRecorder();

        // Fontes de vídeo/áudio
        mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);

        // Formato e encoders
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);

        // Ajustes de bitrate e frame rate
        mediaRecorder.setVideoEncodingBitRate(5_000_000);
        mediaRecorder.setVideoFrameRate(30);

        // Usamos resolução 640x480, geralmente suportada
        mediaRecorder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT);

        // Ajustar orientação dependendo da câmera
        if ("1".equals(cameraId)) {
            // frontal normalmente "espelhada" => 270
            mediaRecorder.setOrientationHint(270);
        } else {
            // traseira => 90
            mediaRecorder.setOrientationHint(90);
        }

        // Define arquivo de saída
        mediaRecorder.setOutputFile(outputFile.getAbsolutePath());
        mediaRecorder.prepare();

        Log.d(TAG, "MediaRecorder preparado. File: " + outputFile.getAbsolutePath());
    }

    private String generateRandomFileName(int length) {
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(length);
        Random random = new Random();
        for (int i = 0; i < length; i++) {
            sb.append(characters.charAt(random.nextInt(characters.length())));
        }
        return sb.toString();
    }

    /**
     * Abre a câmera de forma assíncrona, usando Camera2.
     */
    private void startCameraSession(String cameraId) {
        try {
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    cameraDevice = camera;
                    createCaptureSession();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    Log.e(TAG, "Camera disconnected: " + cameraId);
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    camera.close();
                    Log.e(TAG, "Camera error (" + error + "): " + cameraId);
                }
            }, null);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Error accessing camera.", e);
        } catch (SecurityException e) {
            Log.e(TAG, "Security Exception. Missing camera permission?", e);
        }
    }

    /**
     * Cria a sessão de captura, adicionando surfaces do MediaRecorder e do "dummy preview".
     */
    private void createCaptureSession() {
        if (cameraDevice == null) {
            Log.e(TAG, "createCaptureSession: cameraDevice is null!");
            return;
        }
        try {
            Surface recorderSurface = mediaRecorder.getSurface();

            // Dummy surface com mesma resolução do recorder
            dummySurfaceTexture = new SurfaceTexture(0);
            dummySurfaceTexture.setDefaultBufferSize(VIDEO_WIDTH, VIDEO_HEIGHT);
            dummyPreviewSurface = new Surface(dummySurfaceTexture);

            final CaptureRequest.Builder requestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);

            // Adiciona a surface do recorder e a dummy
            requestBuilder.addTarget(recorderSurface);
            requestBuilder.addTarget(dummyPreviewSurface);

            // Ajustes automáticos
            requestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);

            cameraDevice.createCaptureSession(
                    Arrays.asList(recorderSurface, dummyPreviewSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            captureSession = session;
                            try {
                                // Inicia fluxo
                                session.setRepeatingRequest(requestBuilder.build(), null, null);
                                // Inicia gravação
                                mediaRecorder.start();
                                Log.d(TAG, "Recording started (cameraId: " + cameraId + ")");
                            } catch (CameraAccessException e) {
                                Log.e(TAG, "Error during capture session.", e);
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Log.e(TAG, "Configuration failed.");
                        }
                    },
                    null
            );
        } catch (CameraAccessException e) {
            Log.e(TAG, "CameraAccessException while creating capture session", e);
        }
    }

    /**
     * Para a gravação, libera recursos e registra o vídeo (MediaStore ou scan).
     */
    private void stopRecording() {
        try {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (mediaRecorder != null) {
                try {
                    mediaRecorder.stop();
                } catch (Exception e) {
                    Log.e(TAG, "Error stopping MediaRecorder.", e);
                }
                mediaRecorder.reset();
                mediaRecorder.release();
                mediaRecorder = null;
            }
            if (dummyPreviewSurface != null) {
                dummyPreviewSurface.release();
                dummyPreviewSurface = null;
            }
            if (dummySurfaceTexture != null) {
                dummySurfaceTexture.release();
                dummySurfaceTexture = null;
            }

            Log.d(TAG, "Recording stopped.");

            // Se API >= 29, copiar para MediaStore e apagar arquivo privado
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && outputFile != null && outputFile.exists()) {
                copyToMediaStore(outputFile);
                outputFile.delete();
            }
            // Se API < 29, está em /Movies/CameraDiscreta; precisamos "scanFile"
            else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && outputFile != null && outputFile.exists()) {
                scanFileLegacy(outputFile.getAbsolutePath());
            }

        } catch (Exception e) {
            Log.e(TAG, "Error on stopRecording()", e);
        }
    }

    /**
     * Força o MediaScanner a indexar o arquivo (API < 29).
     */
    private void scanFileLegacy(String path) {
        MediaScannerConnection.scanFile(
                getApplicationContext(),
                new String[]{path},
                null,
                (scanPath, uri) -> Log.d(TAG, "scanFileLegacy completed. Path=" + scanPath + " -> " + uri)
        );
    }

    /**
     * Copia o arquivo da pasta privada para o MediaStore (Movies/CameraDiscreta).
     */
    private void copyToMediaStore(File sourceFile) {
        Uri videoUri = createVideoUriForScopedStorage();
        if (videoUri == null) {
            Log.e(TAG, "Falha ao criar URI no MediaStore!");
            return;
        }
        try (
                FileInputStream in = new FileInputStream(sourceFile);
                OutputStream out = getContentResolver().openOutputStream(videoUri)
        ) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            out.flush();
            Log.d(TAG, "Vídeo copiado para MediaStore: " + videoUri);
        } catch (IOException e) {
            Log.e(TAG, "Erro ao copiar arquivo para MediaStore.", e);
        }
    }

    /**
     * Cria um Uri no MediaStore apontando para "Movies/CameraDiscreta".
     */
    private Uri createVideoUriForScopedStorage() {
        ContentValues values = new ContentValues();
        String randomFileName = generateRandomFileName(10) + ".mp4";
        long currentTime = System.currentTimeMillis();

        values.put(MediaStore.Video.Media.DISPLAY_NAME, randomFileName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/CameraDiscreta");
        values.put(MediaStore.Video.Media.DATE_ADDED, currentTime / 1000);
        values.put(MediaStore.Video.Media.DATE_TAKEN, currentTime);

        ContentResolver resolver = getContentResolver();
        return resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
    }
}
