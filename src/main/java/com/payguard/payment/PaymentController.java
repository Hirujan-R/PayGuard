package com.payguard.payment;

import com.payguard.payment.PaymentDtos.CreatePaymentRequest;
import com.payguard.payment.PaymentDtos.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/payments")
public class PaymentController {

    private final PaymentService paymentService;

    public PaymentController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** Submit a payment. Idempotency-Key is required (see README section 4.2). */
    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public PaymentResponse create(@Valid @RequestBody CreatePaymentRequest request,
                                  @RequestHeader("Idempotency-Key") String idempotencyKey) {
        return paymentService.submit(request, idempotencyKey);
    }

    @GetMapping("/{id}")
    public PaymentResponse get(@PathVariable String id) {
        return paymentService.get(id);
    }

    @PostMapping("/{id}/refund")
    public PaymentResponse refund(@PathVariable String id) {
        return paymentService.refund(id);
    }
}
