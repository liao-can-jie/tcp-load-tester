package com.example.tcploadtester.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceIdentityAllocatorTest {

    @Test
    void allocateFirstDeviceAppendsPublicIpDigits() {
        DeviceIdentityAllocator allocator = new DeviceIdentityAllocator("139.199.15.22");

        DeviceIdentityAllocator.DeviceIdentity identity = allocator.allocate(1);

        assertEquals("BT107204012MXYD1391991522000001", identity.devId());
        assertEquals("860937000000001", identity.imsi());
    }

    @Test
    void allocateUpperBoundDeviceKeepsZeroPaddedIndex() {
        DeviceIdentityAllocator allocator = new DeviceIdentityAllocator("139.199.15.22");

        DeviceIdentityAllocator.DeviceIdentity identity = allocator.allocate(100000);

        assertEquals("BT107204012MXYD1391991522100000", identity.devId());
        assertEquals("860937000100000", identity.imsi());
    }

    @Test
    void rejectDeviceAboveUpperBound() {
        DeviceIdentityAllocator allocator = new DeviceIdentityAllocator("139.199.15.22");

        assertThrows(IllegalArgumentException.class, () -> allocator.allocate(100001));
    }
}
