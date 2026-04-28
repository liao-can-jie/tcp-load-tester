package com.example.tcploadtester.matcher;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AckMessageMatcherTest {

    @Test
    void extractAckMessagesWithQuotedAndUnquotedTxnNo() {
        String input = "xxx{\"msgType\":111,\"devId\":\"TSD000001\",\"txnNo\":1776849137000}yyy{\"msgType\":311,\"devId\":\"TSD000001\",\"txnNo\":\"1776849140522\"}";
        List<AckMessageMatcher.AckMessage> messages = AckMessageMatcher.extract(input);

        assertEquals(2, messages.size());
        assertEquals(111, messages.get(0).msgType());
        assertEquals("1776849137000", messages.get(0).txnNo());
        assertEquals(311, messages.get(1).msgType());
        assertEquals("1776849140522", messages.get(1).txnNo());
    }

    @Test
    void matchExpectedAckByMsgTypeAndTxnNo() {
        AckMessageMatcher.AckMessage ack = new AckMessageMatcher.AckMessage(311, "1776849140522");
        assertTrue(AckMessageMatcher.matches(ack, 311, "1776849140522"));
    }

    @Test
    void extractOnlyQuotedTxnNoMessage() {
        String input = "{\"msgType\":311,\"devId\":\"TSD000001\",\"txnNo\":\"1776849140522\"}";
        List<AckMessageMatcher.AckMessage> messages = AckMessageMatcher.extract(input);
        assertEquals(1, messages.size());
        assertEquals(311, messages.get(0).msgType());
        assertEquals("1776849140522", messages.get(0).txnNo());
    }
}
