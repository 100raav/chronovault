package dev.chronovault.core.protocol;

import dev.chronovault.core.domain.OperationUpdate;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-memory event bus for operation updates, streamed to CLI and web clients.
 */
public final class EventBus {
    private final List<Consumer<OperationUpdate>> subscribers = new CopyOnWriteArrayList<>();
    private final java.util.Queue<OperationUpdate> history = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static final int HISTORY_LIMIT = 2000;

    public void subscribe(Consumer<OperationUpdate> subscriber) {
        subscribers.add(subscriber);
    }

    public void unsubscribe(Consumer<OperationUpdate> subscriber) {
        subscribers.remove(subscriber);
    }

    public void publish(OperationUpdate update) {
        history.add(update);
        while (history.size() > HISTORY_LIMIT) history.remove(0);
        for (Consumer<OperationUpdate> s : subscribers) {
            try { s.accept(update); } catch (Exception ignored) {}
        }
    }

    public List<OperationUpdate> latest(int limit) {
        List<OperationUpdate> all = new java.util.ArrayList<>(history);
        if (all.size() > limit) return all.subList(all.size() - limit, all.size());
        return all;
    }
}