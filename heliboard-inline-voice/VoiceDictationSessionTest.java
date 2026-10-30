/* SPDX-License-Identifier: GPL-3.0-only */
package helium314.keyboard.latin;

import org.junit.Before;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Deterministic tests of the SHIPPED manager, including provider callbacks and
 * real elapsed silence. These do not claim microphone hardware acceptance. */
public class VoiceDictationSessionTest {
    private Clock clock;
    private Provider provider;
    private Host host;
    private VoiceDictationSession voice;

    @Before public void setup() {
        clock = new Clock(); provider = new Provider(); host = new Host();
        voice = new VoiceDictationSession(clock, provider, host);
        voice.start();
    }
    private VoiceDictationSession.Listener listener() { return provider.listeners.get(provider.listeners.size() - 1); }

    @Test public void silenceOneSecond() { quiet(1000); }
    @Test public void silenceThreeSeconds() { quiet(3000); }
    @Test public void silenceFiveSeconds() { quiet(5000); }
    @Test public void silenceTenSeconds() { quiet(10000); }
    @Test public void silenceThirtySeconds() { quiet(30000); }
    private void quiet(long duration) {
        listener().ready(); clock.advance(duration);
        assertTrue(voice.isEnabled()); assertEquals(1, provider.listeners.size());
        assertEquals(VoiceDictationSession.State.LISTENING, voice.state());
        assertEquals("", host.text.toString());
    }

    @Test public void silenceSixtySecondsRecyclesServiceButKeepsIntent() {
        listener().ready(); clock.advance(60000);
        assertTrue(voice.isEnabled());
        assertTrue(provider.listeners.size() > 1);
        assertEquals("", host.text.toString());
        listener().partial("after one minute"); listener().result("after one minute");
        assertEquals("after one minute ", host.text.toString());
    }

    @Test public void fiveSpeechPauseResumeCyclesPreserveEarlierFinals() {
        StringBuilder expected = new StringBuilder();
        long[] gaps = {3000, 10000, 30000, 60000, 3000};
        for (int i = 0; i < gaps.length; i++) {
            advanceProviderSilence(gaps[i]);
            assertTrue("user dictation intent lost after silence " + gaps[i], voice.isEnabled());
            assertEquals("recognizer not active before phrase " + i, 1, provider.active);
            listener().ready();
            assertEquals(VoiceDictationSession.State.LISTENING, voice.state());
            phrase("phrase" + i);
            expected.append("phrase").append(i).append(' ');
            assertEquals("previous phrase lost or duplicated at cycle " + i,
                    expected.toString(), host.text.toString());
            assertEquals(i + 1, host.commits);
        }
        assertEquals(5, host.commits);
        assertTrue(provider.maximumActive <= 1);
        assertTrue("60-second silence never exercised recognizer renewal",
                provider.listeners.size() > gaps.length);
    }

    /** A provider may endpoint after silence. A new recognizer cannot emit
     * speech until its start() has actually run; never deliver callbacks to
     * an old listener while the manager's restart/backoff timer is pending. */
    private void advanceProviderSilence(long millis) {
        long remaining = millis;
        while (remaining > 0) {
            assertEquals("speech delivered before recognizer start", 1, provider.active);
            listener().ready();
            long listening = Math.min(3000, remaining);
            clock.advance(listening);
            remaining -= listening;
            if (remaining > 0) {
                listener().error(VoiceDictationSession.Failure.SILENCE);
                assertEquals(VoiceDictationSession.State.RECONNECTING, voice.state());
                long restartDelay = Math.min(750, remaining);
                clock.advance(restartDelay);
                remaining -= restartDelay;
            }
            assertTrue(voice.isEnabled());
        }
    }

    @Test public void providerSilenceEndpointsDoNotDisableModeForThirtySeconds() {
        for (int i = 0; i < 30; i++) {
            listener().ready(); listener().error(VoiceDictationSession.Failure.SILENCE);
            assertEquals(VoiceDictationSession.State.RECONNECTING, voice.state());
            clock.advance(1000); assertTrue(voice.isEnabled());
        }
        assertEquals("", host.text.toString()); assertEquals(1, provider.active);
    }

    @Test public void ArabicSpeakPauseSpeakAppendsThreeImmutablePhrases() {
        phrase("مرحبا كيف حالك");
        clock.advance(30000); // no ready callback: watchdog reconnects, not user-off
        assertTrue(voice.isEnabled());
        phrase("اليوم سوف نجرب الميكروفون"); phrase("ثم نكتب English بالعربية");
        assertEquals("مرحبا كيف حالك اليوم سوف نجرب الميكروفون ثم نكتب English بالعربية ", host.text.toString());
        assertEquals(3, host.commits);
    }

    private void phrase(String value) {
        listener().ready(); listener().speech(); listener().partial(value);
        listener().endpoint(); listener().result(value); clock.advance(350);
    }

    @Test public void partialsRemainOnlyInUiUntilFinalization() {
        listener().partial("I can"); listener().partial("I can right");
        assertEquals("", host.text.toString()); assertEquals("I can right", host.preview);
        listener().result("I can write");
        assertEquals("I can write ", host.text.toString()); assertEquals(1, host.commits);
    }

    @Test public void repeatedCumulativeHypothesesCommitOnce() {
        listener().partial("hello"); listener().partial("hello"); listener().partial("hello world");
        listener().partial("hello world"); listener().result("hello world"); listener().result("hello world");
        assertEquals("hello world ", host.text.toString()); assertEquals(1, host.commits);
    }

    @Test public void partialWithoutFinalIsSealedByEndpointWatchdog() {
        listener().partial("مرحبا"); listener().endpoint();
        clock.advance(VoiceDictationSession.FINAL_MS);
        assertEquals("مرحبا ", host.text.toString());
        assertTrue(voice.isEnabled()); assertEquals(VoiceDictationSession.State.RECONNECTING, voice.state());
        clock.advance(1000); phrase("كيف حالك");
        assertEquals("مرحبا كيف حالك ", host.text.toString());
    }

    @Test public void latePartialsCannotPostponeEndpointFinalization() {
        listener().partial("first"); listener().endpoint();
        for (int i = 0; i < 10; i++) { listener().partial("first phrase"); clock.advance(200); }
        assertEquals("first ", host.text.toString());
    }

    @Test public void repeatedEndpointDoesNotDelayWatchdog() {
        listener().partial("hello"); listener().endpoint(); clock.advance(1500);
        listener().endpoint(); clock.advance(500);
        assertEquals("hello ", host.text.toString());
    }

    @Test public void lateFinalCannotRewriteAlreadySealedUtterance() {
        VoiceDictationSession.Listener old = listener();
        old.partial("old phrase"); old.endpoint(); clock.advance(3000);
        listener().partial("new"); old.result("old phrase revised"); listener().result("new phrase");
        assertEquals("old phrase new phrase ", host.text.toString());
    }

    @Test public void timeoutAndNoMatchSealPartialsAndRestart() {
        for (String text : new String[]{"one", "اثنان", "three"}) {
            listener().partial(text); listener().error(VoiceDictationSession.Failure.SILENCE);
            clock.advance(750);
        }
        assertEquals("one اثنان three ", host.text.toString()); assertTrue(voice.isEnabled());
    }

    @Test public void engineDisconnectsBackOffWithoutLosingUserIntent() {
        long[] delays = {1000, 2000, 4000, 8000, 16000, 30000, 30000};
        for (long delay : delays) {
            listener().error(VoiceDictationSession.Failure.RECOVERABLE);
            int count = provider.listeners.size(); clock.advance(delay - 1);
            assertEquals(count, provider.listeners.size()); clock.advance(1);
            assertTrue(voice.isEnabled()); assertEquals(count + 1, provider.listeners.size());
        }
        phrase("recovered"); assertEquals("recovered ", host.text.toString());
    }

    @Test public void rateLimitUsesLongCooldownNotRestartLoop() {
        listener().error(VoiceDictationSession.Failure.THROTTLED);
        clock.advance(59999); assertEquals(1, provider.listeners.size());
        assertTrue(voice.isEnabled()); assertEquals(0, provider.active);
        clock.advance(1); assertEquals(2, provider.listeners.size());
    }

    @Test public void readyAndQuietWatchdogsNeverTurnUserModeOff() {
        long[] delays = {1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000};
        for (int i = 0; i < 8; i++) {
            listener().ready(); listener().partial("phrase" + i);
            clock.advance(VoiceDictationSession.PARTIAL_IDLE_MS);
            assertTrue(voice.isEnabled());
            clock.advance(delays[i]);
        }
        assertEquals(8, host.commits); assertTrue(provider.maximumActive <= 1);
    }

    @Test public void manualStopDuringSpeechCommitsOnceAndReleasesEngine() {
        VoiceDictationSession.Listener old = listener(); old.partial("hello"); voice.stop();
        old.result("hello world"); old.error(VoiceDictationSession.Failure.SILENCE); clock.advance(120000);
        assertEquals("hello ", host.text.toString()); assertFalse(voice.isEnabled());
        assertEquals(0, provider.active); assertEquals(VoiceDictationSession.State.STOPPED, voice.state());
    }

    @Test public void manualStopDuringSilenceAddsNoSpaceAndCancelsRestart() {
        listener().error(VoiceDictationSession.Failure.SILENCE); voice.stop(); clock.advance(120000);
        assertEquals("", host.text.toString()); assertEquals(1, provider.listeners.size());
    }

    @Test public void emptyFinalKeepsLastNonemptyPartial() {
        listener().partial("hello"); listener().partial(""); listener().result("  ");
        assertEquals("hello ", host.text.toString());
    }

    @Test public void fatalErrorPreservesPhraseButShowsErrorAndStops() {
        listener().partial("saved"); listener().error(VoiceDictationSession.Failure.FATAL);
        assertEquals("saved ", host.text.toString()); assertFalse(voice.isEnabled());
        assertEquals(VoiceDictationSession.State.ERROR, voice.state()); clock.advance(120000);
        assertEquals(1, provider.listeners.size());
    }

    @Test public void cursorOrEditorChangeDiscardsOnlyPendingUiText() {
        phrase("committed"); listener().partial("temporary"); host.valid = false;
        listener().result("must not appear");
        assertEquals("committed ", host.text.toString()); assertEquals("", host.preview);
        assertFalse(voice.isEnabled()); assertEquals(0, provider.active);
    }

    @Test public void failedEditorCommitIsNeverRetried() {
        host.accept = false; listener().partial("hello"); listener().result("hello");
        voice.stop(); clock.advance(120000); assertEquals(1, host.commits); assertFalse(voice.isEnabled());
    }

    @Test public void viewRecreationKeepsIntentButReleasesAudioUntilResumed() {
        listener().partial("before rotation"); voice.pause(); clock.advance(30000);
        assertTrue(voice.isEnabled()); assertEquals(0, provider.active);
        voice.resume(); clock.advance(350); phrase("after rotation");
        assertEquals("before rotation after rotation ", host.text.toString());
    }

    @Test public void hideShutdownAndReopenRequireNewExplicitStart() {
        phrase("first"); voice.stop(); clock.advance(120000); assertFalse(voice.isEnabled());
        voice.start(); phrase("second"); assertEquals("first second ", host.text.toString());
    }

    @Test public void rmsIsIgnoredAfterEndpointAndFromRetiredGenerations() {
        listener().ready(); listener().rms(4); assertEquals(1, host.levels);
        listener().endpoint(); listener().rms(9); listener().result(""); listener().rms(9);
        assertEquals(1, host.levels); assertEquals(VoiceDictationSession.State.RECONNECTING, voice.state());
    }

    @Test public void concurrentStartsAndStaleListenersCannotCreateConcurrentRecognizers() {
        voice.start(); voice.start(); VoiceDictationSession.Listener old = listener();
        old.result("one"); old.error(VoiceDictationSession.Failure.RECOVERABLE);
        clock.advance(350); old.ready(); old.partial("stale"); phrase("two");
        assertEquals("one two ", host.text.toString()); assertEquals(1, provider.maximumActive);
    }

    @Test public void factoryFailureIsRecoverableAndBounded() {
        voice.stop(); provider.throwNext = true; voice.start();
        assertTrue(voice.isEnabled()); assertEquals(VoiceDictationSession.State.RECONNECTING, voice.state());
        clock.advance(1000); listener().ready(); assertEquals(VoiceDictationSession.State.LISTENING, voice.state());
    }

    @Test public void repeatedPartialAndRmsCannotKeepAnUnresponsiveSessionAlive() {
        listener().partial("saved once");
        for (int i = 0; i < 50; i++) {
            listener().partial("saved once"); listener().rms(5); clock.advance(100);
        }
        assertEquals("saved once ", host.text.toString());
        assertTrue(voice.isEnabled()); assertEquals(VoiceDictationSession.State.RECONNECTING, voice.state());
    }

    private static final class Host implements VoiceDictationSession.Host {
        final StringBuilder text = new StringBuilder();
        String preview = ""; int commits; int levels; boolean valid = true; boolean accept = true;
        @Override public boolean valid() { return valid; }
        @Override public boolean append(String phrase) { commits++; if (accept) text.append(phrase.trim()).append(' '); return accept; }
        @Override public void partial(String phrase) { preview = phrase; }
        @Override public void state(VoiceDictationSession.State state) { }
        @Override public void rms(float level) { levels++; }
    }
    private static final class Provider implements VoiceDictationSession.Factory {
        final List<VoiceDictationSession.Listener> listeners = new ArrayList<>();
        int active; int maximumActive; boolean throwNext;
        @Override public VoiceDictationSession.Engine create(VoiceDictationSession.Listener listener) {
            if (throwNext) { throwNext = false; throw new IllegalStateException("service disconnected"); }
            listeners.add(listener);
            return new VoiceDictationSession.Engine() {
                boolean open;
                @Override public void start() { open = true; active++; maximumActive = Math.max(maximumActive, active); }
                @Override public void close() { if (open) active--; open = false; }
            };
        }
    }
    private static final class Clock implements VoiceDictationSession.Scheduler {
        final List<Task> tasks = new ArrayList<>(); long now;
        @Override public void post(Runnable action, long delay) { tasks.add(new Task(action, now + delay)); }
        @Override public void remove(Runnable action) { tasks.removeIf(task -> task.action == action); }
        void advance(long amount) {
            long end = now + amount;
            while (true) {
                Task next = null;
                for (Task task : tasks) if (task.at <= end && (next == null || task.at < next.at)) next = task;
                if (next == null) break;
                tasks.remove(next); now = next.at; next.action.run();
            }
            now = end;
        }
        private static final class Task {
            final Runnable action; final long at;
            Task(Runnable action, long at) { this.action = action; this.at = at; }
        }
    }
}
