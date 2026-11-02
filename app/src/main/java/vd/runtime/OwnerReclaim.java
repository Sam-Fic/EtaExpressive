package vd.runtime;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Moves an owned task the platform relocated off the source display back onto it.
 *
 * <p>A vendor display policy may refuse an activity on a virtual display and recycle its task to
 * the main display. That is a display placement change, not a new task: the same task id, binder,
 * ownership marker and root shape remain. Only such a proven task is moved back, and only after a
 * fresh identity check; anything unproven stays escaped and fails closed.
 */
final class OwnerReclaim {
    static final String FAILED = "RECLAIM_FAILED";
    static final String UNCERTAIN = "RECLAIM_UNCERTAIN";
    private static final int MAX_TASKS = 8;
    private static final long VERIFY_TIMEOUT_MS = 2_000L;
    private static final long VERIFY_INTERVAL_MS = 100L;

    private OwnerReclaim() {
    }

    /**
     * @param requested explicit ids, or {@code null} for every owned root currently on another
     *                  display. All targets are verified before the first move.
     */
    static JSONObject run(int source, String unique, Map<Integer, OwnerHandoff.Task> owned,
            JSONArray requested) throws OwnerException {
        if (owned.isEmpty()) return result(new JSONArray());
        try {
            OwnerHandoff.verifyDisplay(source, unique);
        } catch (Exception ex) {
            throw new OwnerException("DISPLAY_REBOUND", "display identity");
        }
        Map<Integer, Object> roots;
        try {
            roots = OwnerHandoff.roots();
        } catch (Exception ex) {
            throw new OwnerException(UNCERTAIN, "inventory");
        }
        Set<Integer> targets = new LinkedHashSet<Integer>();
        if (requested == null) {
            for (Integer id : owned.keySet()) {
                Object task = roots.get(id);
                if (task == null) continue;
                try {
                    if (OwnerHandoff.number(task, "displayId") == source) continue;
                } catch (Exception ignored) {
                    continue;
                }
                targets.add(id);
            }
        } else {
            if (requested.length() > MAX_TASKS) {
                throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "taskIds");
            }
            for (int i = 0; i < requested.length(); i++) {
                Object raw = requested.opt(i);
                if (!(raw instanceof Integer)) {
                    throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "taskIds");
                }
                int id = ((Integer) raw).intValue();
                if (!owned.containsKey(id)) throw new OwnerException(FAILED, "not-owned");
                if (!targets.add(id)) throw new OwnerException(OwnerProtocol.ERROR_PROTOCOL, "taskIds");
            }
        }
        if (targets.isEmpty()) return result(new JSONArray());
        // Moving one target while another is unproven would leave a mixed session behind.
        for (Integer id : targets) {
            try {
                owned.get(id).checkMoved(roots.get(id), source);
            } catch (Exception ex) {
                throw new OwnerException(FAILED, "task identity");
            }
        }
        JSONArray reclaimed = new JSONArray();
        // One shared deadline keeps a session with several escaped roots bounded well under one
        // IPC request timeout; each move itself is a single binder call.
        long deadline = System.nanoTime() + VERIFY_TIMEOUT_MS * 1_000_000L;
        for (Integer id : targets) {
            try {
                OwnerHandoff.invokeAtm("moveRootTaskToDisplay",
                        new Class<?>[]{int.class, int.class}, id, source);
            } catch (java.lang.reflect.InvocationTargetException ex) {
                // The service rejected the move before mutating anything.
                throw new OwnerException(FAILED, "move rejected");
            } catch (Exception ex) {
                // No reply from the service: the move may or may not have been applied.
                throw new OwnerException(UNCERTAIN, "move " + ex.getClass().getSimpleName());
            }
            if (!verify(source, owned.get(id), id, deadline)) {
                throw new OwnerException(UNCERTAIN, "reclaim not observed");
            }
            reclaimed.put(id);
        }
        return result(reclaimed);
    }

    /** Settled only when the task is a root on the source display with its identity unchanged. */
    private static boolean verify(int source, OwnerHandoff.Task identity, int id, long deadline) {
        while (true) {
            try {
                Map<Integer, Object> roots = OwnerHandoff.roots();
                if (roots.containsKey(id)) {
                    identity.check(roots.get(id), source);
                    return true;
                }
            } catch (Exception ignored) {
                // Not settled yet: the task may be mid-move or the inventory may be unreadable.
            }
            if (System.nanoTime() >= deadline) return false;
            try {
                Thread.sleep(VERIFY_INTERVAL_MS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static JSONObject result(JSONArray reclaimed) {
        JSONObject out = new JSONObject();
        try {
            out.put("reclaimedTaskIds", reclaimed);
            out.put("reclaimed", reclaimed.length());
        } catch (JSONException ex) {
            throw new IllegalStateException("reclaim result", ex);
        }
        return out;
    }
}
