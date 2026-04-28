package com.example.tcploadtester.payload;

public final class PayloadBuilder {

    private PayloadBuilder() {
    }

    public static String buildLoginPayload(String devId, String imsi, long txnNo) {
        return "{\"msgType\":110,\"imsi\":\"" + imsi + "\",\"batteryStatus\":\"2\",\"hardVersion\":\"1.0.0\",\"softVersion\":\"1.1.3.0\",\"devId\":\"" + devId + "\",\"protocolVersion\":\"V1.0\",\"devType\":1,\"txnNo\":" + txnNo + "}";
    }

    public static String buildAttributePayload(String devId, long txnNo) {
        return "{\"msgType\":310,\"attrList\":[{\"id\":\"01101001\",\"value\":\"5\"},{\"id\":\"01113001\",\"value\":\"100\"},{\"id\":\"01102001\",\"value\":\"118.086998\"},{\"id\":\"01103001\",\"value\":\"24.498304\"},{\"id\":\"01106001\",\"value\":\"100\"},{\"id\":\"01109001\",\"value\":\"0\"},{\"id\":\"01110001\",\"value\":\"853\"},{\"id\":\"01108001\",\"value\":\"2\"}],\"devId\":\"" + devId + "\",\"txnNo\":\"" + txnNo + "\"}";
    }
}
