package com.example.tcploadtester.payload;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PayloadBuilderTest {

    @Test
    void buildLoginPayloadUsesNumericTxnNo() {
        String payload = PayloadBuilder.buildLoginPayload("TSD000001", "860937000000001", 177684913700L);
        assertTrue(payload.contains("\"msgType\":110"));
        assertTrue(payload.contains("\"devId\":\"TSD000001\""));
        assertTrue(payload.contains("\"imsi\":\"860937000000001\""));
        assertTrue(payload.contains("\"txnNo\":177684913700"));
    }

    @Test
    void buildAttributePayloadUsesStringTxnNoAndOriginalAttributeValue() {
        String payload = PayloadBuilder.buildAttributePayload("TSD000001", 1776849140522L);
        assertTrue(payload.contains("\"msgType\":310"));
        assertTrue(payload.contains("\"devId\":\"TSD000001\""));
        assertTrue(payload.contains("\"txnNo\":\"1776849140522\""));
        assertTrue(payload.contains("\"value\":\"24.498304\""));
    }
}
