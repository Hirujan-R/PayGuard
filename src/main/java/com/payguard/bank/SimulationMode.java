package com.payguard.bank;

public enum SimulationMode {
    /** Every charge succeeds. */
    NORMAL,
    /** Every charge fails with a transient "acquirer unavailable" error. */
    HARD_FAIL,
    /** Every charge exceeds its deadline. */
    TIMEOUT,
    /**
     * The hardest case: the charge is settled and written to the bank ledger,
     * but the response back to the gateway is lost. Reproduces "am I charged
     * or not?", resolved later by the reconciliation job.
     */
    DROPPED_RESPONSE
}
