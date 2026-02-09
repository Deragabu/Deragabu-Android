package com.limelight.computers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.PcView;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.UnknownHostException;
import java.security.cert.X509Certificate;

public class PairingService extends Service {
    private static final String CHANNEL_ID = "pairing_channel";
    private static final int NOTIFICATION_ID = 2001;

    public static final String EXTRA_COMPUTER_UUID = "computer_uuid";
    public static final String EXTRA_COMPUTER_NAME = "computer_name";
    public static final String EXTRA_COMPUTER_ADDRESS = "computer_address";
    public static final String EXTRA_COMPUTER_HTTPS_PORT = "computer_https_port";
    public static final String EXTRA_SERVER_CERT = "server_cert";
    public static final String EXTRA_UNIQUE_ID = "unique_id";

    public static final String ACTION_COPY_PIN = "com.limelight.COPY_PIN";
    public static final String ACTION_CANCEL_PAIRING = "com.limelight.CANCEL_PAIRING";

    private NotificationManager notificationManager;
    private final PairingBinder binder = new PairingBinder();
    private PairingListener listener;
    private Thread pairingThread;
    private volatile boolean cancelled = false;
    private String currentPin;

    public interface PairingListener {
        void onPairingSuccess(String computerUuid, X509Certificate serverCert);
        void onPairingFailed(String computerUuid, String message);
    }

    public class PairingBinder extends Binder {
        public void setListener(PairingListener listener) {
            PairingService.this.listener = listener;
        }

        @SuppressWarnings("unused")
        public void cancelPairing() {
            cancelled = true;
            if (pairingThread != null) {
                pairingThread.interrupt();
            }
            stopSelf();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.pairing_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription(getString(R.string.pairing_notification_channel_description));
        channel.setShowBadge(true);
        channel.enableVibration(true);
        notificationManager.createNotificationChannel(channel);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_COPY_PIN.equals(action)) {
            if (currentPin != null) {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("PIN", currentPin);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(this, R.string.pair_pin_copied, Toast.LENGTH_SHORT).show();
            }
            return START_STICKY;
        } else if (ACTION_CANCEL_PAIRING.equals(action)) {
            cancelled = true;
            if (pairingThread != null) {
                pairingThread.interrupt();
            }
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return START_NOT_STICKY;
        }

        final String computerUuid = intent.getStringExtra(EXTRA_COMPUTER_UUID);
        final String computerName = intent.getStringExtra(EXTRA_COMPUTER_NAME);
        final String computerAddress = intent.getStringExtra(EXTRA_COMPUTER_ADDRESS);
        final int httpsPort = intent.getIntExtra(EXTRA_COMPUTER_HTTPS_PORT, 47984);
        final byte[] serverCertBytes = intent.getByteArrayExtra(EXTRA_SERVER_CERT);
        final String uniqueId = intent.getStringExtra(EXTRA_UNIQUE_ID);

        if (computerUuid == null || computerAddress == null || uniqueId == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Generate PIN
        currentPin = PairingManager.generatePinString();

        // Show notification
        showPairingNotification(computerName, currentPin);

        // Start pairing in background
        cancelled = false;
        pairingThread = new Thread(() ->
            doPairing(computerUuid, computerName, computerAddress, httpsPort, serverCertBytes, uniqueId, currentPin));
        pairingThread.start();

        return START_STICKY;
    }

    private void showPairingNotification(String computerName, String pin) {
        // Intent to open PcView
        Intent openIntent = new Intent(this, PcView.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Intent to copy PIN
        Intent copyIntent = new Intent(this, PairingService.class);
        copyIntent.setAction(ACTION_COPY_PIN);
        PendingIntent copyPendingIntent = PendingIntent.getService(
                this, 1, copyIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Intent to cancel pairing
        Intent cancelIntent = new Intent(this, PairingService.class);
        cancelIntent.setAction(ACTION_CANCEL_PAIRING);
        PendingIntent cancelPendingIntent = PendingIntent.getService(
                this, 2, cancelIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String title = getString(R.string.pairing_notification_title, computerName);
        String content = getString(R.string.pairing_notification_content, pin);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(
                        content + "\n\n" + getString(R.string.pair_pairing_help)))
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(openPendingIntent)
                .addAction(new Notification.Action.Builder(
                        null, getString(android.R.string.copy), copyPendingIntent).build())
                .addAction(new Notification.Action.Builder(
                        null, getString(android.R.string.cancel), cancelPendingIntent).build())
                .setCategory(Notification.CATEGORY_PROGRESS);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void updateNotificationSuccess(String computerName) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(getString(R.string.pairing_notification_success_title))
                .setContentText(getString(R.string.pairing_notification_success_content, computerName))
                .setAutoCancel(true)
                .setTimeoutAfter(5000)
                .setCategory(Notification.CATEGORY_STATUS);

        notificationManager.notify(NOTIFICATION_ID + 1, builder.build());
    }

    private void updateNotificationFailed(String computerName, String reason) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.app_icon)
                .setContentTitle(getString(R.string.pairing_notification_failed_title))
                .setContentText(getString(R.string.pairing_notification_failed_content, computerName, reason))
                .setAutoCancel(true)
                .setTimeoutAfter(10000)
                .setCategory(Notification.CATEGORY_ERROR);

        notificationManager.notify(NOTIFICATION_ID + 1, builder.build());
    }

    private void doPairing(String computerUuid, String computerName, String computerAddress,
                           int httpsPort, byte[] serverCertBytes, String uniqueId, String pin) {
        String message = null;
        X509Certificate pairedCert = null;
        boolean success = false;

        try {
            java.security.cert.X509Certificate serverCert = null;
            if (serverCertBytes != null) {
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                serverCert = (java.security.cert.X509Certificate) cf.generateCertificate(
                        new java.io.ByteArrayInputStream(serverCertBytes));
            }

            ComputerDetails.AddressTuple addressTuple = new ComputerDetails.AddressTuple(computerAddress, httpsPort);

            NvHTTP httpConn = new NvHTTP(
                    addressTuple,
                    httpsPort, uniqueId, serverCert,
                    PlatformBinding.getCryptoProvider(this));

            if (httpConn.getPairState() == PairState.PAIRED) {
                success = true;
            } else {
                PairingManager pm = httpConn.getPairingManager();
                PairState pairState = pm.pair(httpConn.getServerInfo(true), pin);

                if (pairState == PairState.PIN_WRONG) {
                    message = getString(R.string.pair_incorrect_pin);
                } else if (pairState == PairState.FAILED) {
                    message = getString(R.string.pair_fail);
                } else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                    message = getString(R.string.pair_already_in_progress);
                } else if (pairState == PairState.PAIRED) {
                    success = true;
                    pairedCert = pm.getPairedCert();
                }
            }
        } catch (UnknownHostException e) {
            message = getString(R.string.error_unknown_host);
        } catch (FileNotFoundException e) {
            message = getString(R.string.error_404);
        } catch (Exception e) {
            LimeLog.warning("Pairing failed: " + e.getMessage());
            message = e.getMessage();
        }

        if (cancelled) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopSelf();
            return;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);

        if (success) {
            updateNotificationSuccess(computerName);
            if (listener != null) {
                listener.onPairingSuccess(computerUuid, pairedCert);
            }
        } else {
            updateNotificationFailed(computerName, message != null ? message : "Unknown error");
            if (listener != null) {
                listener.onPairingFailed(computerUuid, message);
            }
        }

        stopSelf();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelled = true;
        if (pairingThread != null) {
            pairingThread.interrupt();
        }
    }
}
