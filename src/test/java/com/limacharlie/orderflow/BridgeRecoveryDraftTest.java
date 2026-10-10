package com.limacharlie.orderflow;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Behavioural tests for {@link BridgeRecoveryDraft}.
 *
 * <p>Covers initial-state immutability, pending semantics, reversibility, idempotency, and
 * isolation between independent draft instances. No reflection, no runtime integration, no I/O.
 */
final class BridgeRecoveryDraftTest {

    @Test
    void enabledInitialState_reportsTrueAndNoPending() {
        BridgeRecoveryDraft draft = new BridgeRecoveryDraft(true);
        assertTrue(draft.initialBridgeEnabled());
        assertTrue(draft.bridgeEnabled());
        assertFalse(draft.pending());
    }

    @Test
    void disabledInitialState_reportsFalseAndNoPending() {
        BridgeRecoveryDraft draft = new BridgeRecoveryDraft(false);
        assertFalse(draft.initialBridgeEnabled());
        assertFalse(draft.bridgeEnabled());
        assertFalse(draft.pending());
    }

    @Test
    void disablingFromEnabled_createsPendingWhileBaselineRemainsTrue() {
        BridgeRecoveryDraft draft = new BridgeRecoveryDraft(true);
        draft.selectArchiveOnly();

        assertTrue(draft.initialBridgeEnabled(), "immutable baseline must stay true");
        assertFalse(draft.bridgeEnabled(), "current must be false after archive-only");
        assertTrue(draft.pending(), "pending must be true when current differs from initial");
    }

    @Test
    void reversibleSelection_returnsPendingFalseWhenRestored() {
        BridgeRecoveryDraft draft = new BridgeRecoveryDraft(true);
        draft.selectArchiveOnly();
        assertTrue(draft.pending());

        draft.setBridgeEnabled(true);
        assertFalse(draft.pending(), "restoring to initial must clear pending");
        assertTrue(draft.bridgeEnabled());
        assertTrue(draft.initialBridgeEnabled(), "baseline unchanged");
    }

    @Test
    void repeatedSelection_isIdempotent() {
        BridgeRecoveryDraft draft = new BridgeRecoveryDraft(true);

        draft.selectArchiveOnly();
        draft.selectArchiveOnly();
        assertFalse(draft.bridgeEnabled());
        assertTrue(draft.pending());

        draft.setBridgeEnabled(true);
        draft.setBridgeEnabled(true);
        assertTrue(draft.bridgeEnabled());
        assertFalse(draft.pending());
    }

    @Test
    void disabledInitial_canBeEnabledThenRestored() {
        BridgeRecoveryDraft draft = new BridgeRecoveryDraft(false);

        draft.setBridgeEnabled(true);
        assertTrue(draft.bridgeEnabled());
        assertTrue(draft.pending());
        assertFalse(draft.initialBridgeEnabled(), "baseline must remain false");

        draft.setBridgeEnabled(false);
        assertFalse(draft.bridgeEnabled());
        assertFalse(draft.pending(), "restoring to initial must clear pending");
        assertFalse(draft.initialBridgeEnabled(), "baseline still false");
    }

    @Test
    void independentDrafts_doNotAlterEachOther() {
        BridgeRecoveryDraft a = new BridgeRecoveryDraft(true);
        BridgeRecoveryDraft b = new BridgeRecoveryDraft(false);

        a.selectArchiveOnly();
        b.setBridgeEnabled(true);

        assertFalse(a.bridgeEnabled());
        assertTrue(a.pending());
        assertTrue(b.bridgeEnabled());
        assertTrue(b.pending());

        a.setBridgeEnabled(true);
        assertFalse(a.pending(), "restoring A must not affect B");
        assertTrue(b.pending(), "B remains pending");
        assertTrue(b.bridgeEnabled());
        assertFalse(b.initialBridgeEnabled());
    }
}
