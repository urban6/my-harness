package com.example.order.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.ResponseEntity;

/** Shared RFC 9457 assertions (01_api_design.md section 4). */
final class ProblemAssertions {

    private ProblemAssertions() {
    }

    static void assertProblem(ResponseEntity<JsonNode> res, int status, String slug) {
        assertThat(res.getStatusCode().value()).as("body=%s", res.getBody()).isEqualTo(status);
        assertThat(res.getHeaders().getContentType()).isNotNull();
        assertThat(res.getHeaders().getContentType().toString()).startsWith("application/problem+json");
        JsonNode body = res.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("type").asText()).isEqualTo("https://example.com/problems/" + slug);
        assertThat(body.get("code").asText()).isEqualTo(slug.toUpperCase().replace('-', '_'));
        assertThat(body.get("status").asInt()).isEqualTo(status);
        assertThat(body.hasNonNull("title")).isTrue();
        assertThat(body.hasNonNull("detail")).isTrue();
        assertThat(body.hasNonNull("instance")).isTrue();
    }
}
