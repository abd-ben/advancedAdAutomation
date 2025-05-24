package com.hfad.advancedadautomation.logs;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.hfad.advancedadautomation.notifications.NotificationHelper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public class LogMonitor {
    private static final String TAG = "AdAutomationService";
    private static boolean isRunning = false;

    public static void startListening(Context context) {
        if (isRunning) return;
        isRunning = true;

        Context appContext = context.getApplicationContext();
        Handler mainHandler = new Handler(Looper.getMainLooper());

        new Thread(() -> {
            try {
                Process process = Runtime.getRuntime().exec("logcat -s " + TAG);
                BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                String line;

                while ((line = reader.readLine()) != null && isRunning) {
                    // Log.d("LogMonitor", "New log line: " + line);

                    // ➤ Extraire seulement le message après ": " (s'il y a un)
                    String[] parts = line.split(": ", 2);
                    if (parts.length == 2) {
                        String message = parts[1]; // juste le texte du log
                        NotificationHelper.updateNotification(appContext, message);

                        mainHandler.post(() ->
                                Toast.makeText(appContext, message, Toast.LENGTH_SHORT).show()
                        );
                    }
                }
            } catch (IOException e) {
                Log.e("LogMonitor", "Erreur dans logcat reader : " + e.getMessage());
            }
        }).start();
    }

    public static void stopListening() {
        isRunning = false;
    }
}
