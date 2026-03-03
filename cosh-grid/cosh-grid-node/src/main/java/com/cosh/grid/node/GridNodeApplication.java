package com.cosh.grid.node;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * COSH Grid Node — single-node cache server.
 *
 * <p>
 * Exposes a REST API for the gateway to PUT, GET, and DELETE cache entries.
 * The hot path (CacheStore operations) is 100% framework-free; Spring only
 * handles the HTTP transport layer.
 */
@SpringBootApplication
public class GridNodeApplication {

    public static void main(String[] args) {
        SpringApplication.run(GridNodeApplication.class, args);
    }
}
