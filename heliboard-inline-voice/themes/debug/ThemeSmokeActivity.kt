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
    var imeAnimating = false
        private set
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        editor = RecordingEditor(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            // Landscape Android IMEs may enter fullscreen extract-edit mode.
            // That moves real key events away from this RecordingEditor and
            // makes shortcut comparisons depend on extract-view focus.
            // Test on the real host field in both orientations instead.
            imeOptions = android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
            id = android.R.id.edit
            // isShown becomes true before an IME slide has finished. Track the
            // actual animation so touch tests never use an intermediate origin.
            setWindowInsetsAnimationCallback(object : android.view.WindowInsetsAnimation.Callback(DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                private val animations = mutableSetOf<android.view.WindowInsetsAnimation>()
                override fun onPrepare(animation: android.view.WindowInsetsAnimation) {
                    if (animation.typeMask and android.view.WindowInsets.Type.ime() != 0) animations.add(animation)
                    imeAnimating = animations.isNotEmpty()
                }
                override fun onProgress(insets: android.view.WindowInsets, runningAnimations: MutableList<android.view.WindowInsetsAnimation>): android.view.WindowInsets = insets
                override fun onEnd(animation: android.view.WindowInsetsAnimation) {
                    animations.remove(animation)
                    imeAnimating = animations.isNotEmpty()
                }
            })
            hint = "Gallery keyboard smoke test"
            setSingleLine(false)
            gravity = android.view.Gravity.TOP
        }
        setContentView(editor)
        editor.requestFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::editor.isInitialized) {
            editor.requestFocus()
            editor.post {
                if (editor.hasWindowFocus())
                    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                        .showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }
}

class RecordingEditor(context: android.content.Context) : EditText(context) {
    val keyEvents = mutableListOf<Pair<Int, Int>>()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) keyEvents.add(event.keyCode to event.metaState)
        return super.dispatchKeyEvent(event)
    }
}
