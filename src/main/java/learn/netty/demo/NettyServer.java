package learn.netty.demo;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;

/**
 * Created by lwz on 2017/11/15 13:54.
 */
public class NettyServer {

    public static void main(String[] args) {

        NioEventLoopGroup parentGroup = new NioEventLoopGroup();
        NioEventLoopGroup childGroup = new NioEventLoopGroup(4);

        try {
            ServerBootstrap serverBootstrap = new ServerBootstrap();
            serverBootstrap //
                .group(parentGroup, childGroup) //
                .option(ChannelOption.SO_BACKLOG, 10) //
                .option(ChannelOption.SO_KEEPALIVE, true) //
                .childOption(ChannelOption.SO_BACKLOG, 10) //
                .childOption(ChannelOption.SO_KEEPALIVE, true) //
                .channel(NioServerSocketChannel.class) //
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline() //
                            .addLast(new DelimiterBasedFrameDecoder(Integer.MAX_VALUE, Delimiters.lineDelimiter()[0])) //
                            .addLast(new StringDecoder()) //
                            .addLast(new StringEncoder()) //
                            .addLast(new SimpleHandler());
                    }
                });

            ChannelFuture future = serverBootstrap.bind(8080).sync();
            future.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            e.printStackTrace();
        } finally {
            parentGroup.shutdownGracefully();
            childGroup.shutdownGracefully();
        }
    }
}
