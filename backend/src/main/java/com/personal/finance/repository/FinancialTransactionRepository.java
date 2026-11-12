package com.personal.finance.repository;

import com.personal.finance.entity.Direction;
import com.personal.finance.entity.FinancialTransaction;
import com.personal.finance.entity.RawFinancialEvent;
import com.personal.finance.entity.SourceType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
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

    /** Newest groups first; a group is placed by its latest row. */
    @Query("select t.groupId from FinancialTransaction t where t.eventTimestamp >= :from and t.eventTimestamp < :to "
            + "group by t.groupId order by max(t.eventTimestamp) desc")
    List<Long> findGroupIds(@Param("from") Instant from, @Param("to") Instant to, Pageable page);

    List<FinancialTransaction> findByGroupIdIn(Collection<Long> groupIds);

    /** Sets the user's category, description and split on the rows of one phone event; returns how many rows matched. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update FinancialTransaction t set t.userCategory = :category, t.userLabel = :label, t.userSplits = :splits "
            + "where t.deviceId = :deviceId and t.rawEventId in "
            + "(select e.id from RawFinancialEvent e where e.deviceId = :deviceId and e.localEventId = :localId)")
    int applyLabel(@Param("deviceId") Long deviceId, @Param("localId") String localId,
                   @Param("category") String category, @Param("label") String label, @Param("splits") String splits);

    @Query("select t.sourceType, count(t), max(t.eventTimestamp) from FinancialTransaction t group by t.sourceType")
    List<Object[]> countBySource();

    @Query("select t.bankCode, count(t), max(t.eventTimestamp) from FinancialTransaction t group by t.bankCode")
    List<Object[]> countByBank();

    @Query("select t.groupId from FinancialTransaction t group by t.groupId having count(distinct t.sourceType) > 1")
    List<Long> findMultiChannelGroupIds();

    @Query("select count(distinct t.groupId) from FinancialTransaction t")
    long countGroups();

    @Query("select min(t.eventTimestamp) from FinancialTransaction t")
    Instant earliestEvent();
}
