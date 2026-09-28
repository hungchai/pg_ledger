package io.zodia.pgledger.rest;

final class BadRequestException extends RuntimeException {
    BadRequestException() {
        super(null, null, false, false);
    }
}
