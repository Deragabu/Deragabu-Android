package com.limelight.grid.assets;

import android.content.Context;
import android.util.Log;

import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.utils.ServerHelper;

import java.io.IOException;
import java.io.InputStream;

public class NetworkAssetLoader {
        private static final String TAG = "NetworkAssetLoader";
    private final Context context;
    private final String uniqueId;

    public NetworkAssetLoader(Context context, String uniqueId) {
        this.context = context;
        this.uniqueId = uniqueId;
    }

    public InputStream getBitmapStream(CachedAppAssetLoader.LoaderTuple tuple) {
        InputStream in = null;
        try {
            NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(tuple.computer),
                    tuple.computer.httpsPort, uniqueId, tuple.computer.serverCert,
                    PlatformBinding.getCryptoProvider(context));
            in = http.getBoxArt(tuple.app);
        } catch (IOException ignored) {}

        if (in != null) {
            Log.i(TAG,"Network asset load complete: " + tuple);
        }
        else {
           Log.i(TAG,"Network asset load failed: " + tuple);
        }

        return in;
    }
}
