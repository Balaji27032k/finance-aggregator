package com.personal.finance.repository;

import com.personal.finance.entity.ProcessingStatus;
import com.personal.finance.entity.RawFinancialEvent;
import com.personal.finance.entity.SourceType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RawFinancialEventRepository extends JpaRepository<RawFinancialEvent, Long> {

    interface StatusView {
        Long getId();

        String getLocalEventId();

        ProcessingStatus getProcessingStatus();

        Instant getCreatedAt();
    }

    /** Id only, so duplicate checks never decrypt the payload. */
    @Query("select e.id from RawFinancialEvent e where e.deviceId = :deviceId and e.sourceType = :type "
            + "and e.sourceEventId = :sourceEventId")
    Optional<Long> findIdBySource(@Param("deviceId") Long deviceId, @Param("type") SourceType type,
                                  @Param("sourceEventId") String sourceEventId);

    @Query("select e.id as id, e.localEventId as localEventId, e.processingStatus as processingStatus, "
            + "e.createdAt as createdAt from RawFinancialEvent e where e.id = :id and e.deviceId = :deviceId")
    Optional<StatusView> findStatusByIdAndDevice(@Param("id") Long id, @Param("deviceId") Long deviceId);
}
