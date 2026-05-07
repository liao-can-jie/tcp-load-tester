package com.example.tcploadtester.device;

import java.util.Objects;

public final class DeviceIdentityAllocator {

    private final DeviceCounter deviceCounter;

    public DeviceIdentityAllocator(DeviceCounter deviceCounter) {
        this.deviceCounter = Objects.requireNonNull(deviceCounter, "deviceCounter must not be null");
    }

    public DeviceIdentity allocate() {
        long deviceIndex = deviceCounter.nextDeviceIndex();
        validateDeviceIndex(deviceIndex);
        String devId = String.format("BT107204012MXYD%06d", deviceIndex);
        String imsi = "860937" + String.format("%09d", deviceIndex);
        return new DeviceIdentity(devId, imsi);
    }

    public void reset() {
        deviceCounter.reset();
    }

    public void close() {
        deviceCounter.close();
    }

    private static void validateDeviceIndex(long deviceIndex) {
        if (deviceIndex <= 0 || deviceIndex > 100000) {
            throw new IllegalArgumentException("deviceIndex must be between 1 and 100000");
        }
    }

    public interface DeviceCounter {
        long nextDeviceIndex();

        void reset();

        default void close() {
        }
    }

    public record DeviceIdentity(String devId, String imsi) {
    }
}
