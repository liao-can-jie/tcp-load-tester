package com.example.tcploadtester.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceIdentityAllocatorTest {

    @Test
    void allocateFirstDevice() {
        DeviceIdentityAllocator.DeviceIdentity identity = DeviceIdentityAllocator.allocate(1);
        assertEquals("TSD000001", identity.devId());
        assertEquals("860937000000001", identity.imsi());
    }

    @Test
    void allocateUpperBoundDevice() {
        DeviceIdentityAllocator.DeviceIdentity identity = DeviceIdentityAllocator.allocate(100000);
        assertEquals("TSD100000", identity.devId());
        assertEquals("860937000100000", identity.imsi());
    }

    @Test
    void rejectDeviceAboveUpperBound() {
        assertThrows(IllegalArgumentException.class, () -> DeviceIdentityAllocator.allocate(100001));
    }
}
