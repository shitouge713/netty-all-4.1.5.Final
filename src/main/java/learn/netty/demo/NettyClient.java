package learn.netty.demo;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.DelimiterBasedFrameDecoder;
import io.netty.handler.codec.Delimiters;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.util.AttributeKey;

/**
 * Created by lwz on 2017/11/15 14:13.
 */
public class NettyClient {

    public static void main(String[] args) {

        NioEventLoopGroup nioEventLoopGroup = new NioEventLoopGroup();

        try {
            Bootstrap bootstrap = new Bootstrap();
            bootstrap //
                .group(nioEventLoopGroup) //
                .option(ChannelOption.SO_KEEPALIVE, true) //
                .channel(NioSocketChannel.class) //
                .handler(new ChannelInitializer<SocketChannel>() { //
                    protected void initChannel(SocketChannel ch) throws Exception {
                        ch.pipeline() //
                            .addLast(new DelimiterBasedFrameDecoder(Integer.MAX_VALUE, Delimiters.lineDelimiter()[0])) //
                            .addLast(new StringEncoder()) //
                            .addLast(new StringDecoder()) //
                            .addLast(new ClientHanler());
                    }
                });

            ChannelFuture future = bootstrap.connect("localhost", 8080).sync();

            //            future.channel().writeAndFlush(Unpooled.copiedBuffer("张三李四\r\n", CharsetUtil.UTF_8));
            future.channel().write("张三");
            Thread.sleep(5000);
            future.channel().writeAndFlush("\r\n");

            future.channel().closeFuture().sync();

            Object channelKey = future.channel().attr(AttributeKey.valueOf("ChannelKey")).get();
            System.out.println(channelKey);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            nioEventLoopGroup.shutdownGracefully();
        }
    }
}
