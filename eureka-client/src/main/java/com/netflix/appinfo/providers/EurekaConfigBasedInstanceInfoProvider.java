package com.netflix.appinfo.providers;

import javax.inject.Singleton;
import javax.inject.Provider;
import java.util.Map;

import com.google.inject.Inject;
import com.netflix.appinfo.DataCenterInfo;
import com.netflix.appinfo.EurekaInstanceConfig;
import com.netflix.appinfo.InstanceInfo;
import com.netflix.appinfo.InstanceInfo.InstanceStatus;
import com.netflix.appinfo.InstanceInfo.PortType;
import com.netflix.appinfo.LeaseInfo;
import com.netflix.appinfo.RefreshableInstanceConfig;
import com.netflix.appinfo.UniqueIdentifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于EurekaInstanceConfig创建InstanceInfo的工厂
 * InstanceInfo provider that constructs the InstanceInfo this this instance using
 * EurekaInstanceConfig.
 *
 * This provider is @Singleton scope as it provides the InstanceInfo for both DiscoveryClient
 * and ApplicationInfoManager, and need to provide the same InstanceInfo to both.
 *
 * @author elandau
 *
 */
@Singleton
public class EurekaConfigBasedInstanceInfoProvider implements Provider<InstanceInfo> {
    private static final Logger LOG = LoggerFactory.getLogger(EurekaConfigBasedInstanceInfoProvider.class);

    private final EurekaInstanceConfig config;

    private InstanceInfo instanceInfo;

    @Inject(optional = true)
    private VipAddressResolver vipAddressResolver = null;

    @Inject
    public EurekaConfigBasedInstanceInfoProvider(EurekaInstanceConfig config) {
        this.config = config;
    }

    @Override
    public synchronized InstanceInfo get() {
        if (instanceInfo == null) {
            // 创建租约信息构建器，并设置属性
            // Build the lease information to be passed to the server based on config
            LeaseInfo.Builder leaseInfoBuilder = LeaseInfo.Builder.newBuilder()
                    .setRenewalIntervalInSecs(config.getLeaseRenewalIntervalInSeconds())
                    .setDurationInSecs(config.getLeaseExpirationDurationInSeconds());

            if (vipAddressResolver == null) { // 创建VIP地址解析器
                vipAddressResolver = new Archaius1VipAddressResolver();
            }

            // Builder the instance information to be registered with eureka server
            InstanceInfo.Builder builder = InstanceInfo.Builder.newBuilder(vipAddressResolver); // 创建应用实例信息构建器

            // set the appropriate id for the InstanceInfo, falling back to datacenter Id if applicable, else hostname
            String instanceId = config.getInstanceId(); // 应用实例编号
            DataCenterInfo dataCenterInfo = config.getDataCenterInfo();
            if (instanceId == null || instanceId.isEmpty()) {
                if (dataCenterInfo instanceof UniqueIdentifier) {
                    instanceId = ((UniqueIdentifier) dataCenterInfo).getId();
                } else {
                    instanceId = config.getHostName(false);
                }
            }

            String defaultAddress; // 获得主机名
            if (config instanceof RefreshableInstanceConfig) {
                // Refresh AWS data center info, and return up to date address
                defaultAddress = ((RefreshableInstanceConfig) config).resolveDefaultAddress(false);
            } else {
                defaultAddress = config.getHostName(false);
            }

            // fail safe
            if (defaultAddress == null || defaultAddress.isEmpty()) {
                defaultAddress = config.getIpAddress();
            }

            builder.setNamespace(config.getNamespace()) // 设置应用实例信息构建器的属性
                    .setInstanceId(instanceId)
                    .setAppName(config.getAppname())
                    .setAppGroupName(config.getAppGroupName())
                    .setDataCenterInfo(config.getDataCenterInfo())
                    .setIPAddr(config.getIpAddress())
                    .setHostName(defaultAddress) // 主机名
                    .setPort(config.getNonSecurePort())
                    .enablePort(PortType.UNSECURE, config.isNonSecurePortEnabled())
                    .setSecurePort(config.getSecurePort())
                    .enablePort(PortType.SECURE, config.getSecurePortEnabled())
                    .setVIPAddress(config.getVirtualHostName()) // VIP地址
                    .setSecureVIPAddress(config.getSecureVirtualHostName())
                    .setHomePageUrl(config.getHomePageUrlPath(), config.getHomePageUrl())
                    .setStatusPageUrl(config.getStatusPageUrlPath(), config.getStatusPageUrl())
                    .setASGName(config.getASGName())
                    .setHealthCheckUrls(config.getHealthCheckUrlPath(),
                            config.getHealthCheckUrl(), config.getSecureHealthCheckUrl());

            // 应用初始化后是否开启
            // Start off with the STARTING state to avoid traffic
            if (!config.isInstanceEnabledOnit()) {
                InstanceStatus initialStatus = InstanceStatus.STARTING;
                LOG.info("Setting initial instance status as: {}", initialStatus);
                builder.setStatus(initialStatus);
            } else {
                LOG.info("Setting initial instance status as: {}. This may be too early for the instance to advertise "
                         + "itself as available. You would instead want to control this via a healthcheck handler.",
                         InstanceStatus.UP);
            }

            // 设置应用实例信息构建器的元数据（Metadata）集合
            // Add any user-specific metadata information
            for (Map.Entry<String, String> mapEntry : config.getMetadataMap().entrySet()) {
                String key = mapEntry.getKey();
                String value = mapEntry.getValue();
                builder.add(key, value);
            }

            instanceInfo = builder.build(); // 创建应用实例信息
            instanceInfo.setLeaseInfo(leaseInfoBuilder.build()); // 设置应用实例信息的租约信息
        }
        return instanceInfo;
    }

}
