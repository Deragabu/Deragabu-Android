package com.limelight.computers;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Locale;
import java.util.Random;

import android.content.Context;
import android.util.Log;

public class IdentityManager {
    private final static String TAG = "IdentityManager";
    private static final String UNIQUE_ID_FILE_NAME = "uniqueid";
    private static final int UID_SIZE_IN_BYTES = 8;

    private String uniqueId;

    public IdentityManager(Context c) {
        uniqueId = loadUniqueId(c);
        if (uniqueId == null) {
            uniqueId = generateNewUniqueId(c);
        }
        Log.i(TAG, "UID is now: "+uniqueId);
    }

    public String getUniqueId() {
        return uniqueId;
    }

    private static String loadUniqueId(Context c) {
        // 2 Hex digits per byte
        char[] uid = new char[UID_SIZE_IN_BYTES * 2];
        Log.i(TAG, "Attempting to load UID from disk");
        try (final InputStreamReader reader =
                     new InputStreamReader(c.openFileInput(UNIQUE_ID_FILE_NAME))
        ) {
            if (reader.read(uid) != UID_SIZE_IN_BYTES * 2) {
                Log.w(TAG, "UID file data is truncated");
                return null;
            }
            return new String(uid);
        } catch (FileNotFoundException e) {
            Log.i(TAG, "UID file not found, will generate a new one");
            return null;
        } catch (IOException e) {
            Log.e(TAG, "Error while reading UID file",e);
            return null;
        }
    }

    private static String generateNewUniqueId(Context c) {
        // Generate a new UID hex string with 2 hex digits per byte, and a random long is 8 bytes, so 16 hex digits total
        @SuppressWarnings("DataFlowIssue") String uidStr = String.format((Locale)null, "%016x", new Random().nextLong());

        try (final OutputStreamWriter writer =
                     new OutputStreamWriter(c.openFileOutput(UNIQUE_ID_FILE_NAME, 0))
        ) {
            writer.write(uidStr);
        } catch (IOException e) {
            Log.e(TAG, "Error while writing UID file",e);
        }

        // We can return a UID even if I/O fails
        return uidStr;
    }
}
