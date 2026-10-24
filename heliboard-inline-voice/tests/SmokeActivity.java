// SPDX-License-Identifier: GPL-3.0-only
package org.heliboard.compact.smoke;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Separate emulator-only test app. Never included in the HeliBoard APK. */
public final class SmokeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        EditText plain = new EditText(this);
        plain.setContentDescription("plain-text-field");
        plain.setHint("Compact IME text test");
        plain.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        plain.setMinLines(3);
        root.addView(plain);
        EditText password = new EditText(this);
        password.setContentDescription("password-field");
        password.setHint("Password test");
        password.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        root.addView(password);
        setContentView(root);
        plain.requestFocus();
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
    }
}
