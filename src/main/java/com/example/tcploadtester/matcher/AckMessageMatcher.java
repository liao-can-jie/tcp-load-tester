package com.example.tcploadtester.matcher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AckMessageMatcher {

    private static final Pattern ACK_PATTERN = Pattern.compile("\\{\"msgType\":(111|311),.*?\"txnNo\":(\"?\\d{13,15}\"?)\\}");

    private AckMessageMatcher() {
    }

    public static List<AckMessage> extract(String input) {
        List<AckMessage> messages = new ArrayList<>();
        int searchStart = 0;
        while (searchStart < input.length()) {
            int objectStart = input.indexOf('{', searchStart);
            if (objectStart < 0) {
                break;
            }
            int objectEnd = input.indexOf('}', objectStart);
            if (objectEnd < 0) {
                break;
            }
            String candidate = input.substring(objectStart, objectEnd + 1);
            Matcher matcher = ACK_PATTERN.matcher(candidate);
            if (matcher.matches()) {
                int msgType = Integer.parseInt(matcher.group(1));
                String txnNo = matcher.group(2).replace("\"", "");
                messages.add(new AckMessage(msgType, txnNo));
            }
            searchStart = objectEnd + 1;
        }
        return messages;
    }

    public static boolean matches(AckMessage ackMessage, int expectedMsgType, String expectedTxnNo) {
        return ackMessage.msgType() == expectedMsgType && ackMessage.txnNo().equals(expectedTxnNo);
    }

    public record AckMessage(int msgType, String txnNo) {
    }
}
