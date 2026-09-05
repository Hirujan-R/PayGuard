package com.payguard.bank;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BankLedgerRepository extends JpaRepository<BankLedgerEntry, UUID> {

    Optional<BankLedgerEntry> findByBankRequestId(String bankRequestId);
}
