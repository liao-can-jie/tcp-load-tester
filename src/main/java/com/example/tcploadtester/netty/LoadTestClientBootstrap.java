package com.example.tcploadtester.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

public final class LoadTestClientBootstrap {

    private final EventLoopGroup eventLoopGroup = new NioEventLoopGroup();

    public Bootstrap create() {
        return new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.SO_KEEPALIVE, true);
    }

    public EventLoopGroup eventLoopGroup() {
        return eventLoopGroup;
    }

    public void shutdown() {
        eventLoopGroup.shutdownGracefully();
    }
}
