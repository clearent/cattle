package io.cattle.platform.configitem.context.data;

import io.cattle.platform.core.addon.InstanceHealthCheck;
import io.cattle.platform.core.model.HealthcheckInstance;
import io.cattle.platform.core.model.IpAddress;

public class HealthcheckData {
    IpAddress targetIpAddress;
    String healthCheckUuid;
    InstanceHealthCheck healthCheck;

    public HealthcheckData() {
    }

    public IpAddress getTargetIpAddress() {
        return targetIpAddress;
    }

    public void setTargetIpAddress(IpAddress targetIpAddress) {
        this.targetIpAddress = targetIpAddress;
    }

    public InstanceHealthCheck getHealthCheck() {
        return healthCheck;
    }

    public void setHealthCheck(InstanceHealthCheck healthCheck) {
        this.healthCheck = healthCheck;
    }

    public String getHealthCheckUuid() {
        return healthCheckUuid;
    }

    public void setHealthCheckUuid(String healthCheckUuid) {
        this.healthCheckUuid = healthCheckUuid;
    }

}
