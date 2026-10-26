// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.util.SparseArray
import helium314.keyboard.keyboard.KeyboardActionListenerImpl
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.LatinIME
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.*

/** Exercise the production modifier transitions used by onFinishInputView and theme reloads. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], shadows = [ShadowInputMethodManager2::class, ShadowProximityInfo::class])
class ModifierLifecycleTest {
    private val listener = Robolectric.setupService(LatinIME::class.java).mKeyboardActionListener as KeyboardActionListenerImpl
    private fun event(name: String, key: Int) {
        KeyboardActionListenerImpl::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType).apply {
            isAccessible = true
        }.invoke(listener, key)
    }
    private fun mask() = KeyboardActionListenerImpl::class.java.getDeclaredField("metaState").apply {
        isAccessible = true
    }.getInt(listener)
    private fun latchCount() = (KeyboardActionListenerImpl::class.java.getDeclaredField("metaPressStates").apply {
        isAccessible = true
    }.get(listener) as SparseArray<*>).size()
    private fun tap(key: Int) {
        event("metaOnPressKey", key); event("metaAfterCodeInput", key); event("metaOnReleaseKey", key)
    }

    @Test fun firstModifierTapAfterInputFinishesStillWorks() {
        for (key in listOf(KeyCode.CTRL, KeyCode.ALT, KeyCode.FN, KeyCode.META, KeyCode.CTRL_LEFT, KeyCode.ALT_RIGHT)) {
            tap(key)
            assertNotEquals(0, mask())
            listener.resetMetaState()
            assertEquals(0, mask())
            assertEquals(0, latchCount(), "Stale latch after finishing input: $key")
            tap(key)
            assertNotEquals(0, mask(), "First modifier tap after reload was lost: $key")
            event("metaAfterCodeInput", 'a'.code)
            assertEquals(0, mask())
        }
    }

    @Test fun finishingWhileFingerIsDownDoesNotResurrectModifier() {
        event("metaOnPressKey", KeyCode.CTRL)
        listener.resetMetaState()
        event("metaOnReleaseKey", KeyCode.CTRL)
        assertEquals(0, mask())
        assertEquals(0, latchCount())
        tap(KeyCode.CTRL)
        assertNotEquals(0, mask())
    }

    @Test fun lockedModifiersAreAlsoClearedOnInputFinish() {
        for (key in listOf(KeyCode.CTRL_LOCK, KeyCode.ALT_LOCK, KeyCode.FN_LOCK, KeyCode.META_LOCK)) {
            tap(key)
            assertNotEquals(0, mask())
            listener.resetMetaState()
            assertEquals(0, mask())
            assertEquals(0, latchCount())
        }
    }

    @Test fun ordinaryTapAndDoubleTapSemanticsRemainUnchanged() {
        tap(KeyCode.CTRL)
        assertNotEquals(0, mask())
        tap(KeyCode.CTRL)
        assertEquals(0, mask())
        tap(KeyCode.CTRL)
        event("metaAfterCodeInput", 'a'.code)
        assertEquals(0, mask())
    }
}
