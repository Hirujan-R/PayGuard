package com.payguard.bank;

import java.time.Instant;

public record BankChargeResult(String chargeReference, Instant processedAt) {}
