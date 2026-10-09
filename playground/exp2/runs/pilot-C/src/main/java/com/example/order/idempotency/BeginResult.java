package com.example.order.idempotency;

public sealed interface BeginResult {
    record Owner(long id) implements BeginResult {
    }

    record Replay(int status, String body, String location) implements BeginResult {
    }
}
