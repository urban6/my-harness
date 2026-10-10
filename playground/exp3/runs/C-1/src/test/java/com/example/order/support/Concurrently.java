package com.example.order.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/** Runs N tasks that all start at the same instant (latch gate) and collects their HTTP outcomes. */
public final class Concurrently {

    private Concurrently() {
    }

    /** Immutable snapshot of one HTTP response. */
    public record Resp(int status, String contentType, JsonNode body, String replayedHeader, String retryAfter) {

        public String slug() {
            String type = body.path("type").asText("");
            return type.startsWith("urn:problem:order-payment:") ? type.substring("urn:problem:order-payment:".length()) : "";
        }

        public boolean replayed() {
            return "true".equals(replayedHeader);
        }
    }

    @FunctionalInterface
    public interface Request {
        ResultActions call() throws Exception;
    }

    public static Resp snapshot(ResultActions actions, ObjectMapper om) throws Exception {
        MvcResult r = actions.andReturn();
        String text = r.getResponse().getContentAsString();
        JsonNode body = text.isEmpty() ? om.nullNode() : om.readTree(text);
        return new Resp(r.getResponse().getStatus(), r.getResponse().getContentType(), body,
                r.getResponse().getHeader("Idempotent-Replayed"), r.getResponse().getHeader("Retry-After"));
    }

    /** Task i is built by {@code factory.apply(i)}; all tasks are released together. */
    public static List<Resp> run(int n, ObjectMapper om, IntFunction<Request> factory) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Resp>> futures = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Request request = factory.apply(i);
                Callable<Resp> task = () -> {
                    ready.countDown();
                    go.await();
                    return snapshot(request.call(), om);
                };
                futures.add(pool.submit(task));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers did not become ready");
            }
            go.countDown();
            List<Resp> out = new ArrayList<>();
            for (Future<Resp> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Runs arbitrary callables with the same start-gate semantics (e.g. sweeper calls mixed with HTTP calls). */
    public static <T> List<T> runTasks(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("workers did not become ready");
            }
            go.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(60, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    public static long count(List<Resp> responses, int status) {
        return responses.stream().filter(r -> r.status() == status).count();
    }

    public static long count(List<Resp> responses, int status, String slug) {
        return responses.stream().filter(r -> r.status() == status && slug.equals(r.slug())).count();
    }
}
