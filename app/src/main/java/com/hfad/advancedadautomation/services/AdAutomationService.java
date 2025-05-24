package com.hfad.advancedadautomation.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;

import com.hfad.advancedadautomation.OpenAdResult;
import com.hfad.advancedadautomation.R;
import com.hfad.advancedadautomation.logs.LogMonitor;
import com.hfad.advancedadautomation.notifications.NotificationHelper;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public class AdAutomationService extends AccessibilityService {

    private final Random random = new Random();

    private static final String TAG = "AdAutomationService";
    private boolean isRunning = true;
    public static final String ACTION_STOP = "com.hfad.advancedadautomation.STOP_SERVICE";
    public static final String ACTION_START = "com.hfad.advancedadautomation.START_SERVICE";
    private BroadcastReceiver serviceReceiver;
    private String currentPackage = "";
    private boolean isRecovering = false;
    private static final String PREFS_NAME = "AutomationPrefs";
    private static final String LAST_FREE_PACKAGE_TIME = "lastFreePackageTime";
    private static final long FOUR_HOURS = (4 * 60 * 60 * 1000) + (30 * 1000);
    public static final String ACTION_RESET_FREE_PACKAGE = "com.hfad.advancedadautomation.RESET_FREE_PACKAGE";
    public static final String EXTRA_REMAINING_TIME = "remainingTime";
    private static final String USE_CUSTOM_DELAY = "useCustomDelay";
    private long customFreePackageDelay = FOUR_HOURS;
    private static final long WATCHDOG_INTERVAL_MILLIS = 10 * 60 * 1000;
    private long lastActionTimestamp = System.currentTimeMillis();
    private String currentClass = "";
    SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
    private PowerManager.WakeLock wakeLock;
    private CountDownLatch adCloseLatch;
    private boolean waitingForReturnAfterRedirect = false;
    private boolean latchSignaled = false;
    private volatile boolean redirectDetected = false;
    private int adNotDetectedCount = 0;

    private List<Handler> handlersList = new ArrayList<>();
    private Handler adWatcherHandler;
    private Runnable adWatcherRunnable;
    private ScheduledFuture<?> automationFuture;
    private volatile boolean stopRequested = false;
    private Thread adInteractionThread;
    private CountDownLatch recoveryLatch = new CountDownLatch(0);

    private TextToSpeech tts;
    private AudioManager audioManager;
    private int previousVolume = -1;
    private boolean isTtsInitialized = false;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Service started, ready to automate ads.");

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);

        tts = new TextToSpeech(this, status -> {
            if (status == TextToSpeech.SUCCESS) {
                tts.setLanguage(Locale.US);
                isTtsInitialized = true;
                Log.d(TAG, "TTS initialized successfully.");
            } else {
                Log.e(TAG, "Failed to initialize TTS.");
            }
        });

        muteMediaVolume();

        serviceReceiver = new BroadcastReceiver() {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null) {
                    if (ACTION_STOP.equals(intent.getAction())) {
                        Log.d(TAG, "Received broadcast to stop automation.");
                        speak("Stopping service now", "STOP_NOW");
                        stopServiceLogic();
                    } else if (ACTION_START.equals(intent.getAction())) {
                        handleStartAction(intent);
                    }
                    if (ACTION_RESET_FREE_PACKAGE.equals(intent.getAction())) {
                        resetFreePackageTimer(true);
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_STOP);
        filter.addAction(ACTION_START);
        filter.addAction(ACTION_RESET_FREE_PACKAGE);
        registerReceiver(serviceReceiver, filter);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void handleStartAction(Intent intent) {
        customFreePackageDelay = intent.getLongExtra(EXTRA_REMAINING_TIME, FOUR_HOURS);
        Log.d(TAG, "Free Package Delay personnalisé: " + (customFreePackageDelay / 60000) + " minutes");

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putLong(LAST_FREE_PACKAGE_TIME, System.currentTimeMillis())
                .putBoolean(USE_CUSTOM_DELAY, true)
                .apply();

        startServiceLogic();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        lastActionTimestamp = System.currentTimeMillis();

        String pkg = event.getPackageName() != null ? event.getPackageName().toString() : "";
        String className = event.getClassName() != null ? event.getClassName().toString() : "";

        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {

            if (isDisruptiveApp(pkg, className)) {
                redirectDetected = true;

                if (adInteractionThread != null && adInteractionThread.isAlive()) {
                    adInteractionThread.interrupt();
                }

                handleRecovery(pkg, className);
            }
            if (event.getPackageName() != null) {
                currentPackage = pkg;
            }
            if (event.getClassName() != null) {
                currentClass = className;
            }
            if (waitingForReturnAfterRedirect &&
                    pkg.equals("com.firsttouchgames.smp") &&
                    className.equals("com.firsttouchgames.smp.MainActivity")) {

                Log.i(TAG, "Return to the game detected after redirection.");

                if (!latchSignaled) {
                    adCloseLatch.countDown();
                    latchSignaled = true;
                    Log.i(TAG, "Latch reported after returning to the game.");
                }

                waitingForReturnAfterRedirect = false;
                redirectDetected = false;
            }
        }
    }

    private boolean isDisruptiveApp(String pkg, String className) {
        return isChooserPopup(pkg, className) ||                    // "Ouvrir avec"
                pkg.contains("com.android.vending") ||              // Google Play
                pkg.contains("com.sec.android.app.samsungapps") ||  // Samsung Store
                pkg.contains("com.sec.android.app.sbrowser") ||     // Samsung Internet (réel)
                pkg.contains("samsung.internet") ||                 // (parfois utilisé dans logs)
                pkg.contains("chrome");                             // Chrome
    }

    private boolean isInAdActivity() {
        // Log.d(TAG, "Package: " + currentPackage + ", Class: " + currentClass);
        return currentClass != null && currentPackage != null && (
                currentClass.equals("com.google.android.gms.ads.AdActivity") ||
                currentClass.toLowerCase().contains("adactivity") ||
                currentClass.toLowerCase().contains("interstitial") ||
                currentClass.contains("com.vungle.publisher") ||
                currentClass.contains("com.unity3d.ads") ||
                currentClass.contains("com.applovin.adview") ||
                currentClass.contains("adcolony") ||
                currentClass.contains("chartboost") ||
                currentClass.contains("ironsource") ||
                currentClass.contains("facebook.ads") ||
                currentClass.contains("startapp") ||
                currentClass.contains("inmobi") ||
                currentClass.contains("mobvista") ||
                currentClass.contains("tapjoy") ||
                currentClass.equals("com.unity3d.services.ads.adunit.AdUnitActivity") ||
                currentClass.toLowerCase().contains("ads") ||
                currentClass.toLowerCase().contains("unity")
        );
    }

    private boolean isChooserPopup(String pkg, String className) {
        boolean isSystemPackage = pkg != null && pkg.equals("android");

        boolean isKnownChooserClass =
                className != null && (
                        className.contains("ResolverActivity") ||
                         className.contains("ChooserActivity") ||
                         className.contains("DefaultAppPicker")
                );

        return isSystemPackage && isKnownChooserClass;
    }

    private void handleRecovery(String pkg, String className) {
        waitingForReturnAfterRedirect = true;
        if (!isRecovering) {
            isRecovering = true;
            adNotDetectedCount = 0;
            Log.d(TAG, "Recovery in progress...");

            new Handler(Looper.getMainLooper()).post(() -> {
                if (isChooserPopup(pkg, className)) {
                    Log.d(TAG, "Chooser popup detected, pressing back.");
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
                switchBackToGame();
            });
            recoveryLatch = new CountDownLatch(1);

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                switchBackToGame();
                isRecovering = false;
                recoveryLatch.countDown();
            }, 2000);
        }
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Service interrupted.");
    }

    @Override
    protected void onServiceConnected() {
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED | AccessibilityEvent.TYPE_VIEW_CLICKED;  // Types d'événements à surveiller
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;

        setServiceInfo(info);
        createNotificationChannelIfNeeded();
        startForegroundServiceWithNotification();
        startWatchdog();
        initializeServiceComponents();
        Log.d(TAG, "Accessibility service connected.");
    }

    private void initializeServiceComponents() {
        NotificationHelper.initialize(this);
        NotificationHelper.updateNotification(this, "Service démarré.");
        LogMonitor.startListening(this);
    }

    private void createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "automation_channel",
                    "Service d'automatisation",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    private void startForegroundServiceWithNotification() {
        Notification notification = new NotificationCompat.Builder(this, "automation_channel")
                .setContentTitle("advancedadautomation actif")
                .setContentText("L'automatisation fonctionne en arrière-plan.")
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        startForeground(1, notification);
    }

    // Whitelist l'app pour ne jamais être gelée même en Doze : adb shell dumpsys deviceidle whitelist +com.hfad.advancedadautomation
    private void acquireWakeLock(long timeOut) {
        if (wakeLock == null || !wakeLock.isHeld()) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "AdAutomationService:WakeLock");
            wakeLock.acquire(timeOut);
            Log.d(TAG, "WakeLock acquired.");
        }
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
            Log.d(TAG, "WakeLock released.");
        }
    }

    private void startWatchdog() {
        Log.d(TAG, "Watchdog started — interval: " + (WATCHDOG_INTERVAL_MILLIS / 60000) + " min");

        Handler watchdogHandler = new Handler(Looper.getMainLooper());
        handlersList.add(watchdogHandler);

        Runnable watchdogRunnable = new Runnable() {
            @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
            @Override
            public void run() {
                long now = System.currentTimeMillis();
                long inactivityDuration = now - lastActionTimestamp;
                long inactivityMinutes = inactivityDuration / (60 * 1000);
                long inactivitySeconds = (inactivityDuration / 1000) % 60;

                String nowStr = sdf.format(new Date(now));
                String lastActionStr = sdf.format(new Date(lastActionTimestamp));

                Log.d(TAG, "Watchdog check at " + nowStr +
                        " | Last action at " + lastActionStr +
                        " | Inactivity: " + inactivityMinutes + " min " + inactivitySeconds + " sec.");

                if (inactivityDuration > WATCHDOG_INTERVAL_MILLIS) {
                    speak("Service inactive for too long. Attempting recovery.", "INACTIVE_RECOVERY");

                    Log.w(TAG, "Inactivity > " + (WATCHDOG_INTERVAL_MILLIS / 60000) +
                            " min — attempting unlock + recovery...");

                    wakeAndSwipeToUnlock();
                    handleRecovery(currentPackage, currentClass);

                    stopAutomation();
                    releaseWakeLock();

                    long restartDelay = getRandomDelay(5000, 10000);

                    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
                    executorService.schedule(() -> continueAfterPause(executorService), restartDelay, TimeUnit.MILLISECONDS);
                }

                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MILLIS);
            }
        };

        watchdogHandler.post(watchdogRunnable);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public void startServiceLogic() {
        if (isRunning) {
            Log.d(TAG, "AdAutomationService is already running — forcing restart.");
            stopServiceLogic();

            new Handler(Looper.getMainLooper()).postDelayed(this::startServiceLogic, 2000);
            return;
        }

        isRunning = true;

        ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();

        Runnable automationTask = () -> {
            if (isRunning) {
                try {
                    Log.d(TAG, "Launching Score! Match.");
                    Intent intent = new Intent();
                    intent.setComponent(new ComponentName("com.firsttouchgames.smp", "com.firsttouchgames.smp.MainActivity"));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

                    long startTime = System.currentTimeMillis();
                    long workDuration = getRandomDelay(28 * 60 * 1000, 32 * 60 * 1000);
                    acquireWakeLock(workDuration);

                    long endTime = startTime + workDuration;

                    Log.d(TAG, "Starting automation - " + (workDuration / 1000 / 60) + " minutes.");
                    startActivity(intent);
                    Thread.sleep(getRandomDelay(6000, 8000));
                    speak("Starting automation for " + (workDuration / 60000) + " minutes", "START");
                    while (isRunning && System.currentTimeMillis() < endTime) {
                        automateAds();  // Automatiser les publicités
                    }

                    long breakDuration = getRandomDelay(4 * 60 * 1000, 6 * 60 * 1000);
                    Log.d(TAG, "Pausing - " + (breakDuration / 1000 / 60) + " minutes.");

                    speak("Stopping automation. Pausing for " + (breakDuration / 60000) + " minutes", "STOP");
                    stopAutomation();
                    releaseWakeLock();

                    // Avant la pause, on vérifie si le free package peut être ouvert
                    handleFreePackageBeforePause(breakDuration);

                    // Pause aléatoire entre 5 et 10 minutes avant de redémarrer le cycle
                    executorService.schedule(() -> continueAfterPause(executorService), breakDuration, TimeUnit.MILLISECONDS);

                } catch (InterruptedException e) {
                    e.printStackTrace();
                    Log.e(TAG, "Interrupted while waiting for ad to close");
                    Thread.currentThread().interrupt();
                }
            }
        };

        automationFuture = executorService.schedule(automationTask, 0, TimeUnit.SECONDS);
    }

    private void handleFreePackageBeforePause(long breakDuration) {
        long remaining = getRemainingFreePackageTime();
        Log.d(TAG, "Free package remaining time: " + (remaining / 60000) + " minutes");

        if (remaining <= 0) {
            Log.d(TAG, "Free package ready. Will open now before pause.");
            try {
                openFreePackage();
                updateFreePackageTimestamp();
            } catch (InterruptedException e) {
                Log.e(TAG, "Error while opening free package before pause", e);
            }
        } else if (remaining <= breakDuration) {
            Log.d(TAG, "Free package not ready. Will check again in " + (remaining / 60000) + " minutes.");

            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if (canOpenFreePackage()) {
                    try {
                        Log.d(TAG, "Free package became available during pause. Opening...");
                        unlockScreen();
                        openFreePackage();
                        updateFreePackageTimestamp();
                    } catch (InterruptedException e) {
                        Log.e(TAG, "Error while opening free package in delayed check", e);
                    }
                }
            }, remaining);
        } else {
            Log.d(TAG, "Free package not ready and will not be ready within the " + (remaining / 60000) + "-minute pause.");
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void continueAfterPause(ScheduledExecutorService executorService) {
        Log.d(TAG, "Pause completed, closing the game!");
        speak("Restarting automation", "RESTART");
        forceCloseGameAndReturn();
        Log.d(TAG, "Restarting!");
        startAutomationCycle(executorService);
    }

    private void startAutomationCycle(ScheduledExecutorService executorService) {
        Log.d(TAG, "Restarting automation cycle after 5≈10 minutes pause.");
        executorService.schedule(this::startServiceLogic, 0, TimeUnit.SECONDS);
    }

    private void stopAutomation() {
        Log.d(TAG, "Stopping automation.");
        isRunning = false;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void automateAds() throws InterruptedException {
        adCloseLatch = new CountDownLatch(1);

        openAdWithRetry();
        if (isInAdActivity()) {
            Thread.sleep(getRandomDelay(5000, 6000));
            Log.d(TAG, "Ad successfully closed, waiting to return to the game...");
            final OpenAdResult[] openAdResultHolder = new OpenAdResult[1];  // Pour stocker le résultat

            adInteractionThread = new Thread(() -> {
                openAdResultHolder[0] = simulateAdInteractionAndClose();
            });
            adInteractionThread.start();

            try {
                adInteractionThread.join();
            } catch (InterruptedException e) {
                Log.w(TAG, "Thread principal interrompu pendant join.");
                Thread.currentThread().interrupt();
            }

            Thread.sleep(getRandomDelay(1000, 1200));

            OpenAdResult openAdResult = openAdResultHolder[0];
            if (openAdResult == OpenAdResult.SUCCESS) {
                launchApp("com.firsttouchgames.smp", "com.firsttouchgames.smp.MainActivity", "Returning to the game...");
            }
        }
        try {
            recoveryLatch.await();
            Log.d(TAG, "Recovery complete — continuing automation.");
        } catch (InterruptedException e) {
            Log.w(TAG, "Interrupted while waiting for recovery to finish.");
            Thread.currentThread().interrupt();
        } finally {
            Thread.sleep(getRandomDelay(1000, 1200));
        }

        Log.d(TAG, "Click: rewards (540,1350)");
        for (int i = 0; i < 11; i++) {
            performClick(540, 1350);
            Thread.sleep(getRandomDelay(300, 400));
        }

        Log.d(TAG, "Click: close reward (1000,2300)");
        performClick(1000, 2210);
        Thread.sleep(getRandomDelay(1000, 1500));

        Log.d(TAG, "Click: dismiss offers (1,1)");
        performClick(1, 1);

        if (canOpenFreePackage()) {
            Log.d(TAG, "Free package is ready during automation. Opening...");
            openFreePackage();
            updateFreePackageTimestamp();
        }

        Log.d(TAG, "Resetting the automation, waiting 2 seconds before restarting...");
        Thread.sleep(getRandomDelay(1000, 1500));
    }

    private OpenAdResult simulateAdInteractionAndClose() {
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) {
            Log.w(TAG, "No root node available. Returning to the game...");
            switchBackToGame();
            return OpenAdResult.SUCCESS;
        }

        List<AccessibilityNodeInfo> clickableNodes = new ArrayList<>();
        findAllClickableNodes(rootNode, clickableNodes);

        if (clickableNodes.isEmpty()) {
            Log.w(TAG, "No clickable node found. Returning to the game...");
            switchBackToGame();
            return OpenAdResult.SUCCESS;
        }

        for (AccessibilityNodeInfo node : clickableNodes) {
            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            // Log.d(TAG, "Node info - Text: " + (text != null ? text : "null") + ", Description: " + (desc != null ? desc : "null"));

            if ((text != null && text.length() == 0) && (desc == null)) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                performClick(bounds.centerX(), bounds.centerY());
                // Log.d(TAG, "Close button clicked at coordinates : " + bounds.centerX() + "," + bounds.centerY());
                return OpenAdResult.CLOSE_BUTTON_CLICKED;
            }
        }

        int maxClicks = Math.min(6, clickableNodes.size());
        boolean clicked = false;

        for (int i = 0; i < maxClicks; i++) {
            if (redirectDetected || Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Redirection or interruption detected, stopping clicks.");
                return OpenAdResult.FORCE_CLOSE_AND_RESTART;
            }

            AccessibilityNodeInfo node = clickableNodes.get(i);
            CharSequence text = node.getText();
            if (text != null && text.toString().equalsIgnoreCase("Privacy")) {
                continue;
            }

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            performClick(bounds.centerX(), bounds.centerY());
            clicked = true;

            try {
                Thread.sleep(getRandomDelay(500, 1000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return OpenAdResult.FORCE_CLOSE_AND_RESTART;
            }

            if (redirectDetected || Thread.currentThread().isInterrupted()) {
                Log.w(TAG, "Redirection or interruption detected, stopping clicks.");
                return OpenAdResult.FORCE_CLOSE_AND_RESTART;
            }
        }


        if (clicked && !redirectDetected) {
            Log.d(TAG, "Ad successfully closed, returning to the game...");
            switchBackToGame();
            return OpenAdResult.SUCCESS;
        }
        return OpenAdResult.SUCCESS;
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private OpenAdResult openAdWithRetry() throws InterruptedException  {
        boolean adStarted = false;

        while (!adStarted) {
            Log.e(TAG, "Click: open Ad (360, 1800)");
            performClick(360, 1800);

            for (int i = 0; i < 10; i++) {
                Thread.sleep(getRandomDelay(200, 300));
                if (isInAdActivity()) {
                    adStarted = true;
                    adNotDetectedCount = 0;
                    break;
                }
            }

            if (!adStarted) {
                adNotDetectedCount++;

                if ("com.sec.android.app.launcher".equals(currentPackage) &&
                        "android.widget.FrameLayout".equals(currentClass)) {
                    clickAppDrawerButton();
                    if (isInAdActivity()) {
                        Log.d(TAG, "Ad detected after opening the app drawer.");
                        adNotDetectedCount = 0;
                        return OpenAdResult.SUCCESS;
                    }
                }

                Log.d(TAG, "Ad not detected after attempt, performing a refresh.");
                performClick(1000, 2210);
                Thread.sleep(getRandomDelay(1000, 1200));
                performClick(120, 2250);
                Thread.sleep(getRandomDelay(1000, 1200));
                performClick(120, 2250);
                Thread.sleep(getRandomDelay(1000, 1200));

                if (adNotDetectedCount >= 5) {
                    Log.w(TAG, "5 consecutive failures to detect an ad → restarting the service.");
                    speak("No advertisement detected for too long. Restarting the service.", "AD_DETECTION_FAILURE");
                    adNotDetectedCount = 0;
                    restartService();
                }

            } else {
                Log.d(TAG, "Ad detected, continuing automation.");
                adNotDetectedCount = 0;
                return OpenAdResult.SUCCESS;
            }
        }

        return OpenAdResult.SUCCESS;
    }

    private void restartService() throws InterruptedException {
        Log.d(TAG, "Restarting the service...");
        forceCloseGameAndReturn();
        switchBackToGame();
        Thread.sleep(getRandomDelay(5000, 8000));
    }

    private void clickAppDrawerButton() throws InterruptedException {
        Log.d(TAG, "Ad detection - attempting to access app drawer.");

        performSwipe(220, 2360, 220, 2200);
        Thread.sleep(getRandomDelay(500, 800));

        performClick(220, 2330);
        Thread.sleep(getRandomDelay(1000, 1100));
        performClick(220, 2330);
        Thread.sleep(getRandomDelay(1000, 1500));
        performClick(220, 2330);
        Thread.sleep(getRandomDelay(1000, 1100));
        performClick(220, 2330);
        Thread.sleep(getRandomDelay(1000, 1500));
    }


    private void startAdCloseWatcher() {
        latchSignaled = false;
        stopRequested = false;
        adWatcherHandler = new Handler(Looper.getMainLooper());
        handlersList.add(adWatcherHandler);

        long startTime = System.currentTimeMillis();

        adWatcherRunnable = new Runnable() {
            @Override
            public void run() {
                if (stopRequested) {
                    Log.d(TAG, "Watcher cancelled because stopRequested=true");
                    return;
                }

                if (latchSignaled) {
                    Log.d(TAG, "Latch already signaled → stopping the watcher.");
                    return;
                }

                if (System.currentTimeMillis() - startTime > 35000) {
                    Log.d(TAG, "End of the attempt to detect the close button (timeout 35s).");
                    return;
                }

                AccessibilityNodeInfo rootNode = getRootInActiveWindow();
                if (rootNode == null) {
                    Log.d(TAG, "Root node is null, retrying...");
                    adWatcherHandler.postDelayed(this, 1000);
                    return;
                }

                List<AccessibilityNodeInfo> clickableNodes = new ArrayList<>();
                findAllClickableNodes(rootNode, clickableNodes);

                for (AccessibilityNodeInfo node : clickableNodes) {
                    CharSequence text = node.getText();
                    CharSequence desc = node.getContentDescription();

                    if ((text != null && text.length() == 0) || (desc != null && desc.length() == 0)) {
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        performClick(bounds.centerX(), bounds.centerY());
                        Log.d(TAG, "Close button clicked at coordinates : " + bounds.centerX() + "," + bounds.centerY());

                        if (!latchSignaled) {
                            adCloseLatch.countDown();
                            latchSignaled = true;
                        }
                        return;
                    }
                }

                if (!latchSignaled && System.currentTimeMillis() - startTime > 15000) {
                    Log.w(TAG, "No button detected for 15s → forcing click on all clickableNodes");

                    if (clickableNodes.isEmpty()) {
                        Log.w(TAG, "No clickable button found, manual click (550,450)");
                        performClick(550,450);

                        if (redirectDetected) {
                            Log.w(TAG, "Redirection detected during manual click.");
                            return;
                        }
                    }

                    for (AccessibilityNodeInfo node : clickableNodes) {
                        CharSequence text = node.getText();
                        CharSequence desc = node.getContentDescription();
                        // Log.d(TAG, "Node info - Text: " + (text != null ? text : "null") + ", Description: " + (desc != null ? desc : "null"));

                        if (text != null && text.toString().equalsIgnoreCase("Privacy")) {
                            Log.d(TAG, "Ignore the node with the text 'Privacy'.");
                            continue;
                        }

                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);
                        // Log.d(TAG, "Node coordinates: " + bounds.left + ", " + bounds.top + ", " + bounds.right + ", " + bounds.bottom);

                        performClick(bounds.centerX(), bounds.centerY());

                        if (redirectDetected) {
                            Log.w(TAG, "Redirection detected during forced clicks.");
                            return;
                        }
                    }
                }
                adWatcherHandler.postDelayed(this, 1000);
            }
        };
        adWatcherHandler.postDelayed(adWatcherRunnable, 15000);
    }

    private void findAllClickableNodes(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> result) {
        if (node == null) return;
        if (node.isClickable()) result.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            findAllClickableNodes(node.getChild(i), result);
        }
    }

    private boolean isInGameActivity() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null && root.getPackageName() != null) {
            String currentPackage = root.getPackageName().toString();
            return currentPackage.equals("com.firsttouchgames.smp");
        }
        return false;
    }

    @TargetApi(Build.VERSION_CODES.N)
    private void performClick(int x, int y) {
        int xOffset = random.nextInt(10) - 5; // Variation de -5 à +5 pixels
        int yOffset = random.nextInt(10) - 5; // Variation de -5 à +5 pixels

        int adjustedX = x + xOffset;
        int adjustedY = y + yOffset;

        if (adjustedX < 0 || adjustedY < 0) {
            Log.w(TAG, "Offset skipped: negative adjusted coordinates detected (" + adjustedX + ", " + adjustedY + ")");
            adjustedX = x;
            adjustedY = y;
        }

        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path path = new Path();
        path.moveTo(adjustedX, adjustedY);
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 100));
        dispatchGesture(builder.build(), null, null);
    }

    @TargetApi(Build.VERSION_CODES.N)
    private void performSwipe(int startX, int startY, int endX, int endY) {
        Log.d(TAG, "Performing swipe from (" + startX + ", " + startY + ") to (" + endX + ", " + endY + ")");
        GestureDescription.Builder builder = new GestureDescription.Builder();
        Path path = new Path();
        path.moveTo(startX, startY);
        path.lineTo(endX, endY);
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 300));
        dispatchGesture(builder.build(), null, null);
        Log.d(TAG, "Swipe completed.");
    }

    private void forceCloseGameAndReturn() {
        try {
            Log.d(TAG, "Start force closing game...");

            unlockScreen(); // Débloquer l'écran

            performSwipe(220, 2360, 220, 2200); // Affiche les boutons système
            Thread.sleep(getRandomDelay(1600, 2200));

            performClick(220, 2360); // Ouvre les apps récentes
            Thread.sleep(getRandomDelay(1600, 2200));

            performSwipe(1000, 1300, 1000, 500); // Swipe vers le haut sur l'appli du jeu pour la fermer
            Thread.sleep(getRandomDelay(1600, 2200));

            performClick(220, 2360); // Revenir à l'app
            Thread.sleep(getRandomDelay(1600, 2200));

            Log.d(TAG, "Game closed and returned to automation app.");

        } catch (InterruptedException e) {
            Log.e(TAG, "Error while force closing game", e);
        }
    }

    private void unlockScreen() throws InterruptedException {
        Log.d(TAG, "Unlocking screen...");
        performSwipe(540, 1300, 540, 650); // Débloque l'écran
        Thread.sleep(getRandomDelay(2000, 2500));
    }

    private void wakeAndSwipeToUnlock() {
        Log.d(TAG, "Attempting to wake and unlock screen...");

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (powerManager == null) {
            Log.e(TAG, "PowerManager is null. Cannot acquire WakeLock.");
            return;
        }

        PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "AdAutomationService::UnlockScreen"
        );
        wakeLock.acquire(10 * 1000L);

        try {
            performSwipe(540, 1300, 540, 650);
            Thread.sleep(getRandomDelay(2000, 2500));
        } catch (InterruptedException e) {
            Log.e(TAG, "Error during unlock: " + e.getMessage());
        } finally {
            if (wakeLock.isHeld()) {
                wakeLock.release();
                Log.d(TAG, "WakeLock released.");
            }
        }
    }

    private void openFreePackage() throws InterruptedException {
        Log.d(TAG, "Opening free package...");
        Thread.sleep(getRandomDelay(2000, 2500));

        performClick(110, 1855);
        Thread.sleep(getRandomDelay(2000, 2500));

        for (int i = 0; i < 10; i++) {
            performClick(540, 1350);
            Thread.sleep(getRandomDelay(1000, 1500));
        }
        Thread.sleep(getRandomDelay(2000, 2500));

        performClick(1000, 2210);
    }

    private boolean canOpenFreePackage() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long lastTime = prefs.getLong(LAST_FREE_PACKAGE_TIME, 0);
        boolean useCustomDelay = prefs.getBoolean(USE_CUSTOM_DELAY, false);

        long baseDelay = useCustomDelay ? customFreePackageDelay : FOUR_HOURS;

        return System.currentTimeMillis() - lastTime >= baseDelay;
    }

    private void updateFreePackageTimestamp() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putLong(LAST_FREE_PACKAGE_TIME, System.currentTimeMillis())
                .putBoolean(USE_CUSTOM_DELAY, false)
                .apply();

        customFreePackageDelay = FOUR_HOURS;
        Log.d(TAG, "Free package opened. Delay reset to default: 4 hours.");
    }

    // Depuis le terminal: adb shell am broadcast -a com.hfad.advancedadautomation.RESET_FREE_PACKAGE
    private void resetFreePackageTimer(boolean isManuallyReset) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .remove(LAST_FREE_PACKAGE_TIME)
                .remove(USE_CUSTOM_DELAY)
                .apply();

        String msg = "Free package timer reset " + (isManuallyReset ? "manually." : "automatically.");
        Log.d(TAG, msg);
    }

    private long getRemainingFreePackageTime() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        long lastTime = prefs.getLong(LAST_FREE_PACKAGE_TIME, 0);
        boolean useCustomDelay = prefs.getBoolean(USE_CUSTOM_DELAY, false);
        long baseDelay = useCustomDelay ? customFreePackageDelay : FOUR_HOURS;
        long elapsed = System.currentTimeMillis() - lastTime;
        long remainingTime = Math.max(0, baseDelay - elapsed);

        Log.d(TAG, "Base delay used for remaining time: " + (baseDelay / 60000) + " minutes. Remaining time: " + (remainingTime / 60000) + " minutes.");
        return remainingTime;
    }

    private void switchBackToGame() {
        Log.d(TAG, "Switching to game...");

        try {
            launchApp("com.hfad.advancedadautomation", "com.hfad.advancedadautomation.MainActivity", "Returning to automation app...");
            Thread.sleep(getRandomDelay(1000, 1500));
            launchApp("com.firsttouchgames.smp", "com.firsttouchgames.smp.MainActivity", "Back in game!");
        } catch (InterruptedException e) {
            Log.e(TAG, "Thread interrompu dans switchBackToGame()");
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            Log.e(TAG, "Error switching apps", e);
        }
    }

    private void launchApp(String packageName, String className, String logMessage) {
        Log.d(TAG, logMessage);
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName, className));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private long getRandomDelay(long minDelay, long maxDelay) {
        Random random = new Random();
        return minDelay + (long) (random.nextDouble() * (maxDelay - minDelay));
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void speak(String text, String utteranceId) {
        if (tts != null && isTtsInitialized) {
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);

            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String utteranceId) {
                    Log.d(TAG, "TTS started: " + utteranceId);
                    restoreMediaVolume();
                }

                @Override
                public void onDone(String utteranceId) {
                    Log.d(TAG, "TTS done: " + utteranceId);
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        muteMediaVolume();
                    }, 1000);
                }

                @Override
                public void onError(String utteranceId) {
                    Log.e(TAG, "TTS error on: " + utteranceId);
                    muteMediaVolume();
                }
            });

            tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utteranceId);
        } else {
            Log.w(TAG, "TTS not initialized. Skipping speak.");
        }
    }

    private void muteMediaVolume() {
        if (audioManager != null) {
            previousVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0);
            Log.d(TAG, "Media volume muted.");
        }
    }

    private void restoreMediaVolume() {
        if (audioManager != null) {
            if (previousVolume > 0) {
                // Si le volume précédent était valide, on le restaure
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0);
                Log.d(TAG, "Media volume restored to " + previousVolume);
            } else {
                // Si le volume précédent est 0, on définit le volume à 35%
                int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int volumeToSet = (int) (maxVolume * 0.35);  // 35% du volume max
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumeToSet, 0);
                Log.d(TAG, "Media volume set to 35% of max.");
            }
            previousVolume = -1;  // Réinitialise après l'utilisation
        }
    }

    public void stopServiceLogic() {
        isRunning = false;
        LogMonitor.stopListening();
        if (automationFuture != null && !automationFuture.isDone()) {
            automationFuture.cancel(true);
        }
        // Arrête les threads d’interaction avec les pubs
        if (adInteractionThread != null && adInteractionThread.isAlive()) {
            adInteractionThread.interrupt();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (tts != null) {
            tts.stop();
        }
        for (Handler handler : handlersList) {
            handler.removeCallbacksAndMessages(null);
        }

        Log.d(TAG, "All tasks, threads and resources cleaned up.");
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopServiceLogic();
        if (serviceReceiver != null) {
            unregisterReceiver(serviceReceiver);
        }
        if (tts != null) {
            tts.stop();
            tts.shutdown();
            tts = null;
        }
        Log.d(TAG, "Service destroyed.");
    }
}
