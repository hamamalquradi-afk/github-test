// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.settings.preferences.restoreSettingsFiles
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

/** Calls the production preflight/apply entry point copied by the overlay. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRestoreValidationTest {
    private class Tracked(private val prefs: SharedPreferences) : SharedPreferences by prefs {
        var edits = 0
        override fun edit(): SharedPreferences.Editor { edits++; return prefs.edit() }
    }
    private lateinit var normal: Tracked
    private lateinit var protectedStore: Tracked
    private val normalFile = "preferences.json"
    private val protectedFile = "protected_preferences.json"

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        fun store(name: String): Tracked {
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            prefs.edit().clear().putString("oldString", "private-test-value")
                .putBoolean("oldBoolean", true).putInt("oldInt", 7).putLong("oldLong", 8L)
                .putFloat("oldFloat", 1.5f).putStringSet("oldSet", setOf("a", "b")).commit()
            return Tracked(prefs)
        }
        normal = store("k02-normal")
        protectedStore = store("k02-protected")
    }

    private fun valid() = listOf(
        "boolean settings", "{\"b\":true}",
        "int settings", "{\"i\":42}",
        "long settings", "{\"l\":2147483648}",
        "float settings", "{\"f\":1.25}",
        "string settings", "{\"s\":\"مرحبا\"}",
        "string set settings", "{\"ss\":[\"x\",\"y\"]}",
    )
    private fun rejected(files: Map<String, List<String>>) {
        val before = normal.all.toMap()
        val protectedBefore = protectedStore.all.toMap()
        var applying = false
        val error = assertFailsWith<IllegalArgumentException> {
            restoreSettingsFiles(files, normal, protectedStore) { applying = true }
        }
        assertTrue(error.message!!.startsWith("Invalid settings backup:"))
        assertFalse(error.message!!.contains("private-test-value"))
        assertFalse(applying)
        assertEquals(0, normal.edits)
        assertEquals(0, protectedStore.edits)
        assertEquals(before, normal.all)
        assertEquals(protectedBefore, protectedStore.all)
        before.forEach { (key, value) -> assertEquals(value!!::class, normal.all[key]!!::class) }
        protectedBefore.forEach { (key, value) -> assertEquals(value!!::class, protectedStore.all[key]!!::class) }
    }

    @Test fun validBackupRestoresBothStoresAndTypes() {
        restoreSettingsFiles(mapOf(normalFile to valid(), protectedFile to valid()), normal, protectedStore)
        val expected = mapOf("b" to true, "i" to 42, "l" to 2147483648L,
            "f" to 1.25f, "s" to "مرحبا", "ss" to setOf("x", "y"))
        for (store in listOf(normal, protectedStore)) {
            assertEquals(expected, store.all)
            expected.forEach { (key, value) -> assertEquals(value::class, store.all[key]!!::class) }
        }
    }
    @Test fun currentWriterRoundTrips() {
        val out = java.io.ByteArrayOutputStream()
        val writer = Class.forName("helium314.keyboard.settings.preferences.BackupRestorePreferenceKt")
            .getDeclaredMethod("settingsToJsonStream", Map::class.java, java.io.OutputStream::class.java)
        writer.isAccessible = true
        val original = normal.all.toMap()
        writer.invoke(null, original, out)
        restoreSettingsFiles(mapOf(normalFile to out.toString("UTF-8").split("\n")), normal, protectedStore)
        assertEquals(original, normal.all)
        assertEquals(0, protectedStore.edits)
    }
    @Test fun malformedNormalFileDoesNotEditEitherStore() {
        rejected(mapOf(normalFile to valid().toMutableList().apply { this[1] = "{" }))
    }
    @Test fun malformedProtectedFileDoesNotEditEitherStore() {
        rejected(mapOf(protectedFile to valid().toMutableList().apply { this[11] = "{" }))
    }
    @Test fun validFirstMalformedSecondNeverAppliesFirst() {
        rejected(linkedMapOf(normalFile to valid(), protectedFile to valid().toMutableList().apply { this[11] = "{" }))
        rejected(linkedMapOf(protectedFile to valid(), normalFile to valid().dropLast(1)))
    }
    @Test fun sectionWithoutJsonIsRejected() {
        rejected(mapOf(normalFile to valid().dropLast(1)))
        rejected(mapOf(normalFile to valid().toMutableList().apply { this[5] = "" }))
    }
    @Test fun wrongTypesAreRejectedInEverySection() {
        for ((index, bad) in listOf(1 to "{\"b\":\"true\"}", 3 to "{\"i\":1.5}",
            5 to "{\"l\":\"8\"}", 7 to "{\"f\":false}", 9 to "{\"s\":12}",
            11 to "{\"ss\":[\"ok\",5]}")) {
            rejected(mapOf(normalFile to valid().toMutableList().apply { this[index] = bad }))
        }
        for (bad in listOf("null", "[]", "{\"b\":null}")) {
            rejected(mapOf(normalFile to valid().toMutableList().apply { this[1] = bad }))
        }
    }
    @Test fun emptyUnknownIncompleteOrDuplicateSectionsAreRejected() {
        for (lines in listOf(emptyList(), listOf(""), listOf("unknown", "{}"), valid().drop(2),
            valid().toMutableList().apply { this[0] = "unknown" },
            valid().toMutableList().apply { this[2] = this[0] })) {
            rejected(mapOf(normalFile to lines))
        }
    }
}
