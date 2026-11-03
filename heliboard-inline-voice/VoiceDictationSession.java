/* SPDX-License-Identifier: GPL-3.0-only */
package helium314.keyboard.latin;

/** Main-thread, provider-independent session manager. Enabled is USER intent,
 * not a claim that the provider is capturing audio. No transcript is logged. */
final class VoiceDictationSession {
    enum State { STOPPED, RECONNECTING, LISTENING, SPEECH, ERROR }
    enum Failure { SILENCE, RECOVERABLE, THROTTLED, FATAL }
    interface Scheduler {
        void post(Runnable task, long delayMs);
        void remove(Runnable task);
    }
    interface Engine {
        void start();
        void close();
    }
    interface Factory { Engine create(Listener listener); }
    interface Host {
        boolean valid();
        boolean append(String phrase);
        void partial(String phrase);
        void state(State state);
        void rms(float level);
    }
    interface Listener {
        void ready();
        void speech();
        void rms(float level);
        void endpoint();
        void partial(String text);
        void result(String text);
        void error(Failure failure);
    }

    static final long READY_MS = 12000;
    static final long SESSION_MS = 45000;
    static final long PARTIAL_IDLE_MS = 5000;
    // A terminal callback normally follows onEndOfSpeech. If it never arrives,
    // commit the buffered partial and retire this generation, including late finals.
    static final long FINAL_MS = 2000;
    private final Scheduler scheduler;
    private final Factory factory;
    private final Host host;
    private Engine engine;
    private Runnable restart;
    private Runnable watchdog;
    private int generation;
    private int failures;
    private boolean enabled;
    private boolean paused;
    private boolean ended;
    private String hypothesis = "";
    private State state = State.STOPPED;

    VoiceDictationSession(Scheduler scheduler, Factory factory, Host host) {
        this.scheduler = scheduler;
        this.factory = factory;
        this.host = host;
    }

    boolean isEnabled() { return enabled; }
    State state() { return state; }

    void start() {
        if (enabled) return;
        enabled = true;
        paused = false;
        failures = 0;
        begin();
    }

    void stop() {
        if (!enabled) return;
        boolean safe = seal();
        enabled = false;
        paused = false;
        cancelRestart();
        retire();
        show(safe ? State.STOPPED : State.ERROR);
    }

    /** Editor/cursor change: discard only the uncommitted UI preview. */
    void abort() {
        hypothesis = "";
        host.partial("");
        enabled = false;
        paused = false;
        cancelRestart();
        retire();
        show(State.ERROR);
    }

    /** Release the microphone during a bounded IME view recreation. */
    void pause() {
        if (!enabled || paused) return;
        if (!seal()) { abort(); return; }
        paused = true;
        cancelRestart();
        retire();
        show(State.RECONNECTING);
    }

    void resume() {
        if (!enabled || !paused) return;
        paused = false;
        schedule(350);
    }

    private void begin() {
        if (!enabled || paused) return;
        if (!host.valid()) { abort(); return; }
        retire();
        final int token = generation;
        ended = false;
        hypothesis = "";
        show(State.RECONNECTING);
        try {
            engine = factory.create(new Listener() {
                private boolean current() {
                    return enabled && !paused && token == generation;
                }
                @Override public void ready() {
                    if (!current() || ended) return;
                    show(State.LISTENING);
                    arm(token, SESSION_MS);
                }
                @Override public void speech() {
                    if (!current() || ended) return;
                    show(State.SPEECH);
                    arm(token, SESSION_MS);
                }
                @Override public void rms(float level) {
                    if (current() && !ended
                            && (state == State.LISTENING || state == State.SPEECH)) host.rms(level);
                }
                @Override public void endpoint() {
                    if (!current() || ended) return;
                    ended = true;
                    show(State.RECONNECTING); // provider has stopped recording
                    arm(token, FINAL_MS);
                }
                @Override public void partial(String text) {
                    // An endpoint closes the hypothesis boundary. A provider
                    // that delivers another partial afterwards cannot turn it
                    // into a new utterance that erases the buffered phrase.
                    if (!current() || ended) return;
                    if (!host.valid()) { abort(); return; }
                    String value = clean(text);
                    if (value.isEmpty() || value.equals(hypothesis)) return;
                    hypothesis = value; // cumulative/revised hypotheses replace ONLY this buffer
                    host.partial(value);
                    if (!current()) return; // editor may have aborted an unsafe composition
                    show(State.SPEECH);
                    arm(token, PARTIAL_IDLE_MS);
                }
                @Override public void result(String text) {
                    if (!current()) return;
                    String value = clean(text);
                    if (!value.isEmpty()) hypothesis = value;
                    if (!seal()) { abort(); return; }
                    failures = 0;
                    retire();
                    schedule(350);
                }
                @Override public void error(Failure failure) {
                    if (current()) recover(failure);
                }
            });
            arm(token, READY_MS);
            engine.start();
        } catch (RuntimeException error) {
            // Adapter logs only the operation/error type, never speech or secrets.
            if (enabled && token == generation) recover(Failure.RECOVERABLE);
        }
    }

    private void recover(Failure failure) {
        if (!seal()) { abort(); return; }
        retire();
        if (failure == Failure.FATAL) {
            enabled = false;
            cancelRestart();
            show(State.ERROR);
        } else if (failure == Failure.SILENCE) {
            failures = 0;
            schedule(750);
        } else {
            failures = Math.min(failures + 1, 6);
            // Keep the user's mode across service disconnects, without a hot loop.
            schedule(failure == Failure.THROTTLED ? 60000
                    : Math.min(30000, 1000L << (failures - 1)));
        }
    }

    private boolean seal() {
        final String phrase = hypothesis;
        hypothesis = ""; // reentrant/duplicate terminal events cannot commit it again
        host.partial("");
        return phrase.isEmpty() || (host.valid() && host.append(phrase));
    }

    private void arm(int token, long delay) {
        cancelWatchdog();
        watchdog = () -> {
            watchdog = null;
            if (enabled && !paused && token == generation) recover(Failure.RECOVERABLE);
        };
        scheduler.post(watchdog, delay);
    }

    private void schedule(long delay) {
        if (!enabled || paused) return;
        cancelRestart();
        show(State.RECONNECTING);
        restart = () -> { restart = null; begin(); };
        scheduler.post(restart, delay);
    }

    private void retire() {
        ++generation; // invalidated BEFORE cancel/destroy, which can deliver callbacks
        cancelWatchdog();
        Engine old = engine;
        engine = null;
        if (old != null) old.close();
    }

    private void cancelWatchdog() {
        if (watchdog != null) scheduler.remove(watchdog);
        watchdog = null;
    }
    private void cancelRestart() {
        if (restart != null) scheduler.remove(restart);
        restart = null;
    }
    private void show(State value) { state = value; host.state(value); }
    private static String clean(String text) { return VoiceTextBuffer.cleanPhrase(text); }
}
