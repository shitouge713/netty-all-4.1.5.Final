package learn.netty.demo;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;

/**
 * Created by lwz on 2017/11/15 14:20.
 */
public class ClientHanler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        System.out.println("received : [" + msg + "]");

        ctx.channel().attr(AttributeKey.valueOf("ChannelKey")).set(msg);
        ctx.channel().close();
    }
}