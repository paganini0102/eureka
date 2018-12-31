package com.netflix.discovery.internal.util;

import com.netflix.config.ConfigurationManager;
import com.netflix.config.DynamicPropertyFactory;
import com.netflix.config.DynamicStringProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * This is an INTERNAL class not for public use.
 *
 * @author David Liu
 */
public final class Archaius1Utils {

    private static final Logger logger = LoggerFactory.getLogger(Archaius1Utils.class);

    private static final String ARCHAIUS_DEPLOYMENT_ENVIRONMENT = "archaius.deployment.environment";
    private static final String EUREKA_ENVIRONMENT = "eureka.environment";

    public static DynamicPropertyFactory initConfig(String configName) {

        DynamicPropertyFactory configInstance = DynamicPropertyFactory.getInstance(); // 配置文件对象
        DynamicStringProperty EUREKA_PROPS_FILE = configInstance.getStringProperty("eureka.client.props", configName); // 配置文件名

        String env = ConfigurationManager.getConfigInstance().getString(EUREKA_ENVIRONMENT, "test"); // 配置文件环境
        ConfigurationManager.getConfigInstance().setProperty(ARCHAIUS_DEPLOYMENT_ENVIRONMENT, env);

        String eurekaPropsFile = EUREKA_PROPS_FILE.get(); // 将配置文件加载到环境变量
        try {
            ConfigurationManager.loadCascadedPropertiesFromResources(eurekaPropsFile);
        } catch (IOException e) {
            logger.warn(
                    "Cannot find the properties specified : {}. This may be okay if there are other environment "
                            + "specific properties or the configuration is installed with a different mechanism.",
                    eurekaPropsFile);

        }

        return configInstance;
    }
}
