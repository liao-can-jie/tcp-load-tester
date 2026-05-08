package com.example.tcploadtester.device;

import java.util.Objects;

public final class DeviceIdentityAllocator {

    private final String publicIpDigits;

    public DeviceIdentityAllocator(String publicIp) {
        Objects.requireNonNull(publicIp, "publicIp must not be null");
        if (publicIp.isBlank()) {
            throw new IllegalArgumentException("publicIp must not be blank");
        }
        if (!publicIp.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            throw new IllegalArgumentException("publicIp must be an IPv4 address");
        }
        this.publicIpDigits = publicIp.replace(".", "");
    }

    public DeviceIdentity allocate(int deviceIndex) {
        validateDeviceIndex(deviceIndex);
        String devId = "BT107204012MXYD" + publicIpDigits + String.format("%06d", deviceIndex);
        String imsi = "860937" + String.format("%09d", deviceIndex);
        return new DeviceIdentity(devId, imsi);
    }

    private static void validateDeviceIndex(int deviceIndex) {
        if (deviceIndex <= 0 || deviceIndex > 100000) {
            throw new IllegalArgumentException("deviceIndex must be between 1 and 100000");
        }
    }

    public record DeviceIdentity(String devId, String imsi) {
    }
}
