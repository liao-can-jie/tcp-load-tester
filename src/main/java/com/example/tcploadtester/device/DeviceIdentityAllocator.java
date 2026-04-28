package com.example.tcploadtester.device;

public final class DeviceIdentityAllocator {

    private DeviceIdentityAllocator() {
    }

    public static DeviceIdentity allocate(int deviceIndex) {
        if (deviceIndex <= 0 || deviceIndex > 100000) {
            throw new IllegalArgumentException("deviceIndex must be between 1 and 100000");
        }
        String devId = String.format("BT107204012MXYD%06d", deviceIndex);
        String imsi = "860937" + String.format("%09d", deviceIndex);
        return new DeviceIdentity(devId, imsi);
    }

    public record DeviceIdentity(String devId, String imsi) {
    }
}
