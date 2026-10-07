package io.zodia.pgledger.rest.web;

import io.zodia.pgledger.rest.PgLedgerServer;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;

final class HttpResponses {
    static final MediaType JSON = MediaType.parseMediaType("application/json;charset=UTF-8");
    static final byte[] NOT_FOUND = "{\"error\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
    static final byte[] BAD_REQUEST = "{\"error\":\"bad request\"}".getBytes(StandardCharsets.UTF_8);
    static final byte[] SERVER_ERROR = "{\"error\":\"internal error\"}".getBytes(StandardCharsets.UTF_8);
    static final byte[] UP = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);

    private HttpResponses() {
    }

    static ResponseEntity<byte[]> of(int status, String role, byte[] body) {
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(status).contentType(JSON);
        if (role != null) {
            builder.header(PgLedgerServer.ROLE_HEADER, role);
        }
        return builder.body(body);
    }
}
