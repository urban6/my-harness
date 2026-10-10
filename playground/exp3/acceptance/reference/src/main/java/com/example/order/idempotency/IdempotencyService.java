package com.example.order.idempotency;

import com.example.order.common.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Idempotency-Key store (R4). A key is claimed by inserting an IN_PROGRESS row in its own (auto-committed)
 * statement; the business transaction turns it into DONE with the response snapshot; failures delete the row so the
 * same request can be retried (only 2xx responses are stored).
 */
@Service
public class IdempotencyService {

    public static final String SCOPE_CREATE_ORDER = "CREATE_ORDER";
    public static final String SCOPE_PAY = "PAY";

    public record StoredResponse(int status, String body, String location) {
    }

    private record Row(String fingerprint, String state, Integer status, String body, String location) {
    }

    private final JdbcTemplate jdbc;

    public IdempotencyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @return empty when this caller now owns the key and must process the request; otherwise the stored response
     * to replay. Throws 422 on fingerprint mismatch and 409 while another request with the key is in flight.
     */
    public Optional<StoredResponse> begin(String scope, String key, String fingerprint) {
        while (true) {
            int inserted = jdbc.update("INSERT INTO idempotency_keys (scope, idem_key, fingerprint, state) "
                    + "VALUES (?, ?, ?, 'IN_PROGRESS') ON CONFLICT (scope, idem_key) DO NOTHING",
                    scope, key, fingerprint);
            if (inserted == 1) {
                return Optional.empty();
            }
            List<Row> rows = jdbc.query("SELECT fingerprint, state, response_status, response_body, location "
                            + "FROM idempotency_keys WHERE scope = ? AND idem_key = ?",
                    (rs, i) -> new Row(rs.getString(1), rs.getString(2), (Integer) rs.getObject(3, Integer.class),
                            rs.getString(4), rs.getString(5)),
                    scope, key);
            if (rows.isEmpty()) {
                continue; // the previous holder failed and released the key; try to claim it again
            }
            Row row = rows.get(0);
            if (!row.fingerprint().equals(fingerprint)) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_MISMATCH",
                        "Idempotency-Key was already used for a different request");
            }
            if ("DONE".equals(row.state())) {
                return Optional.of(new StoredResponse(row.status(), row.body(), row.location()));
            }
            throw ApiException.conflict("IDEMPOTENCY_IN_PROGRESS",
                    "a request with this Idempotency-Key is still being processed");
        }
    }

    /** Called inside the business transaction so the snapshot commits atomically with the state change. */
    public void complete(String scope, String key, int status, String body, String location) {
        jdbc.update("UPDATE idempotency_keys SET state = 'DONE', response_status = ?, response_body = ?, "
                + "location = ? WHERE scope = ? AND idem_key = ?", status, body, location, scope, key);
    }

    public void release(String scope, String key) {
        jdbc.update("DELETE FROM idempotency_keys WHERE scope = ? AND idem_key = ? AND state = 'IN_PROGRESS'",
                scope, key);
    }

    public static String fingerprint(String canonical) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
