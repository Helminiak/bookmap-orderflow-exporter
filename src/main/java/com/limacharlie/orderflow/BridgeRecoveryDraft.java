package com.limacharlie.orderflow;

/**
 * EDT-confined, draft-only settings selection for optional Linux bridge enablement.
 *
 * <p>This class captures an <em>unsaved</em> user preference in the Bookmap GUI. It carries
 * <strong>no</strong> runtime status, <strong>no</strong> persistence, <strong>no</strong> network
 * effect, and <strong>no</strong> Apply/commit semantics. The initial value is immutable for the
 * lifetime of the draft; the current selection is freely reversible until the operator explicitly
 * applies it through a separate (out-of-scope) mechanism.
 *
 * <p>Intended use: a single Swing EDT thread reads and writes this object. No synchronization is
 * provided because the contract is single-thread (EDT) access only.
 *
 * <p>Dependencies: none beyond {@code java.lang}.
 */
final class BridgeRecoveryDraft {

    /** Immutable baseline captured at construction. */
    private final boolean initial;

    /** Current draft selection; freely mutable, reversible. */
    private boolean current;

    /**
     * Creates a draft whose initial and current bridge state are both {@code enabled}.
     *
     * @param enabled the immutable initial bridge-enablement preference
     */
    BridgeRecoveryDraft(boolean enabled) {
        this.initial = enabled;
        this.current = enabled;
    }

    /** Returns the immutable initial bridge-enablement value. Never changes after construction. */
    boolean initialBridgeEnabled() {
        return initial;
    }

    /** Returns the current (possibly unsaved) draft bridge-enablement value. */
    boolean bridgeEnabled() {
        return current;
    }

    /**
     * Returns {@code true} when the current selection differs from the immutable initial value,
     * i.e. the operator has made a pending change that has not been reverted.
     */
    boolean pending() {
        return current != initial;
    }

    /** Selects the archive-only (bridge-disabled) option. Pure draft mutation; no side effects. */
    void selectArchiveOnly() {
        this.current = false;
    }

    /**
     * Sets the current draft bridge-enablement to the given value. Pure draft mutation; no side
     * effects.
     *
     * @param enabled desired bridge-enablement for the draft
     */
    void setBridgeEnabled(boolean enabled) {
        this.current = enabled;
    }
}
