package io.zodia.pgledger.rest.web;

import org.apache.shardingsphere.infra.hint.HintManager;

import java.util.function.Supplier;

/**
 * {@code SELECT pgledger_create_*} is parsed as a read. The hint forces that
 * transaction onto the primary and is closed before the thread is reused.
 */
final class WriteRoutes {
    private WriteRoutes() {
    }

    static <T> T onWriter(Supplier<T> call) {
        try (HintManager hint = HintManager.getInstance()) {
            hint.setWriteRouteOnly();
            return call.get();
        }
    }
}
