package com.personal.finance.repository;

import com.personal.finance.entity.Direction;
import com.personal.finance.entity.FinancialTransaction;
import com.personal.finance.entity.SourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FinancialTransactionRepository extends JpaRepository<FinancialTransaction, Long> {

    Optional<FinancialTransaction> findFirstByReferenceNoAndDirectionAndAmountOrderByIdAsc(
            String referenceNo, Direction direction, BigDecimal amount);

    @Query("select t from FinancialTransaction t where t.direction = :direction and t.amount = :amount "
            + "and t.sourceType <> :source and t.eventTimestamp between :from and :to")
    List<FinancialTransaction> findNearbyFromOtherSources(@Param("direction") Direction direction,
                                                          @Param("amount") BigDecimal amount,
                                                          @Param("source") SourceType source,
                                                          @Param("from") Instant from, @Param("to") Instant to);

    boolean existsByGroupIdAndSourceType(Long groupId, SourceType sourceType);
}
