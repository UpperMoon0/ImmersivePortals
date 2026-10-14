package qouteall.imm_ptl.core.compat.sable;

import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/** Short-lived server-observed aperture crossings, never client-reported positions. */
final class SableRiderPortalWitnesses {
    record Key(UUID player, UUID body, UUID vehicle, UUID portal, Object dimension, Object destinationDimension) {}

    private final long maximumTicks;
    private final Map<Key, Long> crossings = new HashMap<>();
    private final Map<Key, SableRiderPlaneContact> planeContacts = new HashMap<>();
    private record Mount(UUID body, UUID vehicle, Object dimension, long since) {}
    private final Map<UUID, Mount> mounts = new HashMap<>();

    SableRiderPortalWitnesses(long maximumTicks) {
        if (maximumTicks < 0) throw new IllegalArgumentException("Negative witness lifetime");
        this.maximumTicks = maximumTicks;
    }

    void record(Key key, long tick) { crossings.put(key, tick); planeContacts.remove(key); }

    boolean samplePlaneContact(Key key, Object beforeState, Object afterState, long tick,
                               double beforeZ, double afterZ, boolean insideAperture) {
        SableRiderPlaneContact contact = planeContacts.get(key);
        if (contact == null) {
            if (!(beforeZ > 0 && afterZ == 0 && insideAperture)) return false;
            contact = new SableRiderPlaneContact(maximumTicks);
        }
        boolean crossed = contact.sample(beforeState, afterState, tick, beforeZ, afterZ, insideAperture);
        if (contact.isArmed()) planeContacts.put(key, contact);
        else planeContacts.remove(key);
        return crossed;
    }

    boolean hasPlaneContact(Key key, long tick) {
        SableRiderPlaneContact contact = planeContacts.get(key);
        return contact != null && !contact.expired(tick);
    }

    void observeMount(UUID player, UUID body, UUID vehicle, Object dimension, long tick) {
        Mount previous = mounts.get(player);
        if (previous == null || !previous.body().equals(body) || !previous.vehicle().equals(vehicle)
            || !previous.dimension().equals(dimension) || tick < previous.since()) {
            invalidatePlayer(player);
            mounts.put(player, new Mount(body, vehicle, dimension, tick));
        }
    }

    boolean hasStableMount(Key key, long tick) {
        Mount mount = mounts.get(key.player());
        return mount != null && mount.body().equals(key.body()) && mount.vehicle().equals(key.vehicle())
            && mount.dimension().equals(key.dimension()) && tick > mount.since();
    }

    boolean has(Key key, long tick) {
        Long recorded = crossings.get(key);
        return recorded != null && tick >= recorded && tick - recorded <= maximumTicks;
    }

    void expire(Object dimension, long tick) {
        crossings.entrySet().removeIf(entry -> entry.getKey().dimension().equals(dimension)
            && (tick < entry.getValue() || tick - entry.getValue() > maximumTicks));
        planeContacts.entrySet().removeIf(entry -> entry.getKey().dimension().equals(dimension)
            && entry.getValue().expired(tick));
    }

    List<Key> keys() { return List.copyOf(crossings.keySet()); }
    void invalidate(Key key) { crossings.remove(key); planeContacts.remove(key); }
    void invalidateBody(UUID body) {
        crossings.keySet().removeIf(key -> key.body().equals(body));
        planeContacts.keySet().removeIf(key -> key.body().equals(body));
        mounts.values().removeIf(mount -> mount.body().equals(body));
    }
    void invalidatePlayer(UUID player) {
        crossings.keySet().removeIf(key -> key.player().equals(player));
        planeContacts.keySet().removeIf(key -> key.player().equals(player));
        mounts.remove(player);
    }
    void clear() { crossings.clear(); planeContacts.clear(); mounts.clear(); }
}
