// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.themes

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.view.inputmethod.InputMethodManager
import android.view.KeyEvent

/** Private debug-only input target, used to tap the actual IME during emulator verification. */
class ThemeSmokeActivity : Activity() {
    lateinit var editor: RecordingEditor
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        editor = RecordingEditor(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            hint = "Gallery keyboard smoke test"
            setSingleLine(false)
            gravity = android.view.Gravity.TOP
        }
        setContentView(editor)
        editor.requestFocus()
        editor.postDelayed({ (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }, 500)
    }
}

class RecordingEditor(context: android.content.Context) : EditText(context) {
    val keyEvents = mutableListOf<Pair<Int, Int>>()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) keyEvents.add(event.keyCode to event.metaState)
        return super.dispatchKeyEvent(event)
    }
}
