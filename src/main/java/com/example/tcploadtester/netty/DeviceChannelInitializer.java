package com.example.tcploadtester.netty;

import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.util.CharsetUtil;

import java.util.concurrent.TimeUnit;

public final class DeviceChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final DeviceMessageHandler handler;
    private final int idleTimeoutSeconds;

    public DeviceChannelInitializer(DeviceMessageHandler handler, int idleTimeoutSeconds) {
        this.handler = handler;
        this.idleTimeoutSeconds = idleTimeoutSeconds;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ch.pipeline().addLast(new IdleStateHandler(idleTimeoutSeconds, 0, 0, TimeUnit.SECONDS));
        ch.pipeline().addLast(new StringDecoder(CharsetUtil.UTF_8));
        ch.pipeline().addLast(new StringEncoder(CharsetUtil.UTF_8));
        ch.pipeline().addLast(handler);
    }
}
