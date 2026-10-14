package com.movieticket.payment;

import com.movieticket.payment.PaymentDtos.PaymentRequest;
import com.movieticket.payment.PaymentDtos.PaymentResponse;
import com.movieticket.payment.PaymentDtos.PaymentResult;
import com.movieticket.saga.BookingSagaOrchestrator;
import com.movieticket.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final BookingSagaOrchestrator saga;
    private final PaymentService service;

    public PaymentController(BookingSagaOrchestrator saga, PaymentService service) {
        this.saga = saga;
        this.service = service;
    }

    /**
     * Pays for a booking created with autoPay=false. The Saga then confirms the seat and booking, or refunds
     * and cancels if the seat hold has expired in the meantime.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> pay(@Valid @RequestBody PaymentRequest request,
                                               @AuthenticationPrincipal AuthenticatedUser caller) {
        PaymentResult result = saga.payForBooking(caller, request);
        HttpStatus status = result.replayed() ? HttpStatus.OK : switch (result.payment().status()) {
            case COMPLETED -> HttpStatus.CREATED;
            case FAILED -> HttpStatus.PAYMENT_REQUIRED;
            case REFUNDED -> HttpStatus.CONFLICT;
            case PENDING -> HttpStatus.ACCEPTED;
        };
        return ResponseEntity.status(status).body(result.payment());
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable Long id, @AuthenticationPrincipal AuthenticatedUser caller) {
        return service.get(id, caller);
    }
}
