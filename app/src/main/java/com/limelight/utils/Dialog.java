package com.limelight.utils;

import java.util.ArrayList;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import com.limelight.R;

public class Dialog implements Runnable {
    private final String title;
    private final String message;
    private final Activity activity;
    private final Runnable runOnDismiss;

    private AlertDialog alert;

    private static final ArrayList<Dialog> rundownDialogs = new ArrayList<>();

    private Dialog(Activity activity, String title, String message, Runnable runOnDismiss)
    {
        this.activity = activity;
        this.title = title;
        this.message = message;
        this.runOnDismiss = runOnDismiss;
    }

    public static void closeDialogs()
    {
        synchronized (rundownDialogs) {
            for (Dialog d : rundownDialogs) {
                if (d.alert.isShowing()) {
                    d.alert.dismiss();
                }
            }

            rundownDialogs.clear();
        }
    }

    public static void displayDialog(final Activity activity, String title, String message, final boolean endAfterDismiss)
    {
        activity.runOnUiThread(new Dialog(activity, title, message, () -> {
            if (endAfterDismiss) {
                activity.finish();
            }
        }));
    }

    public static void displayDialog(Activity activity, String title, String message, Runnable runOnDismiss)
    {
        activity.runOnUiThread(new Dialog(activity, title, message, runOnDismiss));
    }

    public static void displayPairingDialog(final Activity activity, String title, String message, final String pin, Runnable runOnDismiss)
    {
        activity.runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (activity.isFinishing())
                    return;

                AlertDialog alert = new AlertDialog.Builder(activity).create();

                alert.setTitle(title);
                alert.setMessage(message);
                alert.setCancelable(false);
                alert.setCanceledOnTouchOutside(false);

                alert.setButton(AlertDialog.BUTTON_POSITIVE, activity.getResources().getText(android.R.string.ok), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        synchronized (rundownDialogs) {
                            dialog.dismiss();
                        }
                        if (runOnDismiss != null) {
                            runOnDismiss.run();
                        }
                    }
                });

                alert.setButton(AlertDialog.BUTTON_NEGATIVE, activity.getResources().getText(android.R.string.copy), new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("PIN", pin);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(activity, R.string.pair_pin_copied, Toast.LENGTH_SHORT).show();
                        // Don't dismiss - let user continue to see the dialog
                    }
                });

                alert.setButton(AlertDialog.BUTTON_NEUTRAL, activity.getResources().getText(R.string.help), (dialog, which) -> {
                    synchronized (rundownDialogs) {
                        dialog.dismiss();
                    }
                    if (runOnDismiss != null) {
                        runOnDismiss.run();
                    }
                    HelpLauncher.launchTroubleshooting(activity);
                });

                alert.setOnShowListener(dialog -> {
                    // Prevent the copy button from dismissing the dialog
                    Button copyButton = alert.getButton(AlertDialog.BUTTON_NEGATIVE);
                    copyButton.setOnClickListener(v -> {
                        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("PIN", pin);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(activity, R.string.pair_pin_copied, Toast.LENGTH_SHORT).show();
                    });

                    Button button = alert.getButton(AlertDialog.BUTTON_POSITIVE);
                    button.setFocusable(true);
                    button.setFocusableInTouchMode(true);
                    button.requestFocus();
                });

                synchronized (rundownDialogs) {
                    // We need to add a wrapper Dialog object for closeDialogs() to work
                    Dialog wrapper = new Dialog(activity, title, message, runOnDismiss);
                    wrapper.alert = alert;
                    rundownDialogs.add(wrapper);
                    alert.show();
                }
            }
        });
    }

    public interface AddComputerCallback {
        void onAddComputer(String host);
    }

    public static void displayAddComputerDialog(final Activity activity, final AddComputerCallback callback) {
        activity.runOnUiThread(() -> {
            if (activity.isFinishing())
                return;

            final EditText input = new EditText(activity);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            input.setHint(R.string.ip_hint);
            input.setSingleLine(true);
            input.setImeOptions(EditorInfo.IME_ACTION_DONE);

            // Add padding to the EditText
            int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
            input.setPadding(padding, padding, padding, padding);

            AlertDialog alert = new AlertDialog.Builder(activity)
                    .setTitle(R.string.title_add_pc)
                    .setView(input)
                    .setCancelable(true)
                    .setPositiveButton(android.R.string.ok, null) // Set to null, we'll override below
                    .setNegativeButton(android.R.string.cancel, (dialog, which) -> dialog.dismiss())
                    .create();

            alert.setOnShowListener(dialog -> {
                Button okButton = alert.getButton(AlertDialog.BUTTON_POSITIVE);
                okButton.setOnClickListener(v -> {
                    String hostAddress = input.getText().toString().trim();
                    if (hostAddress.isEmpty()) {
                        Toast.makeText(activity, R.string.addpc_enter_ip, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    alert.dismiss();
                    if (callback != null) {
                        callback.onAddComputer(hostAddress);
                    }
                });

                // Handle IME action
                input.setOnEditorActionListener((v, actionId, event) -> {
                    if (actionId == EditorInfo.IME_ACTION_DONE) {
                        okButton.performClick();
                        return true;
                    }
                    return false;
                });

                // Focus on the input and show keyboard
                input.requestFocus();
            });

            synchronized (rundownDialogs) {
                Dialog wrapper = new Dialog(activity, activity.getString(R.string.title_add_pc), "", null);
                wrapper.alert = alert;
                rundownDialogs.add(wrapper);
                alert.show();
            }
        });
    }

    @Override
    public void run() {
        // If we're dying, don't bother creating a dialog
        if (activity.isFinishing())
            return;

        alert = new AlertDialog.Builder(activity).create();

        alert.setTitle(title);
        alert.setMessage(message);
        alert.setCancelable(false);
        alert.setCanceledOnTouchOutside(false);
 
        alert.setButton(AlertDialog.BUTTON_POSITIVE, activity.getResources().getText(android.R.string.ok), (dialog, which) -> {
            synchronized (rundownDialogs) {
                rundownDialogs.remove(Dialog.this);
                alert.dismiss();
            }

            runOnDismiss.run();
        });
        alert.setOnShowListener(dialog -> {
            // Set focus to the OK button by default
            Button button = alert.getButton(AlertDialog.BUTTON_POSITIVE);
            button.setFocusable(true);
            button.setFocusableInTouchMode(true);
            button.requestFocus();
        });

        synchronized (rundownDialogs) {
            rundownDialogs.add(this);
            alert.show();
        }
    }

}
