package com.limelight.preferences;

import android.content.Context;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.PreferenceDataStore;

import com.limelight.R;
import com.limelight.binding.wireguard.WireGuardManager;
import com.limelight.utils.UiHelper;

/**
 * Activity for configuring WireGuard VPN tunnel settings.
 * Uses PreferenceFragment for settings UI with MMKV storage.
 */
public class WireGuardSettingsActivity extends AppCompatActivity {
    private static final String TAG = "WireGuardSettings";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);
        setContentView(R.layout.activity_wireguard_settings);

        UiHelper.notifyNewRootView(this);
        UiHelper.applyStatusBarPadding(findViewById(android.R.id.content));

        // Enable back button in action bar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.wireguard_settings_title);
        }

        // Load the preferences fragment
        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.wireguard_settings_container, new WireGuardSettingsFragment())
                    .commit();
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Static method to load WireGuard config from MMKV preferences
     */
    public static WireGuardManager.Config loadConfig(Context context) {
        PreferenceDataStore dataStore = MMKVPreferenceManager.getPreferenceDataStore(context);

        if (!dataStore.getBoolean(WireGuardSettingsFragment.PREF_ENABLED, false)) {
            return null;
        }

        String privateKey = dataStore.getString(WireGuardSettingsFragment.PREF_PRIVATE_KEY, "");
        String peerPublicKey = dataStore.getString(WireGuardSettingsFragment.PREF_PEER_PUBLIC_KEY, "");
        String peerEndpoint = dataStore.getString(WireGuardSettingsFragment.PREF_PEER_ENDPOINT, "");

        if (privateKey == null || privateKey.isEmpty() || 
            peerPublicKey == null || peerPublicKey.isEmpty() || 
            peerEndpoint == null || peerEndpoint.isEmpty()) {
            return null;
        }

        int mtu = 1420;
        try {
            String mtuStr = dataStore.getString(WireGuardSettingsFragment.PREF_MTU, "1420");
            mtu = Integer.parseInt(mtuStr != null ? mtuStr : "1420");
        } catch (NumberFormatException ignored) {
        }

        String presharedKey = dataStore.getString(WireGuardSettingsFragment.PREF_PRESHARED_KEY, "");
        String tunnelAddress = dataStore.getString(WireGuardSettingsFragment.PREF_TUNNEL_ADDRESS, "10.0.0.2");

        return new WireGuardManager.Config()
                .setPrivateKeyBase64(privateKey)
                .setPeerPublicKeyBase64(peerPublicKey)
                .setPresharedKeyBase64(presharedKey != null && !presharedKey.isEmpty() ? presharedKey : null)
                .setEndpoint(peerEndpoint)
                .setTunnelAddress(tunnelAddress != null ? tunnelAddress : "10.0.0.2")
                .setMtu(mtu);
    }

    /**
     * Check if WireGuard is enabled in preferences
     */
    public static boolean isEnabled(Context context) {
        PreferenceDataStore dataStore = MMKVPreferenceManager.getPreferenceDataStore(context);
        return dataStore.getBoolean(WireGuardSettingsFragment.PREF_ENABLED, false);
    }
}

