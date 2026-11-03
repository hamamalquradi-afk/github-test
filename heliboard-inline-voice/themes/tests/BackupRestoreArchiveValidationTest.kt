// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard

import android.content.Context
import android.content.SharedPreferences
import helium314.keyboard.settings.preferences.restoreSettingsFiles
import helium314.keyboard.settings.preferences.withValidatedRestoreArchive
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackupRestoreArchiveValidationTest {
    private class Tracked(private val prefs: SharedPreferences) : SharedPreferences by prefs {
        var edits = 0
        override fun edit(): SharedPreferences.Editor { edits++; return prefs.edit() }
    }
    private lateinit var root: File
    private lateinit var files: File
    private lateinit var protectedFiles: File
    private lateinit var cache: File
    private lateinit var normal: Tracked
    private lateinit var protectedPrefs: Tracked

    @Before fun setup() {
        root = Files.createTempDirectory("keynova-k01-test-").toFile()
        files = File(root, "files").apply { mkdir() }
        protectedFiles = File(root, "protected").apply { mkdir() }
        cache = File(root, "cache").apply { mkdir() }
        File(files, "custom_font").writeText("original font")
        File(protectedFiles, "custom_background_image").writeText("original image")
        File(cache, "unrelated").writeText("keep")
        fun store(name: String): Tracked {
            val p = RuntimeEnvironment.getApplication().getSharedPreferences(name, Context.MODE_PRIVATE)
            p.edit().clear().putBoolean("b", true).putInt("i", 3).putLong("l", 4L)
                .putFloat("f", 1.5f).putString("s", "temporary test value")
                .putStringSet("set", setOf("a", "b")).commit()
            return Tracked(p)
        }
        normal = store("archive-normal")
        protectedPrefs = store("archive-protected")
    }
    @After fun cleanup() { root.deleteRecursively() }

    private fun backupSettings(prefs: SharedPreferences): ByteArray {
        val out = ByteArrayOutputStream()
        Class.forName("helium314.keyboard.settings.preferences.BackupRestorePreferenceKt")
            .getDeclaredMethod("settingsToJsonStream", Map::class.java, java.io.OutputStream::class.java)
            .apply { isAccessible = true }.invoke(null, prefs.all, out)
        return out.toByteArray()
    }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name)); z.write(bytes); z.closeEntry()
            }
        }
        return out.toByteArray()
    }
    private fun tree(): Map<String, String> = root.walkTopDown().associate { f ->
        f.relativeTo(root).path to if (f.isDirectory) "directory" else
            MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    }
    private fun rejected(bytes: ByteArray) {
        val before = tree()
        val prefsBefore = listOf(normal.all.toMap(), protectedPrefs.all.toMap())
        var applied = false
        val error = assertFailsWith<IllegalArgumentException> {
            withValidatedRestoreArchive(ByteArrayInputStream(bytes), cache, files, protectedFiles) {
                applied = true
                // Reaching the production mutation callback on rejection is a failure.
                files.renameTo(File(root, "files2"))
                normal.edit().clear().commit()
            }
        }
        assertEquals("Invalid or unusable backup archive", error.message)
        assertFalse(applied)
        assertEquals(before, tree(), "Original files, directory structure and hashes changed")
        for ((index, prefs) in listOf(normal, protectedPrefs).withIndex()) {
            assertEquals(0, prefs.edits)
            assertEquals(prefsBefore[index], prefs.all)
            prefsBefore[index].forEach { (key, value) ->
                val actual = prefs.all[key]
                if (value is Set<*>) assertTrue(actual is Set<*>)
                else assertEquals(value!!::class, actual!!::class)
            }
        }
    }
    private fun restore(bytes: ByteArray) {
        val before = tree()
        var applied = false
        withValidatedRestoreArchive(ByteArrayInputStream(bytes), cache, files, protectedFiles) { snapshot ->
            applied = true
            assertContentEquals(bytes, snapshot.readBytes())
            val settings = linkedMapOf<String, List<String>>()
            ZipInputStream(snapshot.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name in listOf("preferences.json", "protected_preferences.json"))
                        settings[e.name] = String(z.readBytes(), Charsets.UTF_8).split("\n")
                    z.closeEntry(); e = z.nextEntry
                }
            }
            restoreSettingsFiles(settings, normal, protectedPrefs)
        }
        assertTrue(applied)
        assertEquals(before, tree(), "Temporary snapshot was not cleaned up")
    }
    @Test fun emptyZipIsRejectedWithoutMutation() { rejected(zip()) }
    @Test fun ignoredProtectedEntryAloneIsRejected() {
        rejected(zip("unprotected/README.txt" to "ignored".toByteArray()))
    }
    @Test fun directoriesAloneAreRejected() {
        rejected(zip("unprotected/" to byteArrayOf(), "layouts/" to byteArrayOf()))
    }
    @Test fun unsafeAndRejectedEntriesCannotAuthorizeRestore() {
        rejected(zip("unprotected/../custom_font" to "unsafe".toByteArray()))
        rejected(zip("/custom_font" to "unsafe".toByteArray()))
        rejected(zip("README.txt" to "unknown".toByteArray()))
        rejected(zip("custom_font" to byteArrayOf()))
    }
    @Test fun corruptAndTruncatedZipAreRejected() {
        rejected("not a zip".toByteArray())
        val good = zip("preferences.json" to backupSettings(normal))
        rejected(good.copyOf(good.size - 12))
        val badCrc = good.copyOf()
        val offset = 30 + "preferences.json".length + 2
        badCrc[offset] = (badCrc[offset].toInt() xor 0x40).toByte()
        rejected(badCrc)
    }
    @Test fun corruptSettingsKeepK02Protection() {
        rejected(zip("preferences.json" to backupSettings(normal),
            "protected_preferences.json" to "boolean settings\n{".toByteArray()))
        rejected(zip("preferences.json" to "invalid".toByteArray(), "custom_font" to "font".toByteArray()))
    }
    @Test fun currentWriterSettingsRestoreWithoutDatabaseOrImages() {
        val bytes = zip("preferences.json" to backupSettings(normal),
            "protected_preferences.json" to backupSettings(protectedPrefs))
        restore(bytes)
        assertEquals(1, normal.edits)
        assertEquals(1, protectedPrefs.edits)
    }
    @Test fun unknownSafeEntriesAreIgnoredInUsableBackup() {
        restore(zip("unprotected/" to byteArrayOf(), "unprotected/README.txt" to "ignored".toByteArray(),
            "preferences.json" to backupSettings(normal)))
        assertEquals(1, normal.edits)
        assertEquals(0, protectedPrefs.edits)
    }
    @Test fun allowedUserFileWithoutDatabaseOrSettingsIsRestored() {
        val bytes = zip("custom_font" to "restored test font".toByteArray())
        withValidatedRestoreArchive(ByteArrayInputStream(bytes), cache, files, protectedFiles) { snapshot ->
            ZipInputStream(snapshot.inputStream()).use { stream ->
                val entry = stream.nextEntry
                val method = Class.forName("helium314.keyboard.settings.preferences.BackupRestorePreferenceKt")
                    .getDeclaredMethod("restoreEntryToDir", ZipInputStream::class.java, File::class.java, String::class.java)
                    .apply { isAccessible = true }
                assertEquals(true, method.invoke(null, stream, files, entry.name))
                stream.closeEntry()
            }
        }
        assertEquals("restored test font", File(files, "custom_font").readText())
        assertEquals(0, normal.edits)
        assertEquals(0, protectedPrefs.edits)
        assertEquals(listOf("unrelated"), cache.list()!!.toList())
    }
    @Test fun snapshotDoesNotReopenChangedSourceAndCleansUpOnApplyFailure() {
        val source = File(root, "source.zip")
        val original = zip("preferences.json" to backupSettings(normal))
        source.writeBytes(original)
        source.inputStream().use { input ->
            assertFailsWith<IllegalStateException> {
                withValidatedRestoreArchive(input, cache, files, protectedFiles) { snapshot ->
                    source.writeText("source changed")
                    assertContentEquals(original, snapshot.readBytes())
                    error("simulated apply failure")
                }
            }
        }
        assertEquals(listOf("unrelated"), cache.list()!!.toList())
    }
}
