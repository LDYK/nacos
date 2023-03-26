/*
 * Copyright 1999-2018 Alibaba Group Holding Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.nacos.common.task.engine;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.common.task.AbstractExecuteTask;
import com.alibaba.nacos.common.task.NacosTaskProcessor;
import com.alibaba.nacos.common.utils.ThreadUtils;
import org.slf4j.Logger;

import java.util.Collection;

/**
 * Nacos execute task execute engine.
 *
 * @author xiweng.yy
 */
public class NacosExecuteTaskExecuteEngine extends AbstractNacosTaskExecuteEngine<AbstractExecuteTask> {
    
    private final TaskExecuteWorker[] executeWorkers;
    
    public NacosExecuteTaskExecuteEngine(String name, Logger logger) {
        this(name, logger, ThreadUtils.getSuitableThreadCount(1));
    }
    
    public NacosExecuteTaskExecuteEngine(String name, Logger logger, int dispatchWorkerCount) {
        super(logger);
        executeWorkers = new TaskExecuteWorker[dispatchWorkerCount];
        for (int mod = 0; mod < dispatchWorkerCount; ++mod) {
            executeWorkers[mod] = new TaskExecuteWorker(name, mod, dispatchWorkerCount, getEngineLog());
        }
    }
    
    @Override
    public int size() {
        int result = 0;
        for (TaskExecuteWorker each : executeWorkers) {
            result += each.pendingTaskCount();
        }
        return result;
    }
    
    @Override
    public boolean isEmpty() {
        return 0 == size();
    }

    // change、add执行逻辑
    @Override
    public void addTask(Object tag, AbstractExecuteTask task) {
        NacosTaskProcessor processor = getProcessor(tag);
        if (null != processor) {
            processor.process(task);
            return;
        }
        // 将任务DistroSyncChangeTask、PushExecuteTask等任务交给任务执行处理器TaskExecuteWorker的队列
        TaskExecuteWorker worker = getWorker(tag);
        // TaskExecuteWorker在初始化的时候会启动一个守护线程InnerWorker
        // InnerWorker不断从任务队列取出任务，然后执行不同任务的run方法
        // 例：制定任务的run方法执行DistroClientTransportAgent的数据同步方法
        //     首先是创建同步数据请求DistroDataRequest，然后向任务指定的目标Nacos服务节点发送同步
        //     目标Nacos服务DistroDataRequest接收到请求之后，由 DistroDataRequestHandler 来处理，其实就是把同步过来的数据加入到当前节点的数据缓存
        worker.process(task);
    }
    
    private TaskExecuteWorker getWorker(Object tag) {
        int idx = (tag.hashCode() & Integer.MAX_VALUE) % workersCount();
        return executeWorkers[idx];
    }
    
    private int workersCount() {
        return executeWorkers.length;
    }
    
    @Override
    public AbstractExecuteTask removeTask(Object key) {
        throw new UnsupportedOperationException("ExecuteTaskEngine do not support remove task");
    }
    
    @Override
    public Collection<Object> getAllTaskKeys() {
        throw new UnsupportedOperationException("ExecuteTaskEngine do not support get all task keys");
    }
    
    @Override
    public void shutdown() throws NacosException {
        for (TaskExecuteWorker each : executeWorkers) {
            each.shutdown();
        }
    }
    
    /**
     * Get workers status.
     *
     * @return workers status string
     */
    public String workersStatus() {
        StringBuilder sb = new StringBuilder();
        for (TaskExecuteWorker worker : executeWorkers) {
            sb.append(worker.status()).append('\n');
        }
        return sb.toString();
    }
}
