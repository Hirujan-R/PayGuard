package com.payguard.bank;

/**
 * Failure modes thrown by the simulated bank so the gateway's resilience layer
 * can react to each one differently. They mirror the four realistic behaviours
 * a real payment processor sees from its downstream acquirer:
 *   Timeout    -> call exceeded its deadline (retryable)
 *   Unavailable-> acquirer returned a transient error (retryable)
 *   ResponseLost -> acquirer settled the charge but the reply never arrived
 *                   (NOT retryable - retrying could double charge; the gateway
 *                   marks the transaction UNKNOWN and reconciles against the
 *                   bank ledger instead)
 */
public class BankException extends RuntimeException {

    public BankException(String message) { super(message); }

    public static class Timeout extends BankException {
        public Timeout(String message) { super(message); }
    }

    public static class Unavailable extends BankException {
        public Unavailable(String message) { super(message); }
    }

    /** Carries the charge reference the bank already recorded on its ledger. */
    public static class ResponseLost extends BankException {
        private final String chargeReference;

        public ResponseLost(String message, String chargeReference) {
            super(message);
            this.chargeReference = chargeReference;
        }

        public String getChargeReference() { return chargeReference; }
    }
}
