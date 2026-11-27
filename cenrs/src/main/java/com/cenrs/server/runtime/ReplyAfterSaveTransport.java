package com.cenrs.server.runtime;

import com.cenrs.contract.port.TransportPort;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Makes sure a reply is sent only AFTER the state change is saved (protocol.md, persistence rules).
 *
 * While a worker handles a request (between begin() and commit()), every message it sends
 * is held. commit() sends them once the snapshot is on disk; discard() drops them if the
 * save failed, so a client is never told "success" for a change that could be lost in a crash.
 *
 * Messages sent outside a request (e.g. retransmission timers) go out immediately.
 */
final class ReplyAfterSaveTransport implements TransportPort {

    private final TransportPort network;

    // One list per worker thread: requests handled at the same time never mix their replies.
    private final ThreadLocal<List<HeldMessage>> held = new ThreadLocal<>();
    private final ThreadLocal<List<Runnable>> afterCommit =
            new ThreadLocal<>();

    ReplyAfterSaveTransport(TransportPort network) {
        this.network = Objects.requireNonNull(network);
    }

    /** Start holding this thread's messages. */
    void begin() {
        held.set(new ArrayList<>());
        afterCommit.set(new ArrayList<>());
    }

    /** The state was saved: send the held messages, in order. */
    void commit() throws IOException {
        List<HeldMessage> messages = held.get();
        List<Runnable> actions = afterCommit.get();
        held.remove();
        afterCommit.remove();

        if (messages == null) {
            if (actions != null) {
                actions.forEach(Runnable::run);
            }
            return;
        }

        for (HeldMessage message : messages) {
            network.send(message.destination(), message.bytes());
        }

        if (actions != null) {
            actions.forEach(Runnable::run);
        }
    }

    /** The save failed: drop the held messages. */
    void discard() {
        held.remove();
        afterCommit.remove();
    }

    /** Run only after the current request's state has been saved. */
    void afterCommit(Runnable action) {
        Objects.requireNonNull(action);
        List<Runnable> actions = afterCommit.get();

        if (actions == null) {
            action.run();
        } else {
            actions.add(action);
        }
    }

    @Override
    public void send(InetSocketAddress destination, byte[] message) throws IOException {
        List<HeldMessage> messages = held.get();

        if (messages == null) {
            network.send(destination, message);
        } else {
            messages.add(new HeldMessage(destination, message.clone()));
        }
    }

    private record HeldMessage(InetSocketAddress destination, byte[] bytes) {
    }
}
