package com.personal.finance.repository;

import com.personal.finance.entity.SyncDevice;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SyncDeviceRepository extends JpaRepository<SyncDevice, Long> {

    Optional<SyncDevice> findByTokenHashAndRevokedAtIsNull(String tokenHash);

    @Modifying
    @Query("update SyncDevice d set d.lastSeenAt = :now where d.id = :id")
    int touch(@Param("id") Long id, @Param("now") Instant now);

    @Modifying
    @Query("update SyncDevice d set d.revokedAt = :now where d.id = :id and d.revokedAt is null")
    int revoke(@Param("id") Long id, @Param("now") Instant now);
}
