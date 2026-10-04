package io.zodia.pgledger.rest.web;

final class BadRequestException extends RuntimeException {
    BadRequestException() {
        super(null, null, false, false);
    }
}
