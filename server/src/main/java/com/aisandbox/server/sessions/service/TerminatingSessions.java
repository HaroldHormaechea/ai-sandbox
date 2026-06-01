package com.aisandbox.server.sessions.service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * UC-28 in-flight-delete registry. Tracks the set of session numbers whose
 * teardown ({@code clean.sh} → {@code docker compose down}) is currently in
 * progress, so {@link DockerEnumerationService#enumerate()} can report them
 * as {@code terminating} for the (short, racy) window the {@code removing}
 * Docker state would otherwise be hard to observe deterministically.
 *
 * <h2>Why the service package?</h2>
 *
 * The flag is written by {@code SessionFacade.deleteSession} (the facade
 * layer, which owns the delete use case) and read by
 * {@link DockerEnumerationService} (the service layer). Placing the holder in
 * the {@code service} package lets the enumeration service depend on it
 * <em>downward</em> (service → service) rather than forcing an upward
 * dependency on {@code facade.internal} — keeping the
 * {@code profile-java-server-architecture} layering rule intact (a service
 * never reaches up into the facade layer).
 *
 * <p>Backed by a lock-free {@link ConcurrentHashMap#newKeySet()} so the
 * facade's write threads and the enumeration read threads never block each
 * other. The set is authoritative only for the duration of a single delete:
 * {@code SessionFacade} clears the entry in a {@code finally}, so a crashed /
 * timed-out {@code clean.sh} cannot leave a session pinned as terminating
 * forever (the server-side flag is reasserted on every delete attempt).
 */
@Component
public class TerminatingSessions {

    private final Set<Integer> terminating = ConcurrentHashMap.newKeySet();

    /** Mark session {@code n} as terminating (teardown started). */
    public void markTerminating(int n) {
        terminating.add(n);
    }

    /** Clear the terminating flag for session {@code n} (teardown resolved). */
    public void clear(int n) {
        terminating.remove(n);
    }

    /** @return {@code true} while session {@code n}'s teardown is in flight. */
    public boolean isTerminating(int n) {
        return terminating.contains(n);
    }
}
