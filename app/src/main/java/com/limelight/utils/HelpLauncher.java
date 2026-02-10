package com.limelight.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import com.limelight.HelpActivity;

public class HelpLauncher {
    public static void launchUrl(Context context, String url) {
        // Try to launch the default browser
        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(url));
            context.startActivity(i);
            return;
        } catch (Exception e) {
            // This is only supposed to throw ActivityNotFoundException but
            // it can (at least) also throw SecurityException if a user's default
            // browser is not exported. We'll catch everything to workaround this.

            // Fall through
        }

        // This platform has no browser, use our WebView activity
        Intent i = new Intent(context, HelpActivity.class);
        i.setData(Uri.parse(url));
        context.startActivity(i);
    }

    public static void launchSetupGuide(Context context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/Setup-Guide");
    }

    public static void launchTroubleshooting(Context context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/Troubleshooting");
    }

    public static void launchGameStreamEolFaq(Context context) {
        launchUrl(context, "https://github.com/moonlight-stream/moonlight-docs/wiki/NVIDIA-GameStream-End-Of-Service-Announcement-FAQ");
    }
}
