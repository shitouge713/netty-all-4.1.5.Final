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
package io.netty.channel.nio;

import io.netty.channel.Channel;
import io.netty.channel.ChannelConfig;
import io.netty.channel.ChannelOutboundBuffer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;

import java.io.IOException;
import java.net.PortUnreachableException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link AbstractNioChannel} base class for {@link Channel}s that operate on messages.
 */
public abstract class AbstractNioMessageChannel extends AbstractNioChannel {
    boolean inputShutdown;

    /**
     * @see {@link AbstractNioChannel#AbstractNioChannel(Channel, SelectableChannel, int)}
     */
    protected AbstractNioMessageChannel(Channel parent, SelectableChannel ch, int readInterestOp) {
        super(parent, ch, readInterestOp);
    }

    @Override
    protected AbstractNioUnsafe newUnsafe() {
        return new NioMessageUnsafe();
    }

    @Override
    protected void doBeginRead() throws Exception {
        if (inputShutdown) {
            return;
        }
        super.doBeginRead();
    }

    private final class NioMessageUnsafe extends AbstractNioUnsafe {
        //存放连接建立后，创建的客户端SocketChannel
        private final List<Object> readBuf = new ArrayList<Object>();

        @Override
        public void read() {
            //断言：必须在Main Reactor线程中执行
            assert eventLoop().inEventLoop();
            //注意下面的config和pipeline都是服务端ServerSocketChannel中的
            final ChannelConfig config = config();
            final ChannelPipeline pipeline = pipeline();
            //创建接收数据Buffer分配器（用于分配容量大小合适的byteBuffer用来容纳接收数据）
            //在接收连接的场景中，这里的allocHandle只是用于控制read loop的循环读取创建连接的次数。
            final RecvByteBufAllocator.Handle allocHandle = unsafe().recvBufAllocHandle();
            allocHandle.reset(config);

            boolean closed = false;
            Throwable exception = null;
            try {
                try {
                    /**
                     * 循环接收客户端连接，在read()中接收到的客户端连接全部暂存在List<Object> readBuf集合中
                     * 退出read loop的两个条件：
                     * 1、在限定的16次读取中，已经没有新的客户端连接要接收了。退出循环。
                     * 2、从NioServerSocketChannel中读取客户端连接的次数达到了16次，无论此时是否还有客户端连接都需要退出循环。
                     */
                    do {
                        //底层调用NioServerSocketChannel -> doReadMessages 创建客户端SocketChannel
                        //底层会调用到JDK NIO ServerSocketChannel的accept方法，从内核全连接队列中取出客户端连接
                        //localRead表示接收到了多少客户端连接
                        //客户端连接通过accept方法只会一个一个的接收，所以这里的localRead正常情况下都会返回1，当localRead <= 0时意味着已经没有新的客户端连接可以接收了
                        int localRead = doReadMessages(readBuf);
                        //已无新的连接可接收则退出read loop
                        if (localRead == 0) {
                            break;
                        }
                        if (localRead < 0) {
                            closed = true;
                            break;
                        }
                        //统计在当前事件循环中已经读取到得Message数量（创建连接的个数）
                        allocHandle.incMessagesRead(localRead);
                    //判断是否已经读满16次
                    } while (allocHandle.continueReading());
                } catch (Throwable t) {
                    exception = t;
                }

                //开始遍历readBuf
                int size = readBuf.size();
                for (int i = 0; i < size; i ++) {
                    readPending = false;
                    //在NioServerSocketChannel对应的pipeline中传播ChannelRead事件
                    //io.netty.bootstrap.ServerBootstrap.ServerBootstrapAcceptor.channelRead
                    //初始化客户端SocketChannel，并将其绑定到Sub Reactor线程组中的一个Reactor上
                    //此后sub reactor就开始监听处理客户端连接上的读写事件了
                    //此步骤实现客户端和服务端的连接对应关系
                    //调用 pipeline 上的 handler 来处理消息(IO事件)
                    pipeline.fireChannelRead(readBuf.get(i));
                }
                //清除本次accept 创建的客户端SocketChannel集合
                readBuf.clear();
                allocHandle.readComplete();
                //触发readComplete事件传播
                pipeline.fireChannelReadComplete();

                if (exception != null) {
                    if (exception instanceof IOException && !(exception instanceof PortUnreachableException)) {
                        // ServerChannel should not be closed even on IOException because it can often continue
                        // accepting incoming connections. (e.g. too many open files)
                        closed = !(AbstractNioMessageChannel.this instanceof ServerChannel);
                    }

                    pipeline.fireExceptionCaught(exception);
                }

                if (closed) {
                    inputShutdown = true;
                    if (isOpen()) {
                        close(voidPromise());
                    }
                }
            } finally {
                // Check if there is a readPending which was not processed yet.
                // This could be for two reasons:
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelRead(...) method
                // * The user called Channel.read() or ChannelHandlerContext.read() in channelReadComplete(...) method
                //
                // See https://github.com/netty/netty/issues/2254
                if (!readPending && !config.isAutoRead()) {
                    removeReadOp();
                }
            }
        }
    }

    @Override
    protected void doWrite(ChannelOutboundBuffer in) throws Exception {
        final SelectionKey key = selectionKey();
        final int interestOps = key.interestOps();

        for (;;) {
            Object msg = in.current();
            if (msg == null) {
                // Wrote all messages.
                if ((interestOps & SelectionKey.OP_WRITE) != 0) {
                    key.interestOps(interestOps & ~SelectionKey.OP_WRITE);
                }
                break;
            }
            try {
                boolean done = false;
                for (int i = config().getWriteSpinCount() - 1; i >= 0; i--) {
                    if (doWriteMessage(msg, in)) {
                        done = true;
                        break;
                    }
                }

                if (done) {
                    in.remove();
                } else {
                    // Did not write all messages.
                    if ((interestOps & SelectionKey.OP_WRITE) == 0) {
                        key.interestOps(interestOps | SelectionKey.OP_WRITE);
                    }
                    break;
                }
            } catch (IOException e) {
                if (continueOnWriteError()) {
                    in.remove(e);
                } else {
                    throw e;
                }
            }
        }
    }

    /**
     * Returns {@code true} if we should continue the write loop on a write error.
     */
    protected boolean continueOnWriteError() {
        return false;
    }

    /**
     * Read messages into the given array and return the amount which was read.
     */
    protected abstract int doReadMessages(List<Object> buf) throws Exception;

    /**
     * Write a message to the underlying {@link java.nio.channels.Channel}.
     *
     * @return {@code true} if and only if the message has been written
     */
    protected abstract boolean doWriteMessage(Object msg, ChannelOutboundBuffer in) throws Exception;
}
