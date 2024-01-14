/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.bootstrap;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.ServerChannel;
import io.netty.util.AttributeKey;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.TimeUnit;

/**
 * {@link Bootstrap} sub-class which allows easy bootstrap of {@link ServerChannel}
 *
 */
public class ServerBootstrap extends AbstractBootstrap<ServerBootstrap, ServerChannel> {

    private static final InternalLogger         logger       = InternalLoggerFactory.getInstance(ServerBootstrap.class);

    private final Map<ChannelOption<?>, Object> childOptions = new LinkedHashMap<ChannelOption<?>, Object>();
    private final Map<AttributeKey<?>, Object>  childAttrs   = new LinkedHashMap<AttributeKey<?>, Object>();
    private final ServerBootstrapConfig         config       = new ServerBootstrapConfig(this);
    private volatile EventLoopGroup             childGroup;
    private volatile ChannelHandler             childHandler;

    public ServerBootstrap() {
    }

    private ServerBootstrap(ServerBootstrap bootstrap) {
        super(bootstrap);
        childGroup = bootstrap.childGroup;
        childHandler = bootstrap.childHandler;
        synchronized (bootstrap.childOptions) {
            childOptions.putAll(bootstrap.childOptions);
        }
        synchronized (bootstrap.childAttrs) {
            childAttrs.putAll(bootstrap.childAttrs);
        }
    }

    /**
     * Specify the {@link EventLoopGroup} which is used for the parent (acceptor) and the child (client).
     */
    @Override
    public ServerBootstrap group(EventLoopGroup group) {
        return group(group, group);
    }

    /**
     * Set the {@link EventLoopGroup} for the parent (acceptor) and the child (client). These
     * {@link EventLoopGroup}'s are used to handle all the events and IO for {@link ServerChannel} and
     * {@link Channel}'s.
     */
    public ServerBootstrap group(EventLoopGroup parentGroup, EventLoopGroup childGroup) {
        super.group(parentGroup);
        if (childGroup == null) {
            throw new NullPointerException("childGroup");
        }
        if (this.childGroup != null) {
            throw new IllegalStateException("childGroup set already");
        }
        this.childGroup = childGroup;
        return this;
    }

    /**
     * Allow to specify a {@link ChannelOption} which is used for the {@link Channel} instances once they get created
     * (after the acceptor accepted the {@link Channel}). Use a value of {@code null} to remove a previous set
     * {@link ChannelOption}.
     */
    public <T> ServerBootstrap childOption(ChannelOption<T> childOption, T value) {
        if (childOption == null) {
            throw new NullPointerException("childOption");
        }
        if (value == null) {
            synchronized (childOptions) {
                childOptions.remove(childOption);
            }
        } else {
            synchronized (childOptions) {
                childOptions.put(childOption, value);
            }
        }
        return this;
    }

    /**
     * Set the specific {@link AttributeKey} with the given value on every child {@link Channel}. If the value is
     * {@code null} the {@link AttributeKey} is removed
     */
    public <T> ServerBootstrap childAttr(AttributeKey<T> childKey, T value) {
        if (childKey == null) {
            throw new NullPointerException("childKey");
        }
        if (value == null) {
            childAttrs.remove(childKey);
        } else {
            childAttrs.put(childKey, value);
        }
        return this;
    }

    /**
     * Set the {@link ChannelHandler} which is used to serve the request for the {@link Channel}'s.
     */
    public ServerBootstrap childHandler(ChannelHandler childHandler) {
        if (childHandler == null) {
            throw new NullPointerException("childHandler");
        }
        this.childHandler = childHandler;
        return this;
    }

    @Override
    void init(Channel channel) throws Exception {
        final Map<ChannelOption<?>, Object> options = options0();
        // 赋 channel 上配置的 options 给 channel 持有的 ChannelConfig 对象
        synchronized (options) {
            channel.config().setOptions(options);
        }

        final Map<AttributeKey<?>, Object> attrs = attrs0();
        // attach channel 上配置的 attrs 给 channel 
        synchronized (attrs) {
            for (Entry<AttributeKey<?>, Object> e : attrs.entrySet()) {
                @SuppressWarnings("unchecked")
                AttributeKey<Object> key = (AttributeKey<Object>) e.getKey();
                channel.attr(key).set(e.getValue());
            }
        }
        // 获取channel的pipeline
        ChannelPipeline p = channel.pipeline();

        // 将serverBootstrap中所有以child开头的属性写入到局部变量，
        // 然后将它们初始化到childChannel中
        final EventLoopGroup currentChildGroup = childGroup;
        final ChannelHandler currentChildHandler = childHandler;
        final Entry<ChannelOption<?>, Object>[] currentChildOptions;
        final Entry<AttributeKey<?>, Object>[] currentChildAttrs;
        synchronized (childOptions) {
            currentChildOptions = childOptions.entrySet().toArray(newOptionArray(childOptions.size()));
        }
        synchronized (childAttrs) {
            currentChildAttrs = childAttrs.entrySet().toArray(newAttrArray(childAttrs.size()));
        }

        //初始化处理器 ChannelInitializer,主要的操作是处理 childGroup 里面的 channel 的初始化操作
        p.addLast(new ChannelInitializer<Channel>() {
            @Override
            public void initChannel(Channel ch) throws Exception {
                final ChannelPipeline pipeline = ch.pipeline();
                ChannelHandler handler = config.handler();
                if (handler != null) {
                    pipeline.addLast(handler);
                }

                // We add this handler via the EventLoop as the user may have used a ChannelInitializer as handler.
                // In this case the initChannel(...) method will only be called after this method returns. Because
                // of this we need to ensure we add our handler in a delayed fashion so all the users handler are
                // placed in front of the ServerBootstrapAcceptor.
                ch.eventLoop().execute(new Runnable() {
                    @Override
                    public void run() {
                        // 将ServerBootstrapAcceptor处理器添加到pipeline
                        // ServerBootstrapAcceptor处理器用于接收ServerBootstrap中的属性值，
                        // 我们通常称其为连接处理器
                        pipeline.addLast(new ServerBootstrapAcceptor(currentChildGroup, currentChildHandler, currentChildOptions, currentChildAttrs));
                    }
                });
            }
        });
    }

    @Override
    public ServerBootstrap validate() {
        super.validate();
        if (childHandler == null) {
            throw new IllegalStateException("childHandler not set");
        }
        if (childGroup == null) {
            logger.warn("childGroup is not set. Using parentGroup instead.");
            childGroup = config.group();
        }
        return this;
    }

    @SuppressWarnings("unchecked")
    private static Entry<ChannelOption<?>, Object>[] newOptionArray(int size) {
        return new Entry[size];
    }

    @SuppressWarnings("unchecked")
    private static Entry<AttributeKey<?>, Object>[] newAttrArray(int size) {
        return new Entry[size];
    }

    /**
     * Netty处理客户端连接的核心逻辑所在类
     * 作用：
     * 初始化客户端NioSocketChannel
     * 将客户端NioSocketChannel注册到Sub Reactor Group中
     * 监听OP_READ事件
     * 在Netty启动并开始初始化NioServerSocketChannel的时候将ServerBootstrapAcceptor的创建初始化工作封装成异步任务，
     * 然后在NioServerSocketChannel注册到Main Reactor中成功后执行
     * 这个处理器会接收客户端的连接并处理
     */
    private static class ServerBootstrapAcceptor extends ChannelInboundHandlerAdapter {

        private final EventLoopGroup                    childGroup;
        private final ChannelHandler                    childHandler;
        private final Entry<ChannelOption<?>, Object>[] childOptions;
        private final Entry<AttributeKey<?>, Object>[]  childAttrs;

        ServerBootstrapAcceptor(EventLoopGroup childGroup, ChannelHandler childHandler, Entry<ChannelOption<?>, Object>[] childOptions, Entry<AttributeKey<?>, Object>[] childAttrs) {
            this.childGroup = childGroup;
            this.childHandler = childHandler;
            this.childOptions = childOptions;
            this.childAttrs = childAttrs;
        }

        /**
         * 该方法主要做了两件事：
         * 初始化 childChannel
         * 将成功从 client 连接过来的 channel 注册到 selector 上。
         * @param ctx
         * @param msg
         */
        @Override
        @SuppressWarnings("unchecked")
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            final Channel child = (Channel) msg;
            //设置处理器
            //向客户端NioSocketChannel的pipeline中添加在启动配置类ServerBootstrap中配置的ChannelHandler
            child.pipeline().addLast(childHandler);
            //下面设置一些参数
            for (Entry<ChannelOption<?>, Object> e : childOptions) {
                try {
                    //利用配置的属性初始化客户端NioSocketChannel
                    if (!child.config().setOption((ChannelOption<Object>) e.getKey(), e.getValue())) {
                        logger.warn("Unknown channel option: " + e);
                    }
                } catch (Throwable t) {
                    logger.warn("Failed to set a channel option: " + child, t);
                }
            }

            for (Entry<AttributeKey<?>, Object> e : childAttrs) {
                child.attr((AttributeKey<Object>) e.getKey()).set(e.getValue());
            }

            try {
                //这是比较重要的一些流程，其实就是把一个新的 eventLoop,在里面找到一个 selector 来和channel进行绑定，并设置一个线程监听绑定的channel的事件
                /**
                 * 1：在Sub Reactor线程组中选择一个Reactor绑定
                 * 2：将客户端SocketChannel注册到绑定的Reactor上
                 * 3：SocketChannel注册到sub reactor中的selector上
                 * 4：监听OP_READ事件
                 * */
                childGroup.register(child).addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) throws Exception {
                        if (!future.isSuccess()) {
                            forceClose(child, future.cause());
                        }
                    }
                });
            } catch (Throwable t) {
                forceClose(child, t);
            }
        }

        private static void forceClose(Channel child, Throwable t) {
            child.unsafe().closeForcibly();
            logger.warn("Failed to register an accepted channel: " + child, t);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            final ChannelConfig config = ctx.channel().config();
            if (config.isAutoRead()) {
                // stop accept new connections for 1 second to allow the channel to recover
                // See https://github.com/netty/netty/issues/1328
                config.setAutoRead(false);
                ctx.channel().eventLoop().schedule(new Runnable() {
                    @Override
                    public void run() {
                        config.setAutoRead(true);
                    }
                }, 1, TimeUnit.SECONDS);
            }
            // still let the exceptionCaught event flow through the pipeline to give the user
            // a chance to do something with it
            ctx.fireExceptionCaught(cause);
        }
    }

    @Override
    @SuppressWarnings("CloneDoesntCallSuperClone")
    public ServerBootstrap clone() {
        return new ServerBootstrap(this);
    }

    /**
     * Return the configured {@link EventLoopGroup} which will be used for the child channels or {@code null}
     * if non is configured yet.
     *
     * @deprecated Use {@link #config()} instead.
     */
    @Deprecated
    public EventLoopGroup childGroup() {
        return childGroup;
    }

    final ChannelHandler childHandler() {
        return childHandler;
    }

    final Map<ChannelOption<?>, Object> childOptions() {
        return copiedMap(childOptions);
    }

    final Map<AttributeKey<?>, Object> childAttrs() {
        return copiedMap(childAttrs);
    }

    @Override
    public final ServerBootstrapConfig config() {
        return config;
    }
}
