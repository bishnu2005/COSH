package com.cosh.grid.gateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * COSH Grid Gateway — consistent-hash routing layer.
 *
 * <p>
 * Accepts client requests, computes the target node(s) via consistent hashing,
 * and fans out writes / falls back to replicas on reads.
 */
@SpringBootApplication
public class GridGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(GridGatewayApplication.class, args);
    }
}
