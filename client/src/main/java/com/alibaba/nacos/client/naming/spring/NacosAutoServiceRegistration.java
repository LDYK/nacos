package com.alibaba.nacos.client.naming.spring;

/**
 *

 调用链：spring.factory —> NacosServiceRegistryAutoConfiguration —> NacosAutoServiceRegistration —> AbstractAutoServiceRegistration —> NacosServiceRegistry —> NacosNamingService

 NacosRegistration 负责包装 配置属性文件里面的属性 。
 比spring cloud的 Registration  接口提供了更多的属性比如 registerWeight、Cluster、isRegisterEnabled等。
 因此你可以理解了 为什么 weight 性和  clusterName 要从 nacosDiscoveryProperties 去获而不是通过 registration 去获取
 instance.setWeight(nacosDiscoveryProperties.getWeight());
 instance.setClusterName(nacosDiscoveryProperties.getClusterName());

//服务启动后该类监听web server 初始化事件触发 服务注册流程
public class NacosAutoServiceRegistration extends AbstractAutoServiceRegistration<Registration> {

        //包装了服务注册基本信息[注册信息其属性大部分来源于配置文件]
        private NacosRegistration registration;

        public NacosAutoServiceRegistration(ServiceRegistry<Registration> serviceRegistry,
                                            AutoServiceRegistrationProperties autoServiceRegistrationProperties,
                                            NacosRegistration registration) {
            super(serviceRegistry, autoServiceRegistrationProperties);
            this.registration = registration;
        }


        @Deprecated
        public void setPort(int port) {
            getPort().set(port);
        }

        //如果属性文件配置的端口不合法重新设置端口
        @Override
        protected NacosRegistration getRegistration() {
            if (this.registration.getPort() < 0 && this.getPort().get() > 0) {
                this.registration.setPort(this.getPort().get());
            }
            Assert.isTrue(this.registration.getPort() > 0, "service.port has not been set");
            return this.registration;
        }

        ...

        //调用父方法实现注册
        @Override
        protected void register() {
            if (!this.registration.getNacosDiscoveryProperties().isRegisterEnabled()) {
                log.debug("Registration disabled.");
                return;
            }
            if (this.registration.getPort() < 0) {
                this.registration.setPort(getPort().get());
            }
            super.register();
        }

	...

        // 用户在配置文件中配置的属性
        @Override
        protected Object getConfiguration() {
            return this.registration.getNacosDiscoveryProperties();
        }

        //是否可以启用服务注册[纯消费者可以设置该属性为false]
        @Override
        protected boolean isEnabled() {
            return this.registration.getNacosDiscoveryProperties().isRegisterEnabled();
        }

        //首先从[spring.cloud.nacos.discovery.service]获取appName, 如果没有获取到则从
        //        [spring.application.name]属性获取
        @Override
        @SuppressWarnings("deprecation")
        protected String getAppName() {
            String appName = registration.getNacosDiscoveryProperties().getService();
            return StringUtils.isEmpty(appName) ? super.getAppName() : appName;
        }

    }

}

 */

