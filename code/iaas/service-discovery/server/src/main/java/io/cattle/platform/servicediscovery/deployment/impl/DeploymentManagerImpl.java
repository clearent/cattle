package io.cattle.platform.servicediscovery.deployment.impl;

import io.cattle.platform.activity.ActivityLog;
import io.cattle.platform.activity.ActivityService;
import io.cattle.platform.allocator.service.AllocatorService;
import io.cattle.platform.configitem.events.ConfigUpdate;
import io.cattle.platform.configitem.model.Client;
import io.cattle.platform.configitem.request.ConfigUpdateRequest;
import io.cattle.platform.configitem.version.ConfigItemStatusManager;
import io.cattle.platform.core.addon.ScalePolicy;
import io.cattle.platform.core.constants.CommonStatesConstants;
import io.cattle.platform.core.constants.ServiceConstants;
import io.cattle.platform.core.dao.ServiceDao;
import io.cattle.platform.core.model.Service;
import io.cattle.platform.core.model.ServiceExposeMap;
import io.cattle.platform.engine.idempotent.IdempotentRetryException;
import io.cattle.platform.eventing.EventService;
import io.cattle.platform.json.JsonMapper;
import io.cattle.platform.lock.LockCallback;
import io.cattle.platform.lock.LockCallbackNoReturn;
import io.cattle.platform.lock.LockManager;
import io.cattle.platform.lock.definition.LockDefinition;
import io.cattle.platform.object.ObjectManager;
import io.cattle.platform.object.process.ObjectProcessManager;
import io.cattle.platform.object.process.StandardProcess;
import io.cattle.platform.object.resource.ResourceMonitor;
import io.cattle.platform.object.util.DataAccessor;
import io.cattle.platform.servicediscovery.api.dao.ServiceExposeMapDao;
import io.cattle.platform.servicediscovery.api.util.ServiceDiscoveryUtil;
import io.cattle.platform.servicediscovery.deployment.DeploymentManager;
import io.cattle.platform.servicediscovery.deployment.DeploymentUnitInstanceFactory;
import io.cattle.platform.servicediscovery.deployment.DeploymentUnitInstanceIdGenerator;
import io.cattle.platform.servicediscovery.deployment.ServiceDeploymentPlanner;
import io.cattle.platform.servicediscovery.deployment.ServiceDeploymentPlannerFactory;
import io.cattle.platform.servicediscovery.deployment.impl.lock.ServiceLock;
import io.cattle.platform.servicediscovery.deployment.impl.unit.DeploymentUnit;
import io.cattle.platform.servicediscovery.deployment.impl.unit.DeploymentUnitInstanceIdGeneratorImpl;
import io.cattle.platform.servicediscovery.service.ServiceDiscoveryService;
import io.cattle.platform.util.exception.ServiceInstanceAllocateException;
import io.cattle.platform.util.exception.ServiceReconcileException;
import io.github.ibuildthecloud.gdapi.id.IdFormatter;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class DeploymentManagerImpl implements DeploymentManager {

    private static final String RECONCILE = "reconcile";
    private static final Logger log = LoggerFactory.getLogger(DeploymentManagerImpl.class);

    @Inject
    ActivityService activity;
    @Inject
    LockManager lockManager;
    @Inject
    DeploymentUnitInstanceFactory unitInstanceFactory;
    @Inject
    ObjectProcessManager objectProcessMgr;
    @Inject
    ServiceDiscoveryService sdSvc;
    @Inject
    ObjectManager objectMgr;
    @Inject
    ResourceMonitor resourceMntr;
    @Inject
    ServiceExposeMapDao expMapDao;
    @Inject
    ServiceDeploymentPlannerFactory deploymentPlannerFactory;
    @Inject
    AllocatorService allocatorSvc;
    @Inject
    ConfigItemStatusManager itemManager;
    @Inject
    EventService eventService;
    @Inject
    JsonMapper mapper;
    @Inject
    ServiceDao svcDao;
    @Inject
    IdFormatter idFrmt;
    @Inject
    ActivityService actvtyService;

    @Override
    public boolean isHealthy(Service service) {
        return !activate(service, true);
    }

    @Override
    public void activate(final Service service) {
        activate(service, false);
    }

    /**
     * @param service
     * @param checkState
     * @return true if this service needs to be reconciled
     */
    protected boolean activate(final Service service, final boolean checkState) {
        // return immediately if inactive
        if (service == null || !sdSvc.isActiveService(service)) {
            return false;
        }

        return lockManager.lock(checkState ? null : createLock(service), new LockCallback<Boolean>() {
            @Override
            public Boolean doWithLock() {
                if (!sdSvc.isActiveService(service)) {
                    return false;
                }
                return reconcileDeployment(service, checkState);
            }

        });
    }

    protected boolean reconcileDeployment(final Service service, final boolean checkState) {
        ScalePolicy policy = DataAccessor.field(service,
                ServiceConstants.FIELD_SCALE_POLICY, mapper, ScalePolicy.class);
        boolean result = false;
        if (policy == null) {
            result = deploy(service, checkState);
        } else {
            result = deployWithScaleAdjustement(service, checkState, policy);
        }
        return result;
    }

    protected boolean deployWithScaleAdjustement(final Service service, final boolean checkState,
            ScalePolicy policy) {

        Integer desiredScaleToReset = null;
        Integer desiredScaleSet = DataAccessor.fieldInteger(service,
                ServiceConstants.FIELD_DESIRED_SCALE);
        if (desiredScaleSet == null) {
            desiredScaleToReset = policy.getMin();
        } else if (desiredScaleSet.intValue() > policy.getMax().intValue()) {
            desiredScaleToReset = policy.getMax();
        }

        boolean initLockScale = false;
        if (DataAccessor.fieldInteger(service,
                ServiceConstants.FIELD_LOCKED_SCALE) == null) {
            initLockScale = true;
        }

        if (desiredScaleToReset != null) {
            initLockScale = true;
            desiredScaleToReset = setDesiredScaleInternal(service, desiredScaleToReset);
            log.info("Set service [{}] desired scale to [{}]", service.getUuid(), desiredScaleToReset);
        }

        if (initLockScale) {
            lockScale(service);
        }

        return incremenetScaleAndDeploy(service, checkState, policy);
    }

    protected boolean incremenetScaleAndDeploy(final Service service, final boolean checkState,
            ScalePolicy policy) {
        Integer desiredScale = DataAccessor.fieldInteger(service,
                ServiceConstants.FIELD_DESIRED_SCALE);
        try {
            deploy(service, checkState);
            lockScale(service);
        } catch (ServiceInstanceAllocateException ex) {
            reduceScaleAndDeploy(service, checkState, policy);
            return false;
        }
        if (desiredScale.intValue() < policy.getMax().intValue()) {
            Integer newDesiredScale = policy.getMax() - desiredScale < policy.getIncrement().intValue() ? policy
                    .getMax()
                    : desiredScale
                            + policy.getIncrement();
            desiredScale = setDesiredScaleInternal(service, newDesiredScale);
            log.info("Incremented service [{}] scale to [{}] as reconcile has succeed", service.getUuid(),
                    desiredScale);
            incremenetScaleAndDeploy(service, checkState, policy);
        }

        return false;
    }

    protected boolean reduceScaleAndDeploy(Service service, boolean checkState, ScalePolicy policy) {
        int desiredScale = DataAccessor.fieldInteger(service, ServiceConstants.FIELD_DESIRED_SCALE).intValue();
        int lockedScale = DataAccessor.fieldInteger(service, ServiceConstants.FIELD_LOCKED_SCALE).intValue();
        int minScale = policy.getMin();
        // account for the fact that scale policy can be updated
        if (lockedScale > policy.getMin().intValue()) {
            minScale = lockedScale;
        }
        if (lockedScale >= policy.getMax().intValue()) {
            minScale = policy.getMax();
        }

        int increment = policy.getIncrement().intValue();
        if (desiredScale >= minScale) {
            // reduce scale by interval and try to deploy again
            Integer newDesiredScale = desiredScale - increment <= minScale ? minScale : desiredScale
                    - policy.getIncrement();
            desiredScale = setDesiredScaleInternal(service, newDesiredScale);
            log.info("Decremented service [{}] scale to [{}] as reconcile has failed", service.getUuid(), desiredScale);
            try {
                deploy(service, checkState);
            } catch (ServiceInstanceAllocateException ex) {
                if (desiredScale == minScale) {
                    throw ex;
                }
                reduceScaleAndDeploy(service, checkState, policy);
            }
        }
        return false;
    }

    protected Integer setDesiredScaleInternal(Service service, Integer newScale) {
        service = objectMgr.reload(service);
        Map<String, Object> data = new HashMap<>();
        data.put(ServiceConstants.FIELD_DESIRED_SCALE, newScale);
        objectMgr.setFields(service, data);
        return newScale;
    }

    protected void lockScale(Service service) {
        Integer desiredScale = DataAccessor.fieldInteger(service,
                ServiceConstants.FIELD_DESIRED_SCALE);
        service = objectMgr.reload(service);
        Map<String, Object> data = new HashMap<>();
        data.put(ServiceConstants.FIELD_LOCKED_SCALE, desiredScale);
        objectMgr.setFields(service, data);
    }

    protected boolean deploy(final Service service, final boolean checkState) {
        // get existing deployment units
        ServiceDeploymentPlanner planner = getPlanner(service);

        if (!checkState) {
            actvtyService.info(planner.getStatus());
        }

        // don't process if there is no need to reconcile
        boolean needToReconcile = needToReconcile(service, planner);

        if (!needToReconcile) {
            if (!checkState) {
                actvtyService.info("Service already reconciled");
            }
            return false;
        }

        if (checkState) {
            return !planner.isHealthcheckInitiailizing();
        }

        activateServices(service);
        activateDeploymentUnits(service, planner);

        // reload planner as there can be new hosts added for Global services
        // reload services as well
        Service reloaded = objectMgr.reload(service);
        planner = getPlanner(reloaded);
        if (needToReconcile(reloaded, planner)) {
            throw new ServiceReconcileException("Need to restart service reconcile");
        }

        actvtyService.info("Service reconciled: " + planner.getStatus());
        return false;
    }

    private ServiceDeploymentPlanner getPlanner(Service service) {
        List<DeploymentUnit> units = unitInstanceFactory.collectDeploymentUnits(service,
                new DeploymentServiceContext());
        return deploymentPlannerFactory.createServiceDeploymentPlanner(service,
                units, new DeploymentServiceContext());
    }

    private boolean needToReconcile(Service service, ServiceDeploymentPlanner planner) {
        if (service.getState().equals(CommonStatesConstants.INACTIVE)) {
            return true;
        }

        return planner.needToReconcileDeployment();
    }

    private void activateServices(final Service service) {
        /*
         * Trigger activate for all the services
         */
        try {
            if (service.getState().equalsIgnoreCase(CommonStatesConstants.INACTIVE)) {
                objectProcessMgr.scheduleStandardProcess(StandardProcess.ACTIVATE, service, null);
            } else if (service.getState().equalsIgnoreCase(CommonStatesConstants.ACTIVE)) {
                objectProcessMgr.scheduleStandardProcess(StandardProcess.UPDATE, service, null);
            }
        } catch (IdempotentRetryException ex) {
            // if not caught, the process will keep on spinning forever
            // figure out better solution
        }

    }

    protected LockDefinition createLock(Service service) {
        return new ServiceLock(service);
    }

    protected void activateDeploymentUnits(Service service, final ServiceDeploymentPlanner planner) {
        /*
         * Delete invalid units
         */
        planner.cleanupBadUnits();

        /*
         * Cleanup incomplete units
         */
        planner.cleanupIncompleteUnits();

        /*
         * Delete the units that have a bad health
         */
        planner.cleanupUnhealthyUnits();

        /*
         * Activate all the units
         */
        actvtyService.run(service, "wait", "Waiting for instances to start", new Runnable() {
            @Override
            public void run() {
                startUnits(planner);
            }
        });

        /*
         * Cleanup unused service indexes
         */
        planner.cleanupUnusedAndDuplicatedServiceIndexes();
    }

    private DeploymentUnitInstanceIdGenerator populateUsedNames(
            Service service) {
        Map<String, List<Integer>> launchConfigUsedIds = new HashMap<>();
        for (String launchConfigName : ServiceDiscoveryUtil.getServiceLaunchConfigNames(service)) {
            List<Integer> usedIds = sdSvc.getServiceInstanceUsedSuffixes(service, launchConfigName);
            launchConfigUsedIds.put(launchConfigName, usedIds);
        }
        return new DeploymentUnitInstanceIdGeneratorImpl(launchConfigUsedIds);
    }

    protected void startUnits(ServiceDeploymentPlanner planner) {
        DeploymentUnitInstanceIdGenerator svcInstanceIdGenerator = populateUsedNames(planner.getService());
        /*
         * Ask the planner to deploy more units/ remove extra units
         */
        planner.deploy(svcInstanceIdGenerator);
    }

    @Override
    public void deactivate(final Service service) {
        // do with lock to prevent intervention to sidekick service activate
        lockManager.lock(createLock(service), new LockCallbackNoReturn() {
            @Override
            public void doWithLockNoResult() {
                // in deactivate, we don't care about the sidekicks, and deactivate only requested service
                List<DeploymentUnit> units = unitInstanceFactory.collectDeploymentUnits(
                        service, new DeploymentServiceContext());
                for (DeploymentUnit unit : units) {
                    unit.stop();
                }
            }
        });
    }

    @Override
    public void remove(final Service service) {
        // do with lock to prevent intervention to sidekick service activate
        lockManager.lock(createLock(service), new LockCallbackNoReturn() {
            @Override
            public void doWithLockNoResult() {
                // in remove, we don't care about the sidekicks, and remove only requested service
                deleteServiceInstances(service);
                List<? extends ServiceExposeMap> unmanagedMaps = expMapDao
                        .getUnmanagedServiceInstanceMapsToRemove(service.getId());
                for (ServiceExposeMap unmanagedMap : unmanagedMaps) {
                    objectProcessMgr.scheduleStandardProcessAsync(StandardProcess.REMOVE, unmanagedMap, null);
                }
                sdSvc.removeServiceMaps(service);
            }

            protected void deleteServiceInstances(final Service service) {
                List<DeploymentUnit> units = unitInstanceFactory.collectDeploymentUnits(
                        service, new DeploymentServiceContext());
                for (DeploymentUnit unit : units) {
                    unit.remove(ServiceConstants.AUDIT_LOG_REMOVE_EXTRA, ActivityLog.INFO);
                }
            }
        });
    }


    @Override
    public void reconcileServices(Collection<? extends Service> services) {
        for (Service service: services) {
            ConfigUpdateRequest request = ConfigUpdateRequest.forResource(Service.class, service.getId());
            request.addItem(RECONCILE);
            request.withDeferredTrigger(true);
            itemManager.updateConfig(request);
        }
    }

    @Override
    public void serviceUpdate(ConfigUpdate update) {
        final Client client = new Client(Service.class, new Long(update.getResourceId()));
        itemManager.runUpdateForEvent(RECONCILE, update, client, new Runnable() {
            @Override
            public void run() {
                final Service service = objectMgr.loadResource(Service.class, client.getResourceId());
                if (service != null && service.getState().equalsIgnoreCase(CommonStatesConstants.ACTIVE)) {
                    activity.run(service, "service.trigger", "Re-evaluating state", new Runnable() {
                        @Override
                        public void run() {
                            activate(service);
                        }
                    });
                }
            }
        });
    }

    public final class DeploymentServiceContext {
        final public ObjectManager objectManager = objectMgr;
        final public ResourceMonitor resourceMonitor = resourceMntr;
        final public ObjectProcessManager objectProcessManager = objectProcessMgr;
        final public ServiceDiscoveryService sdService = sdSvc;
        final public ServiceExposeMapDao exposeMapDao = expMapDao;
        final public DeploymentUnitInstanceFactory deploymentUnitInstanceFactory = unitInstanceFactory;
        final public AllocatorService allocatorService = allocatorSvc;
        final public JsonMapper jsonMapper = mapper;
        final public ServiceDao serviceDao = svcDao;
        final public ActivityService activityService = actvtyService;
        final public IdFormatter idFormatter = idFrmt;
        final public LockManager lockMgr = lockManager;
    }
}
