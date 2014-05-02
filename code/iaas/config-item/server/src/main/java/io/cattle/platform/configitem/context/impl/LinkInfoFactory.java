package io.cattle.platform.configitem.context.impl;

import io.cattle.platform.configitem.context.dao.NetworkInfoDao;
import io.cattle.platform.configitem.server.model.ConfigItem;
import io.cattle.platform.configitem.server.model.impl.ArchiveContext;
import io.cattle.platform.core.model.Agent;
import io.cattle.platform.core.model.Instance;

import javax.inject.Inject;
import javax.inject.Named;

@Named
public class LinkInfoFactory extends AbstractAgentBaseContextFactory {

    NetworkInfoDao networkInfoDao;

    @Override
    protected void populateContext(Agent agent, Instance instance, ConfigItem item, ArchiveContext context) {
        context.getData().put("links", networkInfoDao.getLinks(instance));
    }

    public NetworkInfoDao getNetworkInfoDao() {
        return networkInfoDao;
    }

    @Inject
    public void setNetworkInfoDao(NetworkInfoDao networkInfoDao) {
        this.networkInfoDao = networkInfoDao;
    }

}
