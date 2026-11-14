package com.localllm.locie.chat;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Component;

/**
 * The reply currently streaming per chat.
 *
 * <p>Stopping used to mean "the browser aborts its fetch", which fails when the tab is closed or a
 * proxy holds the connection open. This registry lets a stop request reach the generator directly:
 * the streaming pipeline checks the flag between chunks and cancels upstream itself.
 */
@Component
public class ActiveStreams {

    private final Map<UUID, AtomicBoolean> active = new ConcurrentHashMap<>();

    Registration register(UUID chatId) {
        AtomicBoolean stopped = new AtomicBoolean(false);
        active.put(chatId, stopped);
        return new Registration(chatId, stopped);
    }

    /** True when a stream was running and has now been asked to stop. */
    boolean stop(UUID chatId) {
        AtomicBoolean flag = active.get(chatId);
        if (flag == null) {
            return false;
        }
        flag.set(true);
        return true;
    }

    boolean isActive(UUID chatId) {
        return active.containsKey(chatId);
    }

    /** Handle for one streamed reply; closing it removes the chat from the registry. */
    final class Registration {

        private final UUID chatId;
        private final AtomicBoolean stopped;

        private Registration(UUID chatId, AtomicBoolean stopped) {
            this.chatId = chatId;
            this.stopped = stopped;
        }

        boolean stopped() {
            return stopped.get();
        }

        void close() {
            active.remove(chatId, stopped);
        }
    }
}
