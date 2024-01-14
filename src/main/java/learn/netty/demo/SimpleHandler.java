package learn.netty.demo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;

/**
 * Created by lwz on 2017/11/15 14:00.
 */
public class SimpleHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        System.out.println("-------channelRegistered-------");
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        System.out.println("received: [" + msg + "]");

        ctx.channel().writeAndFlush("ÀîËÄ\r\n");
    }
}
