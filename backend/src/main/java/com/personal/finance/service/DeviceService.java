package com.personal.finance.service;

import com.personal.finance.common.Hashing;
import com.personal.finance.config.AppProperties;
import com.personal.finance.dto.RegisterRequest;
import com.personal.finance.dto.RegisterResponse;
import com.personal.finance.entity.SyncDevice;
import com.personal.finance.exception.InvalidPairingCodeException;
import com.personal.finance.repository.SyncDeviceRepository;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {

    private final SyncDeviceRepository devices;
    private final AppProperties properties;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public DeviceService(SyncDeviceRepository devices, AppProperties properties, Clock clock) {
        this.devices = devices;
        this.properties = properties;
        this.clock = clock;
    }

    /** The raw token is returned once; only its SHA-256 is stored. */
    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        if (!Hashing.secretsMatch(request.pairingCode(), properties.security().pairingCode())) {
            throw new InvalidPairingCodeException();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        SyncDevice device = devices.save(new SyncDevice(
                request.deviceName(), request.appVersion(), Hashing.sha256Hex(token), clock.instant()));
        return new RegisterResponse(device.getId(), token);
    }

    @Transactional
    public Instant heartbeat(Long deviceId) {
        Instant now = clock.instant();
        devices.touch(deviceId, now);
        return now;
    }

    @Transactional
    public void revoke(Long deviceId) {
        devices.revoke(deviceId, clock.instant());
    }
}
