package com.alibaba.nacos.client.naming.spring;

/**
 *

 调用链：spring.factory —> NacosServiceRegistryAutoConfiguration —> NacosAutoServiceRegistration —> AbstractAutoServiceRegistration —> NacosServiceRegistry —> NacosNamingService

public class NacosServiceRegistry {


    ...
    // nacos 配置属性
    private final NacosDiscoveryProperties nacosDiscoveryProperties;

    // 可以理解为 nacos 客户端统一的服务管理器
    private final NamingService namingService;

    ...

    @Override
    public void register(Registration registration) {

        if (StringUtils.isEmpty(registration.getServiceId())) {
            log.warn("No service to register for nacos client...");
            return;
        }

        // 如果没有配置属性【spring.cloud.nacos.discovery.service】
        // 就使用【spring.application.name】
        String serviceId = registration.getServiceId();

        // 创建要注册的实例
        // 首先通过 NacosRegistration  和  nacosDiscoveryProperties[配置文件相关属性] 构建一个Instance 对象
        Instance instance = new Instance();
        instance.setIp(registration.getHost());
        instance.setPort(registration.getPort());
        instance.setWeight(nacosDiscoveryProperties.getWeight());
        instance.setClusterName(nacosDiscoveryProperties.getClusterName());
        // 扩展数据
        instance.setMetadata(registration.getMetadata());

		...
        // 调用namingService注册服务
        namingService.registerInstance(serviceId, instance);
        log.info("nacos registry, {} {}:{} register finished", serviceId,
                instance.getIp(), instance.getPort());
		...
    }

    //服务下线功能
    @Override
    public void deregister(Registration registration) {

		...

        NamingService namingService = nacosDiscoveryProperties.namingServiceInstance();
        String serviceId = registration.getServiceId();

		...
        namingService.deregisterInstance(serviceId, registration.getHost(),
                registration.getPort(), nacosDiscoveryProperties.getClusterName());
		...
    }

    @Override
    public void close() {

    }

    @Override
    public void setStatus(Registration registration, String status) {
        // nacos doesn't support set status of a particular registration.
    }

    @Override
    public <T> T getStatus(Registration registration) {
        // nacos doesn't support query status of a particular registration.
        return null;
    }


}

 */
