package com.cosh.grid.gateway.api;

import com.cosh.grid.gateway.routing.CacheRouter;
import com.cosh.grid.gateway.transport.NodeTransport;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.function.Supplier;

/**
 * REST API gateway — the single entry point for all client cache operations.
 *
 * <p>
 * Injects {@link NodeTransport} — works with either HTTP or Netty transport
 * without modification. Transport is selected at startup via
 * {@code cosh.gateway.transport} property.
 */
@RestController
@RequestMapping("/cache")
public class GatewayController {

    private final CacheRouter cacheRouter;
    private final NodeTransport nodeTransport;
    private final MeterRegistry registry;

    public GatewayController(CacheRouter cacheRouter,
            NodeTransport nodeTransport,
            MeterRegistry registry) {
        this.cacheRouter = cacheRouter;
        this.nodeTransport = nodeTransport;
        this.registry = registry;
    }

    @GetMapping("/{key}")
    public ResponseEntity<String> get(@PathVariable String key) {
        return recordMetrics("get", () -> {
            List<String> nodes = cacheRouter.route(key);
            String value = nodeTransport.get(nodes, key);
            return (value == null)
                    ? ResponseEntity.notFound().build()
                    : ResponseEntity.ok(value);
        });
    }

    @PutMapping("/{key}")
    public ResponseEntity<Void> put(@PathVariable String key, @RequestBody String value) {
        return recordMetrics("put", () -> {
            List<String> nodes = cacheRouter.route(key);
            nodeTransport.put(nodes, key, value);
            return ResponseEntity.ok().<Void>build();
        });
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        return recordMetrics("delete", () -> {
            List<String> nodes = cacheRouter.route(key);
            nodeTransport.delete(nodes, key);
            return ResponseEntity.ok().<Void>build();
        });
    }

    private <T> ResponseEntity<T> recordMetrics(String method, Supplier<ResponseEntity<T>> action) {
        Timer.Sample sample = Timer.start(registry);
        String status = "success";
        try {
            ResponseEntity<T> response = action.get();
            if (response.getStatusCode().is4xxClientError())
                status = "client_error";
            else if (response.getStatusCode().is5xxServerError())
                status = "server_error";
            return response;
        } catch (Exception e) {
            status = "server_error";
            throw e;
        } finally {
            sample.stop(registry.timer("cosh.gateway.latency", "method", method));
            registry.counter("cosh.gateway.requests", "method", method, "status", status).increment();
        }
    }
}
