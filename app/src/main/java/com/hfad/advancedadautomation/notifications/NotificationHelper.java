package com.hfad.advancedadautomation.notifications;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;

import com.hfad.advancedadautomation.R;


public class NotificationHelper {
    private static NotificationManager notificationManager;
    private static NotificationCompat.Builder notificationBuilder;
    private static final String CHANNEL_ID = "automation_channel";

    // Initialise la notification
    public static void initialize(Context context) {
        notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);

        // Crée la notification
        notificationBuilder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("AdAutomation actif")
                .setContentText("L'automatisation fonctionne.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)  // Assure-toi d'utiliser l'icône correcte
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true);  // Empêche l'utilisateur de la faire disparaître manuellement

        // Si nécessaire, crée le canal de notification pour Android 8+ (API 26)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannelHelper.createNotificationChannel(context);
        }
    }

    // Mets à jour le texte de la notification
    public static void updateNotification(Context context, String logMessage) {
        if (notificationBuilder != null) {
            notificationBuilder.setContentText(logMessage);
            notificationManager.notify(1, notificationBuilder.build());
        }
    }

    // Crée une nouvelle notification (utile pour des notifications distinctes si besoin)
    public static void createNewLogNotification(Context context, String logMessage) {
        Notification notification = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("AdAutomation Log")
                .setContentText(logMessage)
                .setSmallIcon(R.drawable.ic_launcher_foreground)  // Assure-toi d'utiliser l'icône correcte
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        notificationManager.notify((int) System.currentTimeMillis(), notification);  // Utilisation du timestamp pour des ID uniques
    }

    // Méthode pour libérer les ressources lorsque le service est terminé
    public static void clearNotifications() {
        if (notificationManager != null) {
            notificationManager.cancelAll();  // Annule toutes les notifications liées au service
        }
    }
}
