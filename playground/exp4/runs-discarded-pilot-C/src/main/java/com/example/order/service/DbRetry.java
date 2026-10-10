package com.example.order.service;

import java.sql.SQLException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import org.springframework.dao.ConcurrencyFailureException;

/**
 * 안전망: 데드락(40P01)/직렬화 실패(40001)는 PG 호출이 없는 DB 트랜잭션 단위로 재시도한다.
 * 락 순서 규칙상 정상 경로에서는 발생하지 않는다.
 */
public final class DbRetry {

    private static final int MAX_ATTEMPTS = 4;

    private DbRetry() {
    }

    public static <T> T run(Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                return action.get();
            } catch (RuntimeException e) {
                attempt++;
                if (attempt >= MAX_ATTEMPTS || !isRetryable(e)) {
                    throw e;
                }
                try {
                    Thread.sleep(10L + ThreadLocalRandom.current().nextInt(40));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    static boolean isRetryable(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConcurrencyFailureException) {
                return true;
            }
            if (t instanceof SQLException sql) {
                String state = sql.getSQLState();
                if ("40P01".equals(state) || "40001".equals(state)) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
