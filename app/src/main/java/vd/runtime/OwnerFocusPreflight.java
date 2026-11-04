package vd.runtime;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only paired snapshots. Never launches, moves, removes or focuses a task. */
final class OwnerFocusPreflight {
    static final int MAX_SAMPLES = 4;
    // Checked around reads, not a timeout capable of interrupting a blocking Binder call.
    static final long BUDGET_NS = 400_000_000L;

    interface Source {
        void verifyDisplay() throws Exception;
        Map<Integer, Object> roots() throws Exception;
        FocusWitness focusedWitness(Map<Integer, Object> inventory) throws Exception;
        long nanoTime();
    }

    static final class Sample {
        final FocusWitness witness;
        final Map<Integer, Object> inventory;
        final List<String> observations;
        Sample(FocusWitness witness, Map<Integer, Object> inventory, List<String> observations) {
            this.witness = witness;
            this.inventory = inventory;
            this.observations = new ArrayList<String>(observations);
        }
    }

    /** Only absent/changing focus snapshots can be resampled; malformed identities cannot. */
    static boolean canResample(FocusWitness.Reason reason) {
        switch (reason) {
            case FOCUS_MISSING:
            case INVENTORY_MISSING:
            case TASK_CHANGED:
            case USER_CHANGED:
            case DISPLAY_CHANGED:
            case ROOT_CHANGED:
            case BINDER_CHANGED:
            case BASE_CHANGED:
            case DATA_CHANGED:
            case STRUCTURE_CHANGED:
            case FOCUS_UNSTABLE:
                return true;
            default:
                return false;
        }
    }

    static Sample capture(Source reader, int sourceDisplay, Map<Integer, OwnerHandoff.Task> owned,
            Set<Integer> selected) throws OwnerHandoff.HandoffFailure {
        return capture(reader, sourceDisplay, owned, selected, true);
    }

    /**
     * @param requireMainFocus true for any delivery/handoff that addresses display 0. An empty-selection
     *                         cleanup only removes roots from the source display and never launches or
     *                         moves anything into display 0, so it validates source ownership and
     *                         source stability only; a work-profile foreground cannot block it.
     */
    static Sample capture(Source reader, int sourceDisplay, Map<Integer, OwnerHandoff.Task> owned,
            Set<Integer> selected, boolean requireMainFocus) throws OwnerHandoff.HandoffFailure {
        final long start = reader.nanoTime();
        FocusWitness previous = null;
        Set<Integer> previousSourceIds = null;
        Throwable last = requireMainFocus
                ? new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_UNSTABLE)
                : new IllegalStateException("source inventory unstable");
        List<String> observations = new ArrayList<String>();
        for (int attempt = 0; attempt < MAX_SAMPLES; attempt++) {
            if (Thread.currentThread().isInterrupted()) {
                throw failure("preflight:cancelled", new InterruptedException(), observations);
            }
            if (reader.nanoTime() - start >= BUDGET_NS) break;
            String phase = "preflight:display";
            try {
                // Every round rechecks source ownership, not just the main-display focus.
                reader.verifyDisplay();
                phase = "preflight:inventory";
                Map<Integer, Object> current = reader.roots();
                verifySourceInventory(sourceDisplay, selected, current, owned);
                if (!requireMainFocus) {
                    // Cleanup stability: the same source roots, re-verified against their registered
                    // identities, on two consecutive rounds. No display-0 read is performed at all.
                    Set<Integer> sourceIds = sourceTaskIds(sourceDisplay, current);
                    if (previousSourceIds != null && previousSourceIds.equals(sourceIds)) {
                        return new Sample(null, current, observations);
                    }
                    previousSourceIds = sourceIds;
                    last = new IllegalStateException("source inventory unstable");
                    continue;
                }
                phase = "preflight:focus";
                FocusWitness witness;
                try {
                    // The production source reads explicit display-0 focus, not global top focus.
                    witness = reader.focusedWitness(current);
                    if (witness == null) throw new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_MISSING);
                } catch (FocusWitness.Rejected ex) {
                    observations.add(ex.diagnosticCode());
                    if (!canResample(ex.reason)) throw ex;
                    previous = null;
                    last = ex;
                    continue;
                }
                observations.add("OK");
                if (reader.nanoTime() - start >= BUDGET_NS) {
                    last = new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_UNSTABLE);
                    break;
                }
                if (Thread.currentThread().isInterrupted()) {
                    throw failure("preflight:cancelled", new InterruptedException(), observations);
                }
                if (previous != null && previous.matches(witness)) {
                    return new Sample(witness, current, observations);
                }
                previous = witness;
                last = new FocusWitness.Rejected(FocusWitness.Reason.FOCUS_UNSTABLE);
            } catch (OwnerHandoff.HandoffFailure ex) {
                throw ex;
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw failure("preflight:cancelled", ex, observations);
            } catch (Exception ex) {
                // Reflection/permission failures and source changes are not focus jitter.
                throw failure(phase, ex, observations);
            }
        }
        // A rejection on the final sample may coincide with cancellation: never report it
        // as focus jitter eligible for another physical handoff attempt.
        if (Thread.currentThread().isInterrupted()) {
            throw failure("preflight:cancelled", new InterruptedException(), observations);
        }
        throw failure(requireMainFocus ? "preflight:focus" : "preflight:inventory", last, observations);
    }

    private static Set<Integer> sourceTaskIds(int source, Map<Integer, Object> current) throws Exception {
        Set<Integer> ids = new LinkedHashSet<Integer>();
        for (Object task : current.values()) {
            if (OwnerHandoff.number(task, "displayId") == source) {
                ids.add(OwnerHandoff.number(task, "taskId"));
            }
        }
        return ids;
    }

    static void verifySourceInventory(int source, Set<Integer> selected, Map<Integer, Object> current,
            Map<Integer, OwnerHandoff.Task> owned) throws Exception {
        for (Object task : current.values()) if (OwnerHandoff.number(task, "displayId") == source) {
            OwnerHandoff.Task identity = owned.get(OwnerHandoff.number(task, "taskId"));
            if (identity == null) throw new OwnerHandoff.Rejection("ForeignSourceTask");
            identity.check(task, source);
        }
        for (Integer id : selected) owned.get(id).check(current.get(id), source);
    }

    private static OwnerHandoff.HandoffFailure failure(String phase, Throwable cause, List<String> samples) {
        return new OwnerHandoff.HandoffFailure(phase, cause, false, "moved=[] removed=[]")
                .withFocusSamples(samples);
    }
}
