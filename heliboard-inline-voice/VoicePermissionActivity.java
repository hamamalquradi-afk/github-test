/*
 * One-time Android permission request for in-keyboard speech recognition.
 * SPDX-License-Identifier: GPL-3.0-only
 */
package helium314.keyboard.latin;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/** Permission prompts must be shown by an Activity, not an InputMethodService. */
public final class VoicePermissionActivity extends Activity {
    private static final int MICROPHONE_REQUEST = 1001;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
            finish();
            return;
        }
        requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO },
                MICROPHONE_REQUEST);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == MICROPHONE_REQUEST) {
            if (grantResults.length == 0
                    || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "لم يتم السماح باستخدام الميكروفون",
                        Toast.LENGTH_SHORT).show();
            }
            finish();
        }
    }
}
