package io.zodia.pgledger.rest;

import io.zodia.pgledger.store.LedgerJson;
import io.zodia.pgledger.store.LedgerViolation;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

@RestControllerAdvice
final class LedgerExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(LedgerExceptionHandler.class);

    @ExceptionHandler(LedgerViolation.class)
    ResponseEntity<byte[]> violation(LedgerViolation exception, HttpServletRequest request) {
        String role = "POST".equals(request.getMethod()) ? PgLedgerServer.WRITER : PgLedgerServer.READER;
        return HttpResponses.of(422, role, LedgerJson.writeBytes(Map.of("error", exception.getMessage())));
    }

    @ExceptionHandler({
            BadRequestException.class,
            IllegalArgumentException.class,
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class
    })
    ResponseEntity<byte[]> badRequest() {
        return HttpResponses.of(400, null, HttpResponses.BAD_REQUEST);
    }

    @ExceptionHandler({
            NoHandlerFoundException.class,
            NoResourceFoundException.class,
            HttpRequestMethodNotSupportedException.class
    })
    ResponseEntity<byte[]> notFound() {
        return HttpResponses.of(404, null, HttpResponses.NOT_FOUND);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<byte[]> failure(Exception exception) {
        LOG.error("pgledger request failed", exception);
        return HttpResponses.of(500, null, HttpResponses.SERVER_ERROR);
    }
}
