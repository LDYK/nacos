/*
 * Copyright 1999-2020 Alibaba Group Holding Ltd.
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

package com.alibaba.nacos.naming.core.v2.cleaner;

import com.alibaba.nacos.common.notify.NotifyCenter;
import com.alibaba.nacos.naming.core.v2.ServiceManager;
import com.alibaba.nacos.naming.core.v2.event.metadata.MetadataEvent;
import com.alibaba.nacos.naming.core.v2.index.ClientServiceIndexesManager;
import com.alibaba.nacos.naming.core.v2.index.ServiceStorage;
import com.alibaba.nacos.naming.core.v2.pojo.Service;
import com.alibaba.nacos.naming.misc.GlobalConfig;
import com.alibaba.nacos.naming.misc.GlobalExecutor;
import com.alibaba.nacos.naming.misc.Loggers;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Empty service auto cleaner for v2.x.
 *
 * @author xiweng.yy
 */
@Component
public class EmptyServiceAutoCleanerV2 extends AbstractNamingCleaner {
    
    private static final String EMPTY_SERVICE = "emptyService";
    
    private final ClientServiceIndexesManager clientServiceIndexesManager;
    
    private final ServiceStorage serviceStorage;
    
    public EmptyServiceAutoCleanerV2(ClientServiceIndexesManager clientServiceIndexesManager,
            ServiceStorage serviceStorage) {
        this.clientServiceIndexesManager = clientServiceIndexesManager;
        this.serviceStorage = serviceStorage;
        //60秒执行一次服务的清理工作
        GlobalExecutor.scheduleExpiredClientCleaner(this, TimeUnit.SECONDS.toMillis(30),
                GlobalConfig.getEmptyServiceCleanInterval(), TimeUnit.MILLISECONDS);
        
    }
    
    @Override
    public String getType() {
        return EMPTY_SERVICE;
    }

    //服务清理流程
    @Override
    public void doClean() {
        ServiceManager serviceManager = ServiceManager.getInstance();
        // Parallel flow opening threshold
        int parallelSize = 100;
        //轮询所有的命名空间
        for (String each : serviceManager.getAllNamespaces()) {
            //每个命名空间下的服务列表
            Set<Service> services = serviceManager.getSingletons(each);
            //大于100个用并行流处理
            Stream<Service> stream = services.size() > parallelSize ? services.parallelStream() : services.stream();
            //对每一个服务执行清理操作
            stream.forEach(this::cleanEmptyService);
        }
    }
    
    private void cleanEmptyService(Service service) {
        Collection<String> registeredService = clientServiceIndexesManager.getAllClientsRegisteredService(service);
        //满足条件：没有找到服务实例且服务更新已过期
        if (registeredService.isEmpty() && isTimeExpired(service)) {
            Loggers.SRV_LOG.warn("namespace : {}, [{}] services are automatically cleaned", service.getNamespace(),
                    service.getGroupedServiceName());
            //分别移除clientServiceIndexesManager ServiceManager ServiceStorage
            clientServiceIndexesManager.removePublisherIndexesByEmptyService(service);
            ServiceManager.getInstance().removeSingleton(service);
            serviceStorage.removeData(service);
            NotifyCenter.publishEvent(new MetadataEvent.ServiceMetadataEvent(service, true));
        }
    }
    
    private boolean isTimeExpired(Service service) {
        long currentTimeMillis = System.currentTimeMillis();
        return currentTimeMillis - service.getLastUpdatedTime() >= GlobalConfig.getEmptyServiceExpiredTime();
    }
}
