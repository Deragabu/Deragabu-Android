package com.limelight.computers;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.binding.wireguard.WireGuardManager;
import com.limelight.binding.wireguard.WgSocketFactory;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.nvstream.http.PairingManager.PairState;

import java.io.FileNotFoundException;
import java.net.UnknownHostException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PairingService extends Service {
    private static final String TAG = "PairingService";
    private static final String CHANNEL_ID = "pairing_channel";
    private static final int NOTIFICATION_ID = 2001;

    public static final String EXTRA_COMPUTER_UUID = "computer_uuid";
    public static final String EXTRA_COMPUTER_NAME = "computer_name";
    public static final String EXTRA_COMPUTER_ADDRESS = "computer_address";
    public static final String EXTRA_COMPUTER_HTTP_PORT = "computer_http_port";
    public static final String EXTRA_COMPUTER_HTTPS_PORT = "computer_https_port";
    public static final String EXTRA_SERVER_CERT = "server_cert";
    public static final String EXTRA_UNIQUE_ID = "unique_id";

    // Sunshine auto-pairing extras
    public static final String EXTRA_SUNSHINE_USERNAME = "sunshine_username";
    public static final String EXTRA_SUNSHINE_PASSWORD = "sunshine_password";

    public static final String ACTION_CANCEL_PAIRING = "com.limelight.CANCEL_PAIRING";

    private NotificationManager notificationManager;
    private final PairingBinder binder = new PairingBinder();
    private PairingListener listener;
    private Thread pairingThread;
    private volatile boolean cancelled = false;
    private String currentPin;
    
    // WireGuard proxy state
    private volatile boolean wgProxyStarted = false;

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
        if (ACTION_CANCEL_PAIRING.equals(action)) {
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
        final int httpPort = intent.getIntExtra(EXTRA_COMPUTER_HTTP_PORT, NvHTTP.DEFAULT_HTTP_PORT);
        final int httpsPort = intent.getIntExtra(EXTRA_COMPUTER_HTTPS_PORT, 0);
        final byte[] serverCertBytes = intent.getByteArrayExtra(EXTRA_SERVER_CERT);
        final String uniqueId = intent.getStringExtra(EXTRA_UNIQUE_ID);

        // Sunshine auto-pairing credentials
        final String sunshineUsername = intent.getStringExtra(EXTRA_SUNSHINE_USERNAME);
        final String sunshinePassword = intent.getStringExtra(EXTRA_SUNSHINE_PASSWORD);

        if (computerUuid == null || computerAddress == null || uniqueId == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (sunshineUsername == null || sunshinePassword == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // Generate PIN
        currentPin = PairingManager.generatePinString();

        // Get device name for pairing
        final String deviceName = android.os.Build.MODEL;

        // Show notification
        showPairingNotification(computerName, deviceName);

        // Start pairing in background using Sunshine API
        cancelled = false;
        pairingThread = new Thread(() ->
                doSunshinePairing(computerUuid, computerName, computerAddress, httpPort, httpsPort,
                        serverCertBytes, uniqueId, currentPin, sunshineUsername, sunshinePassword, deviceName));
        pairingThread.start();

        return START_STICKY;
    }


    private void showPairingNotification(String computerName, String deviceName) {
        // Intent to open PcView
        Intent openIntent = new Intent(this, PcView.class);
        openIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this, 0, openIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        // Intent to cancel pairing
        Intent cancelIntent = new Intent(this, PairingService.class);
        cancelIntent.setAction(ACTION_CANCEL_PAIRING);
        PendingIntent cancelPendingIntent = PendingIntent.getService(
                this, 2, cancelIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
        );

        String title = getString(R.string.pairing_notification_title, computerName);
        String content = getString(R.string.pairing_notification_auto_content, deviceName);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(content)
                .setStyle(new Notification.BigTextStyle().bigText(content))
                .setOngoing(true)
                .setAutoCancel(false)
                .setContentIntent(openPendingIntent)
                .addAction(new Notification.Action.Builder(
                        null, getString(android.R.string.cancel), cancelPendingIntent).build())
                .setCategory(Notification.CATEGORY_PROGRESS);

        startForeground(NOTIFICATION_ID, builder.build());
    }

    private void updateNotificationSuccess(String computerName) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.pairing_notification_success_title))
                .setContentText(getString(R.string.pairing_notification_success_content, computerName))
                .setAutoCancel(true)
                .setTimeoutAfter(5000)
                .setCategory(Notification.CATEGORY_STATUS);

        notificationManager.notify(NOTIFICATION_ID + 1, builder.build());
    }

    private void updateNotificationFailed(String computerName, String reason) {
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(getString(R.string.pairing_notification_failed_title))
                .setContentText(getString(R.string.pairing_notification_failed_content, computerName, reason))
                .setAutoCancel(true)
                .setTimeoutAfter(10000)
                .setCategory(Notification.CATEGORY_ERROR);

        notificationManager.notify(NOTIFICATION_ID + 1, builder.build());
    }


    /**
     * Perform pairing using Sunshine's REST API with username/password authentication
     * Flow: pm.pair() sends pairing request -> server waits for PIN -> /api/pin submits PIN -> pairing completes
     */
    private void doSunshinePairing(String computerUuid, String computerName, String computerAddress,
                                   int httpPort, int httpsPort, byte[] serverCertBytes, String uniqueId,
                                   String pin, String username, String password, String deviceName) {
        String message = null;
        X509Certificate pairedCert = null;
        boolean success = false;

        // Setup WireGuard proxy if enabled
        setupWireGuardProxy(computerAddress);
        
        // Use the computer address directly (WireGuard routing handled by JNI layer)
        String effectiveAddress = computerAddress;

        try {
            java.security.cert.X509Certificate serverCert = null;
            if (serverCertBytes != null) {
                java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
                serverCert = (java.security.cert.X509Certificate) cf.generateCertificate(
                        new java.io.ByteArrayInputStream(serverCertBytes));
            }

            ComputerDetails.AddressTuple addressTuple = new ComputerDetails.AddressTuple(effectiveAddress, httpPort);

            NvHTTP httpConn = new NvHTTP(
                    addressTuple,
                    httpsPort, uniqueId, serverCert,
                    PlatformBinding.getCryptoProvider(this));

            if (httpConn.getPairState() == PairState.PAIRED) {
                success = true;
                pairedCert = httpConn.getPairingManager().getPairedCert();
            } else {
                // Step 1: Verify Sunshine credentials before starting pairing
                Log.i(TAG, "Verifying Sunshine credentials...");
                int verifyResult = verifySunshineCredentials(effectiveAddress, username, password);
                if (verifyResult == 401) {
                    Log.e(TAG, "Sunshine authentication failed - invalid credentials");
                    message = getString(R.string.sunshine_pairing_auth_failed);
                } else if (verifyResult != 200 && verifyResult != -2 && verifyResult != -1) {
                    // -2 means endpoint not found (older Sunshine), proceed with pairing
                    // -1 means network error (proxy issue, etc.), also proceed with pairing
                    Log.e(TAG, "Failed to verify Sunshine credentials, response code: " + verifyResult);
                    message = getString(R.string.pair_fail);
                } else {
                    if (verifyResult == -1) {
                        Log.w(TAG, "Sunshine credential verification had network error, proceeding with pairing anyway");
                    }
                    // Credentials verified or verification not supported, proceed with pairing
                    PairingManager pm = httpConn.getPairingManager();

                    // Use AtomicBoolean to capture PIN submission result from background thread
                    final java.util.concurrent.atomic.AtomicBoolean pinSubmitSuccess = new java.util.concurrent.atomic.AtomicBoolean(false);
                    final java.util.concurrent.atomic.AtomicBoolean pinSubmitAuthFailed = new java.util.concurrent.atomic.AtomicBoolean(false);

                    // Schedule PIN submission to run after a short delay (to ensure pm.pair() has started)
                    final String finalEffectiveAddress = effectiveAddress;
                    final Thread currentPairingThread = pairingThread;
                    Thread pinThread = new Thread(() -> {
                        try {
                            // Wait a bit for pm.pair() to start and send the initial pairing request
                            Thread.sleep(500);
                            Log.i(TAG, "Submitting PIN to Sunshine API...");
                            int result = sendPinToSunshine(finalEffectiveAddress, username, password, pin, deviceName);
                            if (result == 200) {
                                Log.i(TAG, "PIN submitted successfully");
                                pinSubmitSuccess.set(true);
                            } else if (result == 401) {
                                Log.e(TAG, "Authentication failed (401) - invalid credentials");
                                pinSubmitAuthFailed.set(true);
                                // Interrupt the pairing thread to stop waiting
                                if (currentPairingThread != null) {
                                    currentPairingThread.interrupt();
                                }
                            } else {
                                Log.e(TAG, "Failed to submit PIN to Sunshine API, response code: " + result);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
                    pinThread.start();

                    // Step 2: This call blocks until server receives PIN and completes pairing
                    try {
                        PairState pairState = pm.pair(pin);

                        if (pairState == PairState.PIN_WRONG) {
                            message = getString(R.string.pair_incorrect_pin);
                        } else if (pairState == PairState.FAILED) {
                            // Check if it was due to authentication failure
                            if (pinSubmitAuthFailed.get()) {
                                message = getString(R.string.sunshine_pairing_auth_failed);
                            } else {
                                message = getString(R.string.pair_fail);
                            }
                        } else if (pairState == PairState.ALREADY_IN_PROGRESS) {
                            message = getString(R.string.pair_already_in_progress);
                        } else if (pairState == PairState.PAIRED) {
                            success = true;
                            pairedCert = pm.getPairedCert();
                        }
                    } catch (Exception e) {
                        // Check if interrupted due to auth failure
                        if (pinSubmitAuthFailed.get()) {
                            message = getString(R.string.sunshine_pairing_auth_failed);
                        } else if (!cancelled) {
                            throw e;
                        }
                    }
                }
            }
        } catch (UnknownHostException e) {
            message = getString(R.string.error_unknown_host);
        } catch (FileNotFoundException e) {
            message = getString(R.string.error_404);
        } catch (Exception e) {
            //LimeLog.warning("Sunshine pairing failed: " + e.getMessage());
            Log.e(TAG, "Sunshine pairing failed: " + e.getMessage(), e);
            message = e.getMessage();
        }

        if (cancelled) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            stopWireGuardProxy();
            stopSelf();
            return;
        }

        stopForeground(STOP_FOREGROUND_REMOVE);
        
        // Clean up WireGuard proxy 
        stopWireGuardProxy();

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

    /**
     * Build an OkHttpClient that trusts all certificates and routes through WireGuard when enabled.
     */
    @SuppressLint("CustomX509TrustManager")
    private OkHttpClient buildWgAwareHttpClient() {
        @SuppressLint("TrustAllX509TrustManager")
        X509TrustManager trustAllManager = new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
            public void checkClientTrusted(X509Certificate[] certs, String authType) {}
            public void checkServerTrusted(X509Certificate[] certs, String authType) {}
        };

        try {
            SSLContext sc = SSLContext.getInstance("TLS");
            sc.init(null, new TrustManager[]{trustAllManager}, new SecureRandom());

            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                    .sslSocketFactory(sc.getSocketFactory(), trustAllManager)
                    .hostnameVerifier((hostname, session) -> true)
                    .connectTimeout(15, TimeUnit.SECONDS)
                    .readTimeout(15, TimeUnit.SECONDS);

            // Route through WireGuard when HTTP config is active
            if (NvHTTP.isDirectWgHttpEnabled()) {
                builder.socketFactory(WgSocketFactory.getInstance());
                Log.i(TAG, "Sunshine API using WireGuard routing via WgSocketFactory");
            }

            return builder.build();
        } catch (NoSuchAlgorithmException | KeyManagementException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Verify Sunshine credentials by calling a simple API endpoint
     *
     * @return HTTP response code (200 = success, 401 = auth failed, -2 = endpoint not found, -1 = error)
     */
    private int verifySunshineCredentials(String computerAddress, String username, String password) {
        Log.i(TAG, ">>> verifySunshineCredentials CALLED: address=" + computerAddress);
        try {
            // Build URL for Sunshine API - use /api/apps as a simple endpoint to verify auth
            String host = computerAddress;
            if (host.contains(":") && !host.startsWith("[")) {
                host = "[" + host + "]";
            }
            String url = "https://" + host + ":47990/api/apps";

            Log.i(TAG, "Verifying Sunshine credentials: " + url + ", wgProxyStarted=" + wgProxyStarted);

            // Create Basic Auth header
            String credentials = username + ":" + password;
            String basicAuth = "Basic " + android.util.Base64.encodeToString(
                    credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);

            OkHttpClient client = buildWgAwareHttpClient();
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .header("Authorization", basicAuth)
                    .header("Accept", "*/*")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                int responseCode = response.code();
                Log.i(TAG, "Sunshine credentials verification response code: " + responseCode);

                if (responseCode == 200) {
                    return 200;
                } else if (responseCode == 401) {
                    Log.w(TAG, "Sunshine authentication failed (401)");
                    return 401;
                } else if (responseCode == 404) {
                    Log.i(TAG, "API endpoint not found, proceeding with pairing");
                    return -2;
                } else {
                    return responseCode;
                }
            }
        } catch (java.io.FileNotFoundException e) {
            Log.i(TAG, "API endpoint not found (FileNotFoundException), proceeding with pairing");
            return -2;
        } catch (Exception e) {
            Log.e(TAG, "Failed to verify Sunshine credentials: " + e.getMessage(), e);
            return -1;
        }
    }

    /**
     * Send PIN to Sunshine server via its REST API
     *
     * @return HTTP response code (200 = success, 401 = auth failed, -1 = error)
     */
    private int sendPinToSunshine(String computerAddress, String username, String password,
                                      String pin, String deviceName) {
        try {
            // Build URL for Sunshine API
            String host = computerAddress;
            if (host.contains(":") && !host.startsWith("[")) {
                host = "[" + host + "]";
            }
            String url = "https://" + host + ":47990/api/pin";

            Log.i(TAG, "Sending PIN to Sunshine API: " + url + ", wgProxyStarted=" + wgProxyStarted);

            // Create JSON payload
            org.json.JSONObject jsonPayload = new org.json.JSONObject();
            jsonPayload.put("pin", pin);
            jsonPayload.put("name", deviceName);

            // Create Basic Auth header
            String credentials = username + ":" + password;
            String basicAuth = "Basic " + android.util.Base64.encodeToString(
                    credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);

            OkHttpClient client = buildWgAwareHttpClient().newBuilder()
                    .readTimeout(30, TimeUnit.SECONDS)
                    .build();

            RequestBody body = RequestBody.create(
                    jsonPayload.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    MediaType.parse("application/json; charset=utf-8"));

            Request request = new Request.Builder()
                    .url(url)
                    .post(body)
                    .header("Authorization", basicAuth)
                    .header("Accept", "*/*")
                    .build();

            try (Response response = client.newCall(request).execute()) {
                int responseCode = response.code();
                Log.i(TAG, "Sunshine API response code: " + responseCode);

                if (responseCode == 200) {
                    return 200;
                } else if (responseCode == 401) {
                    Log.w(TAG, "Sunshine API authentication failed (401)");
                    return 401;
                } else {
                    // Log error body if available
                    if (response.body() != null) {
                        String errorBody = response.body().string();
                        Log.w(TAG, "Sunshine API error response: " + errorBody);
                    }
                    return responseCode;
                }
            }
        } catch (javax.net.ssl.SSLHandshakeException e) {
            Log.e(TAG, "SSL Handshake failed: " + e.getMessage(), e);
            return -1;
        } catch (java.net.SocketTimeoutException e) {
            Log.w(TAG, "Connection timeout: " + e.getMessage(), e);
            return -1;
        } catch (Exception e) {
            Log.e(TAG, "Failed to send PIN to Sunshine: " + e.getMessage(), e);
            return -1;
        }
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
        
        // Stop WireGuard proxy if we started it
        stopWireGuardProxy();
    }
    
    /**
     * Set up WireGuard HTTP routing for pairing if enabled in preferences
     * @param computerAddress The target server address to route through WireGuard
     */
    private void setupWireGuardProxy(String computerAddress) {
        SharedPreferences wgPrefs = getSharedPreferences("wireguard_config", Context.MODE_PRIVATE);
        boolean wgEnabled = wgPrefs.getBoolean("wg_enabled", false);
        Log.i(TAG, "setupWireGuardProxy: wg_enabled=" + wgEnabled + ", computerAddress=" + computerAddress);
        
        if (!wgEnabled) {
            return;
        }
        
        String wgPrivateKey = wgPrefs.getString("wg_private_key", "");
        String wgPeerPublicKey = wgPrefs.getString("wg_peer_public_key", "");
        String wgPresharedKey = wgPrefs.getString("wg_preshared_key", "");
        String wgEndpoint = wgPrefs.getString("wg_peer_endpoint", "");
        String wgTunnelAddress = wgPrefs.getString("wg_tunnel_address", "10.0.0.2");
        
        if (wgPrivateKey.isEmpty() || wgPeerPublicKey.isEmpty() || wgEndpoint.isEmpty()) {
            Log.w(TAG, "WireGuard enabled but configuration incomplete");
            return;
        }
        
        try {
            WireGuardManager.Config wgConfig = new WireGuardManager.Config()
                    .setPrivateKeyBase64(wgPrivateKey)
                    .setPeerPublicKeyBase64(wgPeerPublicKey)
                    .setPresharedKeyBase64(wgPresharedKey.isEmpty() ? null : wgPresharedKey)
                    .setEndpoint(wgEndpoint)
                    .setTunnelAddress(wgTunnelAddress);
            
            // Configure WireGuard HTTP routing (OkHttp uses WgSocket)
            // Use the target computer address for routing
            if (WireGuardManager.configureHttp(wgConfig, computerAddress)) {
                wgProxyStarted = true;
                Log.i(TAG, "WireGuard configured for pairing to " + computerAddress);
            } else {
                Log.e(TAG, "Failed to configure WireGuard HTTP for pairing");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to setup WireGuard for pairing", e);
        }
    }
    
    /**
     * Stop WireGuard HTTP if we started it
     */
    private void stopWireGuardProxy() {
        if (wgProxyStarted) {
            // Clear direct HTTP config
            WireGuardManager.clearHttpConfig();
            
            wgProxyStarted = false;
            Log.i(TAG, "WireGuard stopped after pairing");
        }
    }
}
