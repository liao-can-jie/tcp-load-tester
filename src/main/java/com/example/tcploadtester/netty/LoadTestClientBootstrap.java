package com.example.tcploadtester.netty;

import com.example.tcploadtester.config.LoadTestConfig;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

public final class LoadTestClientBootstrap {

    private final EventLoopGroup eventLoopGroup;

    public LoadTestClientBootstrap(LoadTestConfig config) {
        this.eventLoopGroup = new NioEventLoopGroup(config.eventLoopThreads());
    }

    public Bootstrap create() {
        return new Bootstrap()
                .group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.SO_REUSEADDR, true)
                .option(ChannelOption.SO_RCVBUF, 32 * 1024)
                .option(ChannelOption.SO_SNDBUF, 32 * 1024)
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(8 * 1024, 32 * 1024));
    }

    public EventLoopGroup eventLoopGroup() {
        return eventLoopGroup;
    }

    public void shutdown() {
        eventLoopGroup.shutdownGracefully();
    }
}
