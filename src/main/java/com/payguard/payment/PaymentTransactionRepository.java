package com.payguard.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, UUID> {

    Optional<PaymentTransaction> findByIdempotencyKey(String idempotencyKey);

    List<PaymentTransaction> findTop50ByOrderByCreatedAtDesc();

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentTransaction p where p.id = :id")
    Optional<PaymentTransaction> findForUpdate(@Param("id") UUID id);

    /**
     * Recent attempt history for a single account, newest first. Used by the fraud
     * engine for the velocity and geo-jump rules and for the amount z-score.
     * Native SQL because we want a bounded window without loading the whole table.
     */
    @Query(value = """
        select id, region, status, amount_minor, created_at
        from payment_transactions
        where account_id = :accountId and id <> :selfId
        order by created_at desc
        limit 200
        """, nativeQuery = true)
    List<Object[]> findFraudHistory(@Param("accountId") String accountId, @Param("selfId") UUID selfId);
}
