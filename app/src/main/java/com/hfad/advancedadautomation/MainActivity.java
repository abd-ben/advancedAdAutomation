package com.hfad.advancedadautomation;

import androidx.appcompat.app.AppCompatActivity;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;


import com.hfad.advancedadautomation.services.AdAutomationService;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button startServiceButton = findViewById(R.id.startServiceButton);
        Button stopServiceButton = findViewById(R.id.stopServiceButton);
        Button resetTimerButton = findViewById(R.id.btn_reset_timer);

        // Lancer le service
        startServiceButton.setOnClickListener(this::startAutomationService);

        // Arrêter le service
        stopServiceButton.setOnClickListener(this::stopAutomationService);

        // Réinitialiser le timer du free package
        resetTimerButton.setOnClickListener(v -> resetFreePackageTimer());

        // Rediriger l'utilisateur vers les paramètres d'accessibilité
        findViewById(R.id.btn_enable_service).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });
    }

    public void startAutomationService(View view) {
        Log.d("MainActivityLog", "Trying to start service");

        // Récupère le champ texte
        EditText editTextTimeRemaining = findViewById(R.id.editTextTimeRemaining);
        String timeText = editTextTimeRemaining.getText().toString().trim();

        if (!timeText.isEmpty()) {
            try {
                long remainingTimeInMinutes = Long.parseLong(timeText);
                long remainingTimeInMillis = remainingTimeInMinutes * 60 * 1000;

                // Restart après un petit délai avec le temps personnalisé
                new Handler().postDelayed(() -> {
                    Intent startIntent = new Intent();
                    startIntent.setAction(AdAutomationService.ACTION_START);
                    startIntent.putExtra("remainingTime", remainingTimeInMillis);
                    sendBroadcast(startIntent);
                    Toast.makeText(this, "Service démarré avec " + remainingTimeInMinutes + " minutes restantes.", Toast.LENGTH_SHORT).show();
                }, 300);

            } catch (NumberFormatException e) {
                Toast.makeText(this, "Entrez un nombre valide de minutes.", Toast.LENGTH_SHORT).show();
            }
        } else {
            Toast.makeText(this, "Veuillez saisir un temps (en minutes).", Toast.LENGTH_SHORT).show();
        }
    }

    public void stopAutomationService(View view) {
        Log.d("MainActivity", "Sending broadcast to stop service logic.");

        // Créer un Intent pour envoyer l'action de stopper le service
        Intent stopIntent = new Intent();
        stopIntent.setAction(AdAutomationService.ACTION_STOP);

        // Envoyer l'Intent à travers un Broadcast
        sendBroadcast(stopIntent);
        Toast.makeText(this, "Service stopped", Toast.LENGTH_SHORT).show();
    }

    private void resetFreePackageTimer() {
        // Obtenez l'heure actuelle
        long currentTimeMillis = System.currentTimeMillis();

        // Créez une instance de Date pour formater le temps
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault());
        String formattedDate = sdf.format(new java.util.Date(currentTimeMillis));

        // Affichez l'heure dans les logs
        Log.d("MainActivity", "Button clicked at: " + formattedDate); // Log affichant l'heure de clic

        // Envoie un broadcast pour réinitialiser le timer
        Intent resetIntent = new Intent(AdAutomationService.ACTION_RESET_FREE_PACKAGE);
        sendBroadcast(resetIntent);
        Toast.makeText(this, "Free package timer reset!", Toast.LENGTH_SHORT).show();
        Log.d("MainActivity", "Sent reset timer broadcast.");
    }

    // Vérifie si le service est déjà en cours d'exécution
    private boolean isServiceRunning() {
        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningServiceInfo> runningServices = activityManager.getRunningServices(Integer.MAX_VALUE);

        for (ActivityManager.RunningServiceInfo serviceInfo : runningServices) {
            if (serviceInfo.service.getClassName().equals("com.hfad.advancedadautomation.services.AdAutomationService")) {
                return true;  // Le service est déjà en cours d'exécution
            }
        }
        return false;  // Le service n'est pas en cours d'exécution
    }

    // Méthode appelée lorsque l'utilisateur clique sur le bouton dans le XML
    public void enableAccessibilityService(View view) {
        // Ouvre les paramètres d'accessibilité pour activer le service
        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(intent);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Arrêter le service lorsque l'application passe en arrière-plan
        stopAdAutomationServices();
    }


    @Override
    protected void onStop() {
        super.onStop();
        // Arrêter également le service lorsque l'application est complètement arrêtée
        stopAdAutomationServices();
    }


    private void stopAdAutomationServices() {
        Intent serviceIntent = new Intent(this, AdAutomationService.class);
        stopService(serviceIntent);  // Arrêter le service
    }

}