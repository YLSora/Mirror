package com.mirror.compat;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

/** Optional renderers register their existing resource owners, not a second viewport cache. */
public final class MirrorViewResources {
    public interface Owner {
        void releaseMirrorView(long view);
        void clearMirrorViews();
        default void restoreMainView() { }
        default void reportMemory() { }
    }

    private static final Set<Owner> OWNERS = Collections.newSetFromMap(new IdentityHashMap<>());

    private MirrorViewResources() { }

    public static void register(Owner owner) { OWNERS.add(owner); }
    public static void unregister(Owner owner) { OWNERS.remove(owner); }

    public static void release(long view) {
        // Deleting a viewport can unregister its depth target during iteration.
        for (Owner owner : OWNERS.toArray(Owner[]::new)) owner.releaseMirrorView(view);
    }

    public static void clear() {
        for (Owner owner : OWNERS.toArray(Owner[]::new)) owner.clearMirrorViews();
    }

    public static void restoreMain() {
        for (Owner owner : OWNERS) owner.restoreMainView();
    }

    public static void reportMemory() {
        for (Owner owner : OWNERS) owner.reportMemory();
    }
}
