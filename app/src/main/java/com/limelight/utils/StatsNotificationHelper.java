package com.limelight.utils;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;

public class StatsNotificationHelper {
    private static final String CHANNEL_ID = "streaming_stats_channel";
    private static final int NOTIFICATION_ID = 1001;

    private final Context context;
    private final NotificationManager notificationManager;
    private boolean isShowing = false;

    public StatsNotificationHelper(Context context) {
        this.context = context;
        this.notificationManager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.stats_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(context.getString(R.string.stats_notification_channel_description));
        channel.setShowBadge(false);
        channel.enableVibration(false);
        channel.setSound(null, null);
        notificationManager.createNotificationChannel(channel);
    }

    private boolean hasNotificationPermission() {
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
    }

    private String simplifyStatsText(String statsText) {
        // Parse the stats text and extract key metrics only
        // Expected format: "H.264 60.00 FPS 1920x1080 15.20 Mbps RTT: 2 ms (variance: 0 ms)"
        try {
            StringBuilder simplified = new StringBuilder();

            // Extract FPS
            if (statsText.contains("FPS")) {
                int fpsEnd = statsText.indexOf("FPS");
                // Find the start of FPS number (look backwards for space)
                int fpsStart = statsText.lastIndexOf(' ', fpsEnd - 1);
                if (fpsStart >= 0 && fpsEnd > fpsStart) {
                    String fps = statsText.substring(fpsStart + 1, fpsEnd).trim();
                    simplified.append(fps).append(" FPS");
                }
            }

            // Extract bitrate
            if (statsText.contains("Mbps")) {
                int mbpsEnd = statsText.indexOf("Mbps");
                // Find the start of Mbps number (look backwards for space)
                int mbpsStart = statsText.lastIndexOf(' ', mbpsEnd - 1);
                if (mbpsStart >= 0 && mbpsEnd > mbpsStart) {
                    String bitrate = statsText.substring(mbpsStart + 1, mbpsEnd).trim();
                    if (simplified.length() > 0) {
                        simplified.append(" | ");
                    }
                    simplified.append(bitrate).append(" Mbps");
                }
            }

            // Extract RTT (ping)
            if (statsText.contains("RTT:")) {
                int rttStart = statsText.indexOf("RTT:") + 4;
                int rttEnd = statsText.indexOf("ms", rttStart);
                if (rttEnd > rttStart) {
                    String rtt = statsText.substring(rttStart, rttEnd).trim();
                    if (simplified.length() > 0) {
                        simplified.append(" | ");
                    }
                    simplified.append(rtt).append(" ms");
                }
            }

            String result = simplified.toString();
            return result.isEmpty() ? statsText : result;
        } catch (Exception e) {
            LimeLog.warning("Failed to simplify stats: " + e.getMessage());
            return statsText;
        }
    }

    public void showNotification(String statsText, String videoCodec) {
        // Check for notification permission before attempting to show
        if (!hasNotificationPermission()) {
            return;
        }

        // Simplify the stats text
        String simplifiedStats = simplifyStatsText(statsText);

        // Add codec info if available
        String title = context.getString(R.string.stats_notification_title);
        if (videoCodec != null && !videoCodec.isEmpty() && !videoCodec.equals("Unknown")) {
            title = videoCodec + " - " + context.getString(R.string.stats_notification_title);
        }

        Intent intent = new Intent(context, Game.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        Notification.Builder builder = new Notification.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title)
                .setContentText(simplifiedStats)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pendingIntent)
                .setCategory(Notification.CATEGORY_STATUS);

        notificationManager.notify(NOTIFICATION_ID, builder.build());
        isShowing = true;
    }

    // Overload for backward compatibility
    public void showNotification(String statsText) {
        showNotification(statsText, "");
    }

    public void updateNotification(String statsText, String videoCodec) {
        if (isShowing) {
            showNotification(statsText, videoCodec);
        }
    }

    public void cancelNotification() {
        notificationManager.cancel(NOTIFICATION_ID);
        isShowing = false;
    }

    public boolean isShowing() {
        return isShowing;
    }
}
