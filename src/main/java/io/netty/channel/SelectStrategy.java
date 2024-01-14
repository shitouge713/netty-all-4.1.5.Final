/*
 * Copyright 2016 The Netty Project
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
package io.netty.channel;

import io.netty.util.IntSupplier;

/**
 * Select strategy interface.
 *
 * Provides the ability to control the behavior of the select loop. For example a blocking select
 * operation can be delayed or skipped entirely if there are events to process immediately.
 */
public interface SelectStrategy {

    /**
     * Indicates a blocking select should follow.
     * 没有异步任务需要执行，Reactor线程可以安心的阻塞在Selector上等待IO就绪事件发生
     */
    int SELECT = -1;
    /**
     * Indicates the IO loop should be retried, no blocking select to follow directly.
     * 表示下一步应该重试IO循环，不执行阻塞的select操作。
     */
    int CONTINUE = -2;

    /**
     * The {@link SelectStrategy} can be used to steer the outcome of a potential select
     * call.
     *
     * @param selectSupplier The supplier with the result of a select result.
     * @param hasTasks true if tasks are waiting to be processed.
     * @return {@link #SELECT} if the next step should be blocking select {@link #CONTINUE} if
     *         the next step should be to not select but rather jump back to the IO loop and try
     *         again. Any value >= 0 is treated as an indicator that work needs to be done.
     * 接受一个IntSupplier类型的参数selectSupplier和一个boolean类型的参数hasTasks，用于确定下一步的操作
     * 如果返回值为SELECT，则表示下一步应该执行阻塞的select操作；
     * 如果返回值为CONTINUE，则表示下一步应该重试IO循环；
     * 如果返回值大于等于0，则表示需要执行一些工作。
     */
    int calculateStrategy(IntSupplier selectSupplier, boolean hasTasks) throws Exception;
}
