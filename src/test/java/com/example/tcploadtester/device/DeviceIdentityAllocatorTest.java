package com.example.tcploadtester.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DeviceIdentityAllocatorTest {

    @Test
    void allocateFirstDevice() {
        DeviceIdentityAllocator allocator = new DeviceIdentityAllocator(new StubDeviceCounter(1));

        DeviceIdentityAllocator.DeviceIdentity identity = allocator.allocate();

        assertEquals("BT107204012MXYD000001", identity.devId());
        assertEquals("860937000000001", identity.imsi());
    }

    @Test
    void allocateUpperBoundDevice() {
        DeviceIdentityAllocator allocator = new DeviceIdentityAllocator(new StubDeviceCounter(100000));

        DeviceIdentityAllocator.DeviceIdentity identity = allocator.allocate();

        assertEquals("BT107204012MXYD100000", identity.devId());
        assertEquals("860937000100000", identity.imsi());
    }

    @Test
    void rejectDeviceAboveUpperBound() {
        DeviceIdentityAllocator allocator = new DeviceIdentityAllocator(new StubDeviceCounter(100001));

        assertThrows(IllegalArgumentException.class, allocator::allocate);
    }

    private record StubDeviceCounter(long deviceIndex) implements DeviceIdentityAllocator.DeviceCounter {
        @Override
        public long nextDeviceIndex() {
            return deviceIndex;
        }

        @Override
        public void reset() {
        }
    }
}
