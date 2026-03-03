package com.cosh.grid.node.api;

import com.cosh.core.store.CacheStore;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST endpoint for the COSH grid node.
 *
 * <p>
 * This controller is deliberately thin — it delegates immediately to the
 * framework-free {@link CacheStore}. No business logic lives here.
 *
 * <p>
 * The gateway reaches this service via HTTP for all PUT/GET/DELETE operations.
 */
@RestController
@RequestMapping("/cache")
public class NodeController {

    private final CacheStore cacheStore;

    public NodeController(CacheStore cacheStore) {
        this.cacheStore = cacheStore;
    }

    @GetMapping("/{key}")
    public ResponseEntity<String> get(@PathVariable String key) {
        String value = cacheStore.get(key);
        return (value == null)
                ? ResponseEntity.notFound().build()
                : ResponseEntity.ok(value);
    }

    @PutMapping(value = "/{key}", consumes = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<Void> put(
            @PathVariable String key,
            @RequestBody String value,
            @RequestHeader(value = "X-TTL", required = false) Long ttl) {
        cacheStore.put(key, value, (ttl != null) ? ttl : 0L);
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<Void> delete(@PathVariable String key) {
        cacheStore.delete(key);
        return ResponseEntity.ok().build();
    }
}
