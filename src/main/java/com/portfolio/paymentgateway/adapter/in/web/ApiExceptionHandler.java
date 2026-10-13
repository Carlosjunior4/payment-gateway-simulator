package com.portfolio.paymentgateway.adapter.in.web;

import com.portfolio.paymentgateway.application.exception.IdempotencyConflictException;
import com.portfolio.paymentgateway.application.exception.MerchantNotActiveException;
import com.portfolio.paymentgateway.application.exception.MerchantNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IdempotencyConflictException.class)
    ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException exception) {
        return problem(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", exception.getMessage());
    }

    @ExceptionHandler(MerchantNotFoundException.class)
    ResponseEntity<ProblemDetail> handleMerchantNotFound(MerchantNotFoundException exception) {
        return problem(HttpStatus.NOT_FOUND, "MERCHANT_NOT_FOUND", exception.getMessage());
    }

    @ExceptionHandler(MerchantNotActiveException.class)
    ResponseEntity<ProblemDetail> handleMerchantNotActive(MerchantNotActiveException exception) {
        return problem(HttpStatus.FORBIDDEN, "MERCHANT_NOT_ACTIVE", exception.getMessage());
    }

    @ExceptionHandler(InvalidBankSignatureException.class)
    ResponseEntity<ProblemDetail> handleInvalidBankSignature(InvalidBankSignatureException exception) {
        return problem(HttpStatus.UNAUTHORIZED, "INVALID_BANK_SIGNATURE", exception.getMessage());
    }

    @ExceptionHandler({
            IllegalArgumentException.class,
            MethodArgumentNotValidException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class
    })
    ResponseEntity<ProblemDetail> handleBadRequest(Exception exception) {
        return problem(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Request is invalid");
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        return ResponseEntity.status(status).body(problem);
    }
}
