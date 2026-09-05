package com.payguard.deadletter;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DeadLetterRepository extends JpaRepository<DeadLetterTransaction, UUID> {

    List<DeadLetterTransaction> findAllByOrderByCreatedAtDesc();

    Optional<DeadLetterTransaction> findByTransactionId(UUID transactionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select d from DeadLetterTransaction d where d.id = :id")
    Optional<DeadLetterTransaction> findForUpdate(@Param("id") UUID id);
}
