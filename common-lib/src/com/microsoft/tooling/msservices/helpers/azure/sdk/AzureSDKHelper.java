/**
 * Copyright (c) Microsoft Corporation
 * <p/>
 * All rights reserved.
 * <p/>
 * MIT License
 * <p/>
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation
 * the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and
 * to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 * <p/>
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 * <p/>
 * THE SOFTWARE IS PROVIDED *AS IS*, WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO
 * THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 * TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.microsoft.tooling.msservices.helpers.azure.sdk;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import com.microsoft.azure.storage.CloudStorageAccount;
import com.microsoft.azure.storage.blob.CloudBlobClient;
import com.microsoft.azure.storage.blob.CloudBlobContainer;
import com.microsoft.tooling.msservices.components.DefaultLoader;
import com.microsoft.tooling.msservices.helpers.NotNull;
import com.microsoft.tooling.msservices.helpers.Nullable;
import com.microsoft.tooling.msservices.helpers.OpenSSLHelper;
import com.microsoft.tooling.msservices.helpers.azure.AzureCmdException;
import com.microsoft.tooling.msservices.helpers.azure.AzureManagerImpl;
import com.microsoft.tooling.msservices.model.storage.ClientStorageAccount;
import com.microsoft.tooling.msservices.model.storage.StorageAccount;
import com.microsoft.tooling.msservices.model.vm.*;
import com.microsoft.tooling.msservices.model.ws.WebHostingPlan;
import com.microsoft.tooling.msservices.model.ws.WebSite;
import com.microsoft.tooling.msservices.model.ws.WebSiteConfiguration;
import com.microsoft.tooling.msservices.model.ws.WebSiteConfiguration.ConnectionInfo;
import com.microsoft.tooling.msservices.model.ws.WebSitePublishSettings;
import com.microsoft.tooling.msservices.model.ws.WebSitePublishSettings.FTPPublishProfile;
import com.microsoft.tooling.msservices.model.ws.WebSitePublishSettings.MSDeployPublishProfile;
import com.microsoft.tooling.msservices.model.ws.WebSitePublishSettings.PublishProfile;
import com.microsoft.windowsazure.Configuration;
import com.microsoft.windowsazure.core.OperationResponse;
import com.microsoft.windowsazure.core.OperationStatus;
import com.microsoft.windowsazure.core.OperationStatusResponse;
import com.microsoft.windowsazure.core.pipeline.apache.ApacheConfigurationProperties;
import com.microsoft.windowsazure.core.utils.Base64;
import com.microsoft.windowsazure.core.utils.KeyStoreType;
import com.microsoft.windowsazure.exception.CloudError;
import com.microsoft.windowsazure.exception.ServiceException;
import com.microsoft.windowsazure.management.*;
import com.microsoft.windowsazure.management.compute.*;
import com.microsoft.windowsazure.management.compute.models.*;
import com.microsoft.windowsazure.management.configuration.ManagementConfiguration;
import com.microsoft.windowsazure.management.models.AffinityGroupListResponse;
import com.microsoft.windowsazure.management.models.LocationsListResponse;
import com.microsoft.windowsazure.management.models.RoleSizeListResponse;
import com.microsoft.windowsazure.management.network.NetworkManagementClient;
import com.microsoft.windowsazure.management.network.NetworkManagementService;
import com.microsoft.windowsazure.management.network.NetworkOperations;
import com.microsoft.windowsazure.management.network.models.NetworkListResponse;
import com.microsoft.windowsazure.management.storage.StorageAccountOperations;
import com.microsoft.windowsazure.management.storage.StorageManagementClient;
import com.microsoft.windowsazure.management.storage.StorageManagementService;
import com.microsoft.windowsazure.management.storage.models.*;
import com.microsoft.windowsazure.management.websites.*;
import com.microsoft.windowsazure.management.websites.models.*;
import com.microsoft.windowsazure.management.websites.models.WebSpacesListResponse.WebSpace;
import org.xml.sax.SAXException;

import javax.security.cert.X509Certificate;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPathExpressionException;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.*;
import java.security.cert.CertificateException;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class AzureSDKHelper {
    private static class StatusLiterals {
        private static final String UNKNOWN = "Unknown";
        private static final String READY_ROLE = "ReadyRole";
        private static final String STOPPED_VM = "StoppedVM";
        private static final String STOPPED_DEALLOCATED = "StoppedDeallocated";
        private static final String BUSY_ROLE = "BusyRole";
        private static final String CREATING_VM = "CreatingVM";
        private static final String CREATING_ROLE = "CreatingRole";
        private static final String STARTING_VM = "StartingVM";
        private static final String STARTING_ROLE = "StartingRole";
        private static final String STOPPING_VM = "StoppingVM";
        private static final String STOPPING_ROLE = "StoppingRole";
        private static final String DELETING_VM = "DeletingVM";
        private static final String RESTARTING_ROLE = "RestartingRole";
        private static final String CYCLING_ROLE = "CyclingRole";
        private static final String FAILED_STARTING_VM = "FailedStartingVM";
        private static final String FAILED_STARTING_ROLE = "FailedStartingRole";
        private static final String UNRESPONSIVE_ROLE = "UnresponsiveRole";
        private static final String PREPARING = "Preparing";
    }

    private static final String PERSISTENT_VM_ROLE = "PersistentVMRole";
    private static final String NETWORK_CONFIGURATION = "NetworkConfiguration";
    private static final String PLATFORM_IMAGE = "Platform";
    private static final String USER_IMAGE = "User";
    private static final String WINDOWS_OS_TYPE = "Windows";
    private static final String LINUX_OS_TYPE = "Linux";
    private static final String WINDOWS_PROVISIONING_CONFIGURATION = "WindowsProvisioningConfiguration";
    private static final String LINUX_PROVISIONING_CONFIGURATION = "LinuxProvisioningConfiguration";
    private static final char[] HEX_ARRAY = "0123456789ABCDEF".toCharArray();

    @NotNull
    public static SDKRequestCallback<List<CloudService>, ComputeManagementClient> getCloudServices(@NotNull final String subscriptionId) {
        return new SDKRequestCallback<List<CloudService>, ComputeManagementClient>() {
            @NotNull
            @Override
            public List<CloudService> execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                List<CloudService> csList = new ArrayList<CloudService>();
                ArrayList<HostedServiceListResponse.HostedService> hostedServices = getHostedServices(client).getHostedServices();

                if (hostedServices == null) {
                    return csList;
                }

                for (HostedServiceListResponse.HostedService hostedService : hostedServices) {
                    ListenableFuture<DeploymentGetResponse> productionFuture = getDeploymentAsync(
                            client,
                            hostedService.getServiceName(),
                            DeploymentSlot.Production);
                    ListenableFuture<DeploymentGetResponse> stagingFuture = getDeploymentAsync(
                            client,
                            hostedService.getServiceName(),
                            DeploymentSlot.Staging);

                    DeploymentGetResponse prodDGR = productionFuture.get();

                    DeploymentGetResponse stagingDGR = stagingFuture.get();

                    CloudService cloudService = new CloudService(
                            hostedService.getServiceName() != null ? hostedService.getServiceName() : "",
                            hostedService.getProperties() != null && hostedService.getProperties().getLocation() != null ?
                                    hostedService.getProperties().getLocation() :
                                    "",
                            hostedService.getProperties() != null && hostedService.getProperties().getAffinityGroup() != null ?
                                    hostedService.getProperties().getAffinityGroup() :
                                    "",
                            subscriptionId);

                    loadDeployment(prodDGR, cloudService);

                    cloudService = loadDeployment(prodDGR, cloudService);
                    cloudService = loadDeployment(stagingDGR, cloudService);

                    csList.add(cloudService);
                }

                return csList;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<List<VirtualMachine>, ComputeManagementClient> getVirtualMachines(@NotNull final String subscriptionId) {
        return new SDKRequestCallback<List<VirtualMachine>, ComputeManagementClient>() {
            @NotNull
            @Override
            public List<VirtualMachine> execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                List<VirtualMachine> vmList = new ArrayList<VirtualMachine>();
                ArrayList<HostedServiceListResponse.HostedService> hostedServices = getHostedServices(client).getHostedServices();

                if (hostedServices == null) {
                    return vmList;
                }

                for (HostedServiceListResponse.HostedService hostedService : hostedServices) {
                    String serviceName = hostedService.getServiceName() != null ? hostedService.getServiceName() : "";
                    vmList = loadVirtualMachines(client, subscriptionId, serviceName, vmList);
                }

                return vmList;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<VirtualMachine, ComputeManagementClient> refreshVirtualMachineInformation(@NotNull final VirtualMachine vm) {
        return new SDKRequestCallback<VirtualMachine, ComputeManagementClient>() {
            @NotNull
            @Override
            public VirtualMachine execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                DeploymentGetResponse deployment = getDeployment(client, vm);

                List<Role> roles = getVMDeploymentRoles(deployment);

                Role vmRole = null;

                for (Role role : roles) {
                    if (PERSISTENT_VM_ROLE.equals(role.getRoleType()) && vm.getName().equals(role.getRoleName())) {
                        vmRole = role;
                        break;
                    }
                }

                if (vmRole == null) {
                    throw new Exception("Invalid Virtual Machine information. No Roles match the VM data.");
                }

                vm.setDeploymentName(deployment.getName() != null ? deployment.getName() : "");
                vm.setAvailabilitySet(vmRole.getAvailabilitySetName() != null ? vmRole.getAvailabilitySetName() : "");
                vm.setSize(vmRole.getRoleSize() != null ? vmRole.getRoleSize() : "");
                vm.setStatus(getVMStatus(deployment, vmRole));

                vm.getEndpoints().clear();
                loadNetworkConfiguration(vmRole, vm);

                return vm;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<Void, ComputeManagementClient> startVirtualMachine(@NotNull final VirtualMachine vm) {
        return new SDKRequestCallback<Void, ComputeManagementClient>() {
            @NotNull
            @Override
            public Void execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                VirtualMachineOperations vmo = getVirtualMachineOperations(client);
                OperationStatusResponse osr = vmo.start(vm.getServiceName(), vm.getDeploymentName(), vm.getName());
                validateOperationStatus(osr);

                return null;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<Void, ComputeManagementClient> shutdownVirtualMachine(@NotNull final VirtualMachine vm,
                                                                                           final boolean deallocate) {
        return new SDKRequestCallback<Void, ComputeManagementClient>() {
            @NotNull
            @Override
            public Void execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                VirtualMachineOperations vmo = getVirtualMachineOperations(client);

                VirtualMachineShutdownParameters parameters = new VirtualMachineShutdownParameters();
                parameters.setPostShutdownAction(deallocate ? PostShutdownAction.StoppedDeallocated : PostShutdownAction.Stopped);
                OperationStatusResponse osr = vmo.shutdown(vm.getServiceName(), vm.getDeploymentName(), vm.getName(), parameters);
                validateOperationStatus(osr);

                return null;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<Void, ComputeManagementClient> restartVirtualMachine(@NotNull final VirtualMachine vm) {
        return new SDKRequestCallback<Void, ComputeManagementClient>() {
            @NotNull
            @Override
            public Void execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                VirtualMachineOperations vmo = getVirtualMachineOperations(client);
                OperationStatusResponse osr = vmo.restart(vm.getServiceName(), vm.getDeploymentName(), vm.getName());
                validateOperationStatus(osr);

                return null;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<Void, ComputeManagementClient> deleteVirtualMachine(@NotNull final VirtualMachine vm,
                                                                                         final boolean deleteFromStorage) {
        return new SDKRequestCallback<Void, ComputeManagementClient>() {
            @NotNull
            @Override
            public Void execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                DeploymentGetResponse deployment = getDeployment(client, vm);

                List<Role> roles = getVMDeploymentRoles(deployment);

                if (roles.size() == 1) {
                    Role role = roles.get(0);

                    if (PERSISTENT_VM_ROLE.equals(role.getRoleType()) && vm.getName().equals(role.getRoleName())) {
                        deleteDeployment(client, vm.getServiceName(), vm.getDeploymentName(), deleteFromStorage);
                    } else {
                        throw new Exception("Invalid Virtual Machine information. No Roles match the VM data.");
                    }
                } else if (roles.size() > 1) {
                    deleteVMRole(client, vm.getServiceName(), vm.getDeploymentName(), vm.getName(), deleteFromStorage);
                } else {
                    throw new Exception("Invalid Virtual Machine information. No Roles match the VM data.");
                }

                return null;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<byte[], ComputeManagementClient> downloadRDP(@NotNull final VirtualMachine vm) {
        return new SDKRequestCallback<byte[], ComputeManagementClient>() {
            @NotNull
            @Override
            public byte[] execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                VirtualMachineOperations vmo = getVirtualMachineOperations(client);

                VirtualMachineGetRemoteDesktopFileResponse vmgrdfr = vmo.getRemoteDesktopFile(
                        vm.getServiceName(),
                        vm.getDeploymentName(),
                        vm.getName());

                if (vmgrdfr == null) {
                    throw new Exception("Unable to retrieve RDP information");
                }

                byte[] remoteDesktopFile = vmgrdfr.getRemoteDesktopFile();

                if (remoteDesktopFile == null) {
                    throw new Exception("Unable to retrieve RDP information");
                }

                return (new String(remoteDesktopFile, "UTF-8")).getBytes();
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<List<StorageAccount>, StorageManagementClient> getStorageAccounts(@NotNull final String subscriptionId) {
        return new SDKRequestCallback<List<StorageAccount>, StorageManagementClient>() {
            @NotNull
            @Override
            public List<StorageAccount> execute(@NotNull StorageManagementClient client)
                    throws Throwable {
                List<StorageAccount> saList = new ArrayList<StorageAccount>();
                ArrayList<com.microsoft.windowsazure.management.storage.models.StorageAccount> storageAccounts =
                        getStorageAccounts(client).getStorageAccounts();

                if (storageAccounts == null) {
                    return saList;
                }

                List<ListenableFuture<StorageAccount>> saFutureList = new ArrayList<ListenableFuture<StorageAccount>>();

                for (com.microsoft.windowsazure.management.storage.models.StorageAccount storageAccount : storageAccounts) {
                    saFutureList.add(getStorageAccountAsync(subscriptionId, client, storageAccount));
                }

                saList.addAll(Futures.allAsList(saFutureList).get());

                return saList;
            }
        };
    }


    @NotNull
    public static SDKRequestCallback<List<VirtualMachineImage>, ComputeManagementClient> getVirtualMachineImages() {
        return new SDKRequestCallback<List<VirtualMachineImage>, ComputeManagementClient>() {
            @NotNull
            @Override
            public List<VirtualMachineImage> execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                List<VirtualMachineImage> vmImageList = new ArrayList<VirtualMachineImage>();

                ListenableFuture<List<VirtualMachineImage>> osImagesFuture = getOSImagesAsync(client);
                ListenableFuture<List<VirtualMachineImage>> vmImagesFuture = getVMImagesAsync(client);
                vmImageList.addAll(osImagesFuture.get());
                vmImageList.addAll(vmImagesFuture.get());

                return vmImageList;
            }
        };
    }


    @NotNull
    public static SDKRequestCallback<List<VirtualMachineSize>, ManagementClient> getVirtualMachineSizes() {
        return new SDKRequestCallback<List<VirtualMachineSize>, ManagementClient>() {
            @NotNull
            @Override
            public List<VirtualMachineSize> execute(@NotNull ManagementClient client)
                    throws Throwable {
                List<VirtualMachineSize> vmSizeList = new ArrayList<VirtualMachineSize>();

                vmSizeList = loadVMSizes(client, vmSizeList);

                return vmSizeList;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<List<Location>, ManagementClient> getLocations() {
        return new SDKRequestCallback<List<Location>, ManagementClient>() {
            @NotNull
            @Override
            public List<Location> execute(@NotNull ManagementClient client) throws Throwable {
                List<Location> locationList = new ArrayList<Location>();
                locationList = loadLocations(client, locationList);

                return locationList;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<List<AffinityGroup>, ManagementClient> getAffinityGroups() {
        return new SDKRequestCallback<List<AffinityGroup>, ManagementClient>() {
            @NotNull
            @Override
            public List<AffinityGroup> execute(@NotNull ManagementClient client) throws Throwable {
                List<AffinityGroup> affinityGroupList = new ArrayList<AffinityGroup>();
                affinityGroupList = loadAffinityGroups(client, affinityGroupList);

                return affinityGroupList;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<List<VirtualNetwork>, NetworkManagementClient> getVirtualNetworks(@NotNull final String subscriptionId) {
        return new SDKRequestCallback<List<VirtualNetwork>, NetworkManagementClient>() {
            @NotNull
            @Override
            public List<VirtualNetwork> execute(@NotNull NetworkManagementClient client)
                    throws Throwable {
                List<VirtualNetwork> vnList = new ArrayList<VirtualNetwork>();

                ArrayList<NetworkListResponse.VirtualNetworkSite> virtualNetworkSites =
                        getNetworks(client).getVirtualNetworkSites();

                if (virtualNetworkSites == null) {
                    return vnList;
                }

                for (NetworkListResponse.VirtualNetworkSite virtualNetworkSite : virtualNetworkSites) {
                    VirtualNetwork vn = new VirtualNetwork(
                            virtualNetworkSite.getName() != null ? virtualNetworkSite.getName() : "",
                            virtualNetworkSite.getId() != null ? virtualNetworkSite.getId() : "",
                            virtualNetworkSite.getLocation() != null ? virtualNetworkSite.getLocation() : "",
                            virtualNetworkSite.getAffinityGroup() != null ? virtualNetworkSite.getAffinityGroup() : "",
                            subscriptionId);

                    if (virtualNetworkSite.getSubnets() != null) {
                        Set<String> vnSubnets = vn.getSubnets();

                        for (NetworkListResponse.Subnet subnet : virtualNetworkSite.getSubnets()) {
                            if (subnet.getName() != null && !subnet.getName().isEmpty()) {
                                vnSubnets.add(subnet.getName());
                            }
                        }
                    }

                    vnList.add(vn);
                }

                return vnList;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<Void, StorageManagementClient> createStorageAccount(@NotNull final StorageAccount storageAccount) {
        return new SDKRequestCallback<Void, StorageManagementClient>() {
            @NotNull
            @Override
            public Void execute(@NotNull StorageManagementClient client)
                    throws Throwable {
                StorageAccountOperations sao = getStorageAccountOperations(client);
                StorageAccountCreateParameters sacp = new StorageAccountCreateParameters(storageAccount.getName(),
                        storageAccount.getName());
                sacp.setAccountType(storageAccount.getType());

                if (!storageAccount.getAffinityGroup().isEmpty()) {
                    sacp.setAffinityGroup(storageAccount.getAffinityGroup());
                } else if (!storageAccount.getLocation().isEmpty()) {
                    sacp.setLocation(storageAccount.getLocation());
                }

                OperationStatusResponse osr = sao.create(sacp);
                validateOperationStatus(osr);

                return null;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<Void, ComputeManagementClient> createCloudService(@NotNull final CloudService cloudService) {
        return new SDKRequestCallback<Void, ComputeManagementClient>() {
            @NotNull
            @Override
            public Void execute(@NotNull ComputeManagementClient client) throws Throwable {
                HostedServiceOperations hso = getHostedServiceOperations(client);
                HostedServiceCreateParameters hscp = new HostedServiceCreateParameters(cloudService.getName(),
                        cloudService.getName());

                if (!cloudService.getAffinityGroup().isEmpty()) {
                    hscp.setAffinityGroup(cloudService.getAffinityGroup());
                } else if (!cloudService.getLocation().isEmpty()) {
                    hscp.setLocation(cloudService.getLocation());
                }

                OperationResponse or = hso.create(hscp);

                if (or == null) {
                    throw new Exception("Unable to retrieve Operation");
                }

                OperationStatusResponse osr = getOperationStatusResponse(client, or);
                validateOperationStatus(osr);

                return null;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<Void, ComputeManagementClient> createVirtualMachine(@NotNull final VirtualMachine virtualMachine, @NotNull final VirtualMachineImage vmImage,
                                                                                         @NotNull final StorageAccount storageAccount, @NotNull final String virtualNetwork,
                                                                                         @NotNull final String username, @NotNull final String password, @NotNull final byte[] certificate) {
        return new SDKRequestCallback<Void, ComputeManagementClient>() {
            @NotNull
            @Override
            public Void execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                String mediaLocation = getMediaLocation(virtualMachine, storageAccount);

                return createVirtualMachine(virtualMachine, vmImage, mediaLocation, virtualNetwork, username, password, certificate).execute(client);
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<Void, ComputeManagementClient> createVirtualMachine(@NotNull final VirtualMachine virtualMachine, @NotNull final VirtualMachineImage vmImage,
                                                                                         @NotNull final String mediaLocation, @NotNull final String virtualNetwork,
                                                                                         @NotNull final String username, @NotNull final String password, @NotNull final byte[] certificate) {
        return new SDKRequestCallback<Void, ComputeManagementClient>() {
            @NotNull
            @Override
            public Void execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                VirtualMachineOperations vmo = getVirtualMachineOperations(client);

                if (virtualMachine.getDeploymentName().isEmpty()) {
                    createVMDeployment(client, vmo, virtualMachine, vmImage, mediaLocation, virtualNetwork, username, password, certificate);
                } else {
                    createVM(client, vmo, virtualMachine, vmImage, mediaLocation, username, password, certificate);
                }

                return null;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<StorageAccount, StorageManagementClient> refreshStorageAccountInformation(@NotNull final StorageAccount storageAccount) {
        return new SDKRequestCallback<StorageAccount, StorageManagementClient>() {
            @NotNull
            @Override
            public StorageAccount execute(@NotNull StorageManagementClient client)
                    throws Throwable {
                StorageAccountOperations sao = getStorageAccountOperations(client);
                StorageAccountGetResponse sagr = sao.get(storageAccount.getName());

                if (sagr == null) {
                    throw new Exception("Unable to retrieve Operation");
                }

                OperationStatusResponse osr = getOperationStatusResponse(client, sagr);
                validateOperationStatus(osr);

                if (sagr.getStorageAccount() == null) {
                    throw new Exception("Invalid Storage Account information. No Storage Account matches the specified data.");
                }

                StorageAccount sa = getStorageAccount(storageAccount.getSubscriptionId(), client, sagr.getStorageAccount());
                storageAccount.setType(sa.getType());
                storageAccount.setDescription(sa.getDescription());
                storageAccount.setLabel(sa.getLabel());
                storageAccount.setStatus(sa.getStatus());
                storageAccount.setLocation(sa.getLocation());
                storageAccount.setAffinityGroup(sa.getAffinityGroup());
                storageAccount.setPrimaryKey(sa.getPrimaryKey());
                storageAccount.setSecondaryKey(sa.getSecondaryKey());
                storageAccount.setManagementUri(sa.getManagementUri());
                storageAccount.setBlobsUri(sa.getBlobsUri());
                storageAccount.setQueuesUri(sa.getQueuesUri());
                storageAccount.setTablesUri(sa.getTablesUri());
                storageAccount.setPrimaryRegion(sa.getPrimaryRegion());
                storageAccount.setPrimaryRegionStatus(sa.getPrimaryRegionStatus());
                storageAccount.setSecondaryRegion(sa.getSecondaryRegion());
                storageAccount.setSecondaryRegionStatus(sa.getSecondaryRegionStatus());
                storageAccount.setLastFailover(sa.getLastFailover());

                return storageAccount;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<String, ComputeManagementClient> createServiceCertificate(@NotNull final String serviceName,
                                                                                               @NotNull final byte[] data,
                                                                                               @NotNull final String password) {
        return new SDKRequestCallback<String, ComputeManagementClient>() {
            @NotNull
            @Override
            public String execute(@NotNull ComputeManagementClient client)
                    throws Throwable {
                MessageDigest md = MessageDigest.getInstance("SHA1");
                X509Certificate cert = X509Certificate.getInstance(data);
                md.update(cert.getEncoded());
                String thumbprint = bytesToHex(md.digest());

                ServiceCertificateOperations sco = getServiceCertificateOperations(client);
                ServiceCertificateCreateParameters sccp = new ServiceCertificateCreateParameters(data,
                        CertificateFormat.Pfx);
                sccp.setPassword(password);

                OperationStatusResponse osr = sco.create(serviceName, sccp);
                validateOperationStatus(osr);

                return thumbprint;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<Void, StorageManagementClient> deleteStorageAccount(@NotNull final StorageAccount storageAccount) {
        return new SDKRequestCallback<Void, StorageManagementClient>() {
            @NotNull
            @Override
            public Void execute(@NotNull StorageManagementClient client)
                    throws Throwable {
                StorageAccountOperations sao = getStorageAccountOperations(client);
                OperationResponse or = sao.delete(storageAccount.getName());
                OperationStatusResponse osr = getOperationStatusResponse(client, or);
                validateOperationStatus(osr);

                return null;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<List<String>, WebSiteManagementClient> getWebSpaces() {
        return new SDKRequestCallback<List<String>, WebSiteManagementClient>() {
            @NotNull
            @Override
            public List<String> execute(@NotNull WebSiteManagementClient client)
                    throws Throwable {
                List<String> wsList = new ArrayList<String>();

                for (WebSpace webSpace : getWebSpaces(client)) {
                    if (webSpace.getName() != null) {
                        wsList.add(webSpace.getName());
                    }
                }

                return wsList;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<List<WebSite>, WebSiteManagementClient> getWebSites(@NotNull final String webSpaceName) {
        return new SDKRequestCallback<List<WebSite>, WebSiteManagementClient>() {
            @NotNull
            @Override
            public List<WebSite> execute(@NotNull WebSiteManagementClient client)
                    throws Throwable {
                List<WebSite> wsList = new ArrayList<WebSite>();

                String subscriptionId = client.getCredentials().getSubscriptionId();

                for (com.microsoft.windowsazure.management.websites.models.WebSite webSite : getWebSites(client,
                        webSpaceName)) {
                    WebSite ws = loadWebSite(subscriptionId, webSpaceName, webSite);
                    wsList.add(ws);
                }

                return wsList;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<List<WebHostingPlan>, WebSiteManagementClient> getWebHostingPlans(@NotNull final String webSpaceName) {
        return new SDKRequestCallback<List<WebHostingPlan>, WebSiteManagementClient>() {
            @NotNull
            @Override
            public List<WebHostingPlan> execute(@NotNull WebSiteManagementClient client)
                    throws Throwable {
                List<WebHostingPlan> whpList = new ArrayList<WebHostingPlan>();

                String subscriptionId = client.getCredentials().getSubscriptionId();

                for (com.microsoft.windowsazure.management.websites.models.WebHostingPlan webHostingPlan :
                        getWebHostingPlans(client, webSpaceName)) {
                    WebHostingPlan ws = new WebHostingPlan(Strings.nullToEmpty(webHostingPlan.getName()),
                            webSpaceName,
                            subscriptionId);

                    whpList.add(ws);
                }

                return whpList;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<WebSiteConfiguration, WebSiteManagementClient> getWebSiteConfiguration(@NotNull final String webSpaceName,
                                                                                                            @NotNull final String webSiteName) {
        return new SDKRequestCallback<WebSiteConfiguration, WebSiteManagementClient>() {
            @NotNull
            @Override
            public WebSiteConfiguration execute(@NotNull WebSiteManagementClient client)
                    throws Throwable {
                String subscriptionId = client.getCredentials().getSubscriptionId();
                WebSiteGetConfigurationResponse webSiteConfiguration = getWebSiteConfiguration(client, webSpaceName, webSiteName);

                return loadWebSiteConfiguration(subscriptionId, webSpaceName, webSiteName, webSiteConfiguration);
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<WebSitePublishSettings, WebSiteManagementClient> getWebSitePublishSettings(@NotNull final String webSpaceName,
                                                                                                                @NotNull final String webSiteName) {
        return new SDKRequestCallback<WebSitePublishSettings, WebSiteManagementClient>() {
            @NotNull
            @Override
            public WebSitePublishSettings execute(@NotNull WebSiteManagementClient client)
                    throws Throwable {
                String subscriptionId = client.getCredentials().getSubscriptionId();
                WebSitePublishSettings wsps = new WebSitePublishSettings(webSpaceName, webSiteName, subscriptionId);

                WebSiteGetPublishProfileResponse webSitePublishProfile = getWebSitePublishProfile(client, webSpaceName, webSiteName);

                List<WebSitePublishSettings.PublishProfile> publishProfileList = wsps.getPublishProfileList();

                for (WebSiteGetPublishProfileResponse.PublishProfile publishProfile : webSitePublishProfile.getPublishProfiles()) {
                    String name = Strings.nullToEmpty(publishProfile.getProfileName());
                    final String publishMethod = Strings.nullToEmpty(publishProfile.getPublishMethod());

                    PublishProfile pp;

                    if ("MSDeploy".equals(publishMethod)) {
                        pp = new MSDeployPublishProfile(name);
                        ((MSDeployPublishProfile) pp).setMsdeploySite(Strings.nullToEmpty(publishProfile.getMSDeploySite()));
                    } else if ("FTP".equals(publishMethod)) {
                        pp = new FTPPublishProfile(name);
                        ((FTPPublishProfile) pp).setFtpPassiveMode(publishProfile.isFtpPassiveMode());
                    } else {
                        pp = new PublishProfile(name) {
                            @NotNull
                            @Override
                            public String getPublishMethod() {
                                return publishMethod;
                            }
                        };
                    }

                    pp.setPublishUrl(Strings.nullToEmpty(publishProfile.getPublishUrl()));
                    pp.setUserName(Strings.nullToEmpty(publishProfile.getUserName()));
                    pp.setPassword(Strings.nullToEmpty(publishProfile.getUserPassword()));
                    pp.setDestinationAppUrl(publishProfile.getDestinationAppUri() != null ?
                            publishProfile.getDestinationAppUri().toString() : "");
                    pp.setSqlServerDBConnectionString(Strings.nullToEmpty(publishProfile.getSqlServerConnectionString()));
                    pp.setMySQLDBConnectionString(Strings.nullToEmpty(publishProfile.getMySqlConnectionString()));
                    pp.setHostingProviderForumLink(publishProfile.getHostingProviderForumUri() != null ?
                            publishProfile.getHostingProviderForumUri().toString() : "");
                    pp.setControlPanelLink(publishProfile.getControlPanelUri() != null ?
                            publishProfile.getControlPanelUri().toString() : "");

                    publishProfileList.add(pp);
                }

                return wsps;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<Void, WebSiteManagementClient> restartWebSite(@NotNull final String webSpaceName,
                                                                                   @NotNull final String webSiteName) {
        return new SDKRequestCallback<Void, WebSiteManagementClient>() {
            @NotNull
            @Override
            public Void execute(@NotNull WebSiteManagementClient client)
                    throws Throwable {
                getWebSiteOperations(client).restart(webSpaceName, webSiteName);

                return null;
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<WebSite, WebSiteManagementClient> createWebSite(@NotNull final String webSpaceName,
                                                                                     @NotNull final String webHostingPlanName,
                                                                                     @NotNull final String webSiteName) {
        return new SDKRequestCallback<WebSite, WebSiteManagementClient>() {
            @NotNull
            @Override
            public WebSite execute(@NotNull WebSiteManagementClient client)
                    throws Throwable {
                String subscriptionId = client.getCredentials().getSubscriptionId();
                WebSiteCreateParameters wsCreateParams = new WebSiteCreateParameters(webSiteName, webHostingPlanName);
                com.microsoft.windowsazure.management.websites.models.WebSite webSite =
                        getWebSiteOperations(client).create(webSpaceName, wsCreateParams).getWebSite();

                return loadWebSite(subscriptionId, webSpaceName, webSite);
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<WebSiteConfiguration, WebSiteManagementClient> updateWebSiteConfiguration(@NotNull final String webSpaceName,
                                                                                                               @NotNull final String webSiteName,
                                                                                                               @NotNull final WebSiteConfiguration webSiteConfiguration) {
        return new SDKRequestCallback<WebSiteConfiguration, WebSiteManagementClient>() {
            @NotNull
            @Override
            public WebSiteConfiguration execute(@NotNull WebSiteManagementClient client)
                    throws Throwable {
                updateWebSiteConfiguration(client, webSpaceName, webSiteName, webSiteConfiguration);
                WebSiteGetConfigurationResponse wsgcr = getWebSiteConfiguration(client, webSpaceName, webSiteName);

                return loadWebSiteConfiguration(webSiteConfiguration, wsgcr);
            }
        };
    }

    @NotNull
    public static SDKRequestCallback<WebHostingPlan, WebSiteManagementClient> createWebHostingPlan(@NotNull final WebHostingPlan webHostingPlan) {
        return new SDKRequestCallback<WebHostingPlan, WebSiteManagementClient>() {
            @NotNull
            @Override
            public WebHostingPlan execute(@NotNull WebSiteManagementClient client)
                    throws Throwable {
                WebHostingPlanOperations whpo = getWebHostingPlanOperations(client);
                WebHostingPlanCreateParameters whpcp = new WebHostingPlanCreateParameters(webHostingPlan.getName());
                whpcp.setSKU(SkuOptions.Free);

                whpo.create(webHostingPlan.getWebSpaceName(), whpcp);

                return webHostingPlan;
            }
        };
    }

    @NotNull
    public static ComputeManagementClient getComputeManagementClient(@NotNull String subscriptionId,
                                                                     @NotNull String accessToken)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException,
            XPathExpressionException, ParserConfigurationException, SAXException, AzureCmdException {
        Configuration configuration = getConfigurationFromAuthToken(subscriptionId);

        if (configuration == null) {
            throw new AzureCmdException("Unable to instantiate Configuration");
        }

        ComputeManagementClient client = ComputeManagementService.create(configuration);

        if (client == null) {
            throw new AzureCmdException("Unable to instantiate Compute Management client");
        }
        // add a request filter for tacking on the A/D auth token if the current authentication
        // mode is active directory
        AuthTokenRequestFilter requestFilter = new AuthTokenRequestFilter(accessToken);
        return client.withRequestFilterFirst(requestFilter);
    }

    @NotNull
    public static ComputeManagementClient getComputeManagementClient(@NotNull String subscriptionId,
                                                                     @NotNull String managementCertificate,
                                                                     @NotNull String serviceManagementUrl)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException,
            XPathExpressionException, ParserConfigurationException, SAXException, AzureCmdException {
        Configuration configuration = getConfigurationFromCertificate(subscriptionId, managementCertificate,
                serviceManagementUrl);

        if (configuration == null) {
            throw new AzureCmdException("Unable to instantiate Configuration");
        }

        ComputeManagementClient client = ComputeManagementService.create(configuration);

        if (client == null) {
            throw new AzureCmdException("Unable to instantiate Compute Management client");
        }

        return client;
    }

    @NotNull
    public static StorageManagementClient getStorageManagementClient(@NotNull String subscriptionId,
                                                                     @NotNull String accessToken)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException,
            XPathExpressionException, ParserConfigurationException, SAXException, AzureCmdException {
        Configuration configuration = getConfigurationFromAuthToken(subscriptionId);

        if (configuration == null) {
            throw new AzureCmdException("Unable to instantiate Configuration");
        }

        StorageManagementClient client = StorageManagementService.create(configuration);

        if (client == null) {
            throw new AzureCmdException("Unable to instantiate Storage Management client");
        }
        // add a request filter for tacking on the A/D auth token if the current authentication
        // mode is active directory
        AuthTokenRequestFilter requestFilter = new AuthTokenRequestFilter(accessToken);
        return client.withRequestFilterFirst(requestFilter);
    }

    @NotNull
    public static StorageManagementClient getStorageManagementClient(@NotNull String subscriptionId,
                                                                     @NotNull String managementCertificate,
                                                                     @NotNull String serviceManagementUrl)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException,
            XPathExpressionException, ParserConfigurationException, SAXException, AzureCmdException {
        Configuration configuration = getConfigurationFromCertificate(subscriptionId, managementCertificate,
                serviceManagementUrl);

        if (configuration == null) {
            throw new AzureCmdException("Unable to instantiate Configuration");
        }

        StorageManagementClient client = StorageManagementService.create(configuration);

        if (client == null) {
            throw new AzureCmdException("Unable to instantiate Storage Management client");
        }

        return client;
    }

    @NotNull
    public static NetworkManagementClient getNetworkManagementClient(@NotNull String subscriptionId,
                                                                     @NotNull String accessToken)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException,
            XPathExpressionException, ParserConfigurationException, SAXException, AzureCmdException {
        Configuration configuration = getConfigurationFromAuthToken(subscriptionId);

        if (configuration == null) {
            throw new AzureCmdException("Unable to instantiate Configuration");
        }

        NetworkManagementClient client = NetworkManagementService.create(configuration);

        if (client == null) {
            throw new AzureCmdException("Unable to instantiate Network Management client");
        }
        // add a request filter for tacking on the A/D auth token if the current authentication
        // mode is active directory
        AuthTokenRequestFilter requestFilter = new AuthTokenRequestFilter(accessToken);
        return client.withRequestFilterFirst(requestFilter);
    }

    @NotNull
    public static NetworkManagementClient getNetworkManagementClient(@NotNull String subscriptionId,
                                                                     @NotNull String managementCertificate,
                                                                     @NotNull String serviceManagementUrl)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException,
            XPathExpressionException, ParserConfigurationException, SAXException, AzureCmdException {
        Configuration configuration = getConfigurationFromCertificate(subscriptionId, managementCertificate,
                serviceManagementUrl);

        if (configuration == null) {
            throw new AzureCmdException("Unable to instantiate Configuration");
        }

        NetworkManagementClient client = NetworkManagementService.create(configuration);

        if (client == null) {
            throw new AzureCmdException("Unable to instantiate Network Management client");
        }

        return client;
    }

    @NotNull
    public static WebSiteManagementClient getWebSiteManagementClient(@NotNull String subscriptionId,
                                                                     @NotNull String accessToken)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException,
            XPathExpressionException, ParserConfigurationException, SAXException, AzureCmdException {
        Configuration configuration = getConfigurationFromAuthToken(subscriptionId);

        if (configuration == null) {
            throw new AzureCmdException("Unable to instantiate Configuration");
        }

        WebSiteManagementClient client = WebSiteManagementService.create(configuration);

        if (client == null) {
            throw new AzureCmdException("Unable to instantiate Web Site Management client");
        }
        // add a request filter for tacking on the A/D auth token if the current authentication
        // mode is active directory
        AuthTokenRequestFilter requestFilter = new AuthTokenRequestFilter(accessToken);
        return client.withRequestFilterFirst(requestFilter);
    }

    @NotNull
    public static WebSiteManagementClient getWebSiteManagementClient(@NotNull String subscriptionId,
                                                                     @NotNull String managementCertificate,
                                                                     @NotNull String serviceManagementUrl)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException,
            XPathExpressionException, ParserConfigurationException, SAXException, AzureCmdException {
        Configuration configuration = getConfigurationFromCertificate(subscriptionId, managementCertificate,
                serviceManagementUrl);

        if (configuration == null) {
            throw new AzureCmdException("Unable to instantiate Configuration");
        }

        WebSiteManagementClient client = WebSiteManagementService.create(configuration);

        if (client == null) {
            throw new AzureCmdException("Unable to instantiate Web Site Management client");
        }

        return client;
    }

    @NotNull
    public static ManagementClient getManagementClient(@NotNull String subscriptionId,
                                                       @NotNull String accessToken)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException,
            XPathExpressionException, ParserConfigurationException, SAXException, AzureCmdException {
        Configuration configuration = getConfigurationFromAuthToken(subscriptionId);

        if (configuration == null) {
            throw new AzureCmdException("Unable to instantiate Configuration");
        }

        ManagementClient client = ManagementService.create(configuration);

        if (client == null) {
            throw new AzureCmdException("Unable to instantiate Management client");
        }
        // add a request filter for tacking on the A/D auth token if the current authentication
        // mode is active directory
        AuthTokenRequestFilter requestFilter = new AuthTokenRequestFilter(accessToken);
        return client.withRequestFilterFirst(requestFilter);
    }

    @NotNull
    public static ManagementClient getManagementClient(@NotNull String subscriptionId,
                                                       @NotNull String managementCertificate,
                                                       @NotNull String serviceManagementUrl)
            throws IOException, CertificateException, NoSuchAlgorithmException, KeyStoreException,
            XPathExpressionException, ParserConfigurationException, SAXException, AzureCmdException {
        Configuration configuration = getConfigurationFromCertificate(subscriptionId, managementCertificate,
                serviceManagementUrl);

        if (configuration == null) {
            throw new AzureCmdException("Unable to instantiate Configuration");
        }

        ManagementClient client = ManagementService.create(configuration);

        if (client == null) {
            throw new AzureCmdException("Unable to instantiate Management client");
        }

        return client;
    }

    @NotNull
    public static CloudStorageAccount getCloudStorageAccount(@NotNull ClientStorageAccount storageAccount)
            throws URISyntaxException, InvalidKeyException {
        return CloudStorageAccount.parse(storageAccount.getConnectionString());
    }

    @NotNull
    private static CloudBlobClient getCloudBlobClient(@NotNull ClientStorageAccount storageAccount)
            throws Exception {
        CloudStorageAccount csa = getCloudStorageAccount(storageAccount);

        return csa.createCloudBlobClient();
    }

    @NotNull
    private static HostedServiceOperations getHostedServiceOperations(@NotNull ComputeManagementClient client)
            throws Exception {
        HostedServiceOperations hso = client.getHostedServicesOperations();

        if (hso == null) {
            throw new Exception("Unable to retrieve Hosted Services information");
        }

        return hso;
    }

    @NotNull
    private static DeploymentOperations getDeploymentOperations(@NotNull ComputeManagementClient client)
            throws Exception {
        DeploymentOperations dop = client.getDeploymentsOperations();

        if (dop == null) {
            throw new Exception("Unable to retrieve Deployment information");
        }

        return dop;
    }

    @NotNull
    private static VirtualMachineOperations getVirtualMachineOperations(@NotNull ComputeManagementClient client)
            throws Exception {
        VirtualMachineOperations vmo = client.getVirtualMachinesOperations();

        if (vmo == null) {
            throw new Exception("Unable to retrieve Virtual Machines Information");
        }

        return vmo;
    }

    @NotNull
    private static VirtualMachineOSImageOperations getVirtualMachineOSImageOperations(@NotNull ComputeManagementClient client)
            throws Exception {
        VirtualMachineOSImageOperations vmosio = client.getVirtualMachineOSImagesOperations();

        if (vmosio == null) {
            throw new Exception("Unable to retrieve OS Images information");
        }

        return vmosio;
    }

    @NotNull
    private static VirtualMachineVMImageOperations getVirtualMachineVMImageOperations(@NotNull ComputeManagementClient client)
            throws Exception {
        VirtualMachineVMImageOperations vmvmio = client.getVirtualMachineVMImagesOperations();

        if (vmvmio == null) {
            throw new Exception("Unable to retrieve VM Images information");
        }

        return vmvmio;
    }

    @NotNull
    private static RoleSizeOperations getRoleSizeOperations(@NotNull ManagementClient client)
            throws Exception {
        RoleSizeOperations rso = client.getRoleSizesOperations();

        if (rso == null) {
            throw new Exception("Unable to retrieve Role Sizes information");
        }

        return rso;
    }

    @NotNull
    private static LocationOperations getLocationsOperations(@NotNull ManagementClient client)
            throws Exception {
        LocationOperations lo = client.getLocationsOperations();

        if (lo == null) {
            throw new Exception("Unable to retrieve Locations information");
        }

        return lo;
    }

    @NotNull
    private static AffinityGroupOperations getAffinityGroupOperations(@NotNull ManagementClient client)
            throws Exception {
        AffinityGroupOperations ago = client.getAffinityGroupsOperations();

        if (ago == null) {
            throw new Exception("Unable to retrieve Affinity Groups information");
        }

        return ago;
    }

    @NotNull
    private static StorageAccountOperations getStorageAccountOperations(@NotNull StorageManagementClient client)
            throws Exception {
        StorageAccountOperations sao = client.getStorageAccountsOperations();

        if (sao == null) {
            throw new Exception("Unable to retrieve Storage Accounts information");
        }

        return sao;
    }

    @NotNull
    private static NetworkOperations getNetworkOperations(@NotNull NetworkManagementClient client)
            throws Exception {
        NetworkOperations no = client.getNetworksOperations();

        if (no == null) {
            throw new Exception("Unable to retrieve Network information");
        }

        return no;
    }

    @NotNull
    private static ServiceCertificateOperations getServiceCertificateOperations(@NotNull ComputeManagementClient client)
            throws Exception {
        ServiceCertificateOperations sco = client.getServiceCertificatesOperations();

        if (sco == null) {
            throw new Exception("Unable to retrieve Service Certificate information");
        }

        return sco;
    }

    @NotNull
    private static WebSpaceOperations getWebSpaceOperations(@NotNull WebSiteManagementClient client)
            throws Exception {
        WebSpaceOperations wso = client.getWebSpacesOperations();

        if (wso == null) {
            throw new Exception("Unable to retrieve Web Space information");
        }

        return wso;
    }

    @NotNull
    private static WebHostingPlanOperations getWebHostingPlanOperations(@NotNull WebSiteManagementClient client)
            throws Exception {
        WebHostingPlanOperations whpo = client.getWebHostingPlansOperations();

        if (whpo == null) {
            throw new Exception("Unable to retrieve Web Hosting Plan information");
        }

        return whpo;
    }

    @NotNull
    private static WebSiteOperations getWebSiteOperations(@NotNull final WebSiteManagementClient client)
            throws Exception {
        /*
        final WebSiteOperations wsoInternal = client.getWebSitesOperations();

        if (wsoInternal == null) {
            throw new Exception("Unable to retrieve Web Site information");
        }

        return wso;
        */

        return new WebSiteOperationsImpl2((WebSiteManagementClientImpl) client);
    }

    @NotNull
    private static HostedServiceListResponse getHostedServices(@NotNull ComputeManagementClient client)
            throws Exception {
        HostedServiceOperations hso = getHostedServiceOperations(client);

        HostedServiceListResponse hslr = hso.list();

        if (hslr == null) {
            throw new Exception("Unable to retrieve Hosted Services information");
        }

        return hslr;
    }

    @NotNull
    private static ListenableFuture<DeploymentGetResponse> getDeploymentAsync(@NotNull final ComputeManagementClient client,
                                                                              @NotNull final String serviceName,
                                                                              @NotNull final DeploymentSlot slot) {
        final SettableFuture<DeploymentGetResponse> future = SettableFuture.create();

        DefaultLoader.getIdeHelper().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
                try {
                    future.set(getDeployment(client, serviceName, slot));
                } catch (Exception e) {
                    future.setException(e);
                }
            }
        });

        return future;
    }

    @NotNull
    private static DeploymentGetResponse getDeployment(@NotNull ComputeManagementClient client,
                                                       @NotNull String serviceName,
                                                       @NotNull DeploymentSlot slot)
            throws Exception {
        try {
            DeploymentGetResponse dgr = getDeploymentOperations(client).getBySlot(serviceName, slot);

            if (dgr == null) {
                throw new Exception("Unable to retrieve Deployment information");
            }

            return dgr;
        } catch (ServiceException se) {
            if (se.getHttpStatusCode() == 404) {
                return new DeploymentGetResponse();
            } else {
                throw se;
            }
        }
    }

    @NotNull
    private static DeploymentGetResponse getDeployment(@NotNull ComputeManagementClient client,
                                                       @NotNull VirtualMachine vm)
            throws Exception {
        return getDeployment(client, vm.getServiceName(), DeploymentSlot.Production);
    }

    @NotNull
    private static StorageAccountListResponse getStorageAccounts(@NotNull StorageManagementClient client) throws Exception {
        StorageAccountListResponse salr = getStorageAccountOperations(client).list();

        if (salr == null) {
            throw new Exception("Unable to retrieve Storage Accounts information");
        }

        return salr;
    }

    @NotNull
    private static ListenableFuture<StorageAccount> getStorageAccountAsync(@NotNull final String subscriptionId,
                                                                           @NotNull final StorageManagementClient client,
                                                                           @NotNull final com.microsoft.windowsazure.management.storage.models.StorageAccount storageAccount)
            throws Exception {
        final SettableFuture<StorageAccount> future = SettableFuture.create();

        DefaultLoader.getIdeHelper().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
                try {
                    future.set(getStorageAccount(subscriptionId, client, storageAccount));
                } catch (Exception e) {
                    future.setException(e);
                }
            }
        });

        return future;
    }

    @NotNull
    private static StorageAccount getStorageAccount(@NotNull String subscriptionId,
                                                    @NotNull StorageManagementClient client,
                                                    @NotNull com.microsoft.windowsazure.management.storage.models.StorageAccount storageAccount) throws Exception {
        String primaryKey = "";
        String secondaryKey = "";

        if (storageAccount.getName() != null) {
            StorageAccountGetKeysResponse sak = getStorageAccountKeys(client, storageAccount.getName());

            primaryKey = sak.getPrimaryKey();
            secondaryKey = sak.getSecondaryKey();
        }

        StorageAccountProperties sap = storageAccount.getProperties() != null ?
                storageAccount.getProperties() :
                new StorageAccountProperties();
        String blobsUri = "";
        String queuesUri = "";
        String tablesUri = "";

        ArrayList<URI> endpoints = sap.getEndpoints();

        if (endpoints != null && endpoints.size() > 0) {
            blobsUri = endpoints.get(0).toString();

            if (endpoints.size() > 1) {
                queuesUri = endpoints.get(1).toString();

                if (endpoints.size() > 2) {
                    tablesUri = endpoints.get(2).toString();
                }
            }
        }

        StorageAccount sa = new StorageAccount(Strings.nullToEmpty(storageAccount.getName()), subscriptionId);

        sa.setPrimaryKey(Strings.nullToEmpty(primaryKey));
        sa.setProtocol("https");
        sa.setBlobsUri(blobsUri);
        sa.setQueuesUri(queuesUri);
        sa.setTablesUri(tablesUri);
        sa.setUseCustomEndpoints(true);

        sa.setType(Strings.nullToEmpty(sap.getAccountType()));
        sa.setDescription(Strings.nullToEmpty(sap.getDescription()));
        sa.setLabel(Strings.nullToEmpty(sap.getLabel()));
        sa.setStatus(sap.getStatus() != null ? sap.getStatus().toString() : "");
        sa.setLocation(Strings.nullToEmpty(sap.getLocation()));
        sa.setAffinityGroup(Strings.nullToEmpty(sap.getAffinityGroup()));
        sa.setSecondaryKey(Strings.nullToEmpty(secondaryKey));
        sa.setManagementUri(storageAccount.getUri() != null ? storageAccount.getUri().toString() : "");
        sa.setPrimaryRegion(Strings.nullToEmpty(sap.getGeoPrimaryRegion()));
        sa.setPrimaryRegionStatus(sap.getStatusOfGeoPrimaryRegion() != null ? sap.getStatusOfGeoPrimaryRegion().toString() : "");
        sa.setSecondaryRegion(Strings.nullToEmpty(sap.getGeoSecondaryRegion()));
        sa.setSecondaryRegionStatus(sap.getStatusOfGeoSecondaryRegion() != null ? sap.getStatusOfGeoSecondaryRegion().toString() : "");
        sa.setLastFailover(sap.getLastGeoFailoverTime() != null ? sap.getLastGeoFailoverTime() : new GregorianCalendar());

        return sa;
    }

    @NotNull
    private static StorageAccountGetKeysResponse getStorageAccountKeys(@NotNull StorageManagementClient client,
                                                                       @NotNull String storageName)
            throws Exception {
        StorageAccountGetKeysResponse sagkr = getStorageAccountOperations(client).getKeys(storageName);

        if (sagkr == null) {
            throw new Exception("Unable to retrieve Storage Account Keys information");
        }

        return sagkr;
    }

    @NotNull
    private static List<Role> getVMDeploymentRoles(@NotNull DeploymentGetResponse deployment) throws Exception {
        ArrayList<Role> roles = deployment.getRoles();

        if (roles == null) {
            throw new Exception("Invalid Virtual Machine information. No Roles match the VM data.");
        }

        return roles;
    }

    @NotNull
    private static NetworkListResponse getNetworks(@NotNull NetworkManagementClient client) throws Exception {
        NetworkListResponse nlr = getNetworkOperations(client).list();

        if (nlr == null) {
            throw new Exception("Unable to retrieve Networks information");
        }

        return nlr;
    }

    private static void validateOperationStatus(@Nullable OperationStatusResponse osr) throws Exception {
        if (osr == null) {
            throw new Exception("Unable to retrieve Operation Status");
        }

        if (osr.getError() != null) {
            throw new Exception(osr.getError().getMessage());
        }
    }

    private static void validateOperationStatus(@Nullable WebSiteOperationStatusResponse osr) throws Exception {
        if (osr == null) {
            throw new Exception("Unable to retrieve Operation Status");
        }

        if (osr.getErrors() != null && osr.getErrors().size() > 0) {
            throw new Exception(osr.getErrors().get(0).getMessage());
        }
    }

    @NotNull
    private static WebSpacesListResponse getWebSpaces(@NotNull WebSiteManagementClient client) throws Exception {
        WebSpacesListResponse wslr = getWebSpaceOperations(client).list();

        if (wslr == null) {
            throw new Exception("Unable to retrieve Web Spaces information");
        }

        return wslr;
    }

    @NotNull
    private static WebSpacesListWebSitesResponse getWebSites(@NotNull WebSiteManagementClient client,
                                                             @NotNull String webSpaceName)
            throws Exception {
        WebSiteListParameters webSiteListParameters = new WebSiteListParameters();
        webSiteListParameters.getPropertiesToInclude().add("Name");
        webSiteListParameters.getPropertiesToInclude().add("WebSpace");
        webSiteListParameters.getPropertiesToInclude().add("Status");
        webSiteListParameters.getPropertiesToInclude().add("Url");

        WebSpacesListWebSitesResponse wslwsr = getWebSpaceOperations(client).listWebSites(webSpaceName,
                webSiteListParameters);

        if (wslwsr == null) {
            throw new Exception("Unable to retrieve Web Sites information");
        }

        return wslwsr;
    }

    @NotNull
    private static WebHostingPlanListResponse getWebHostingPlans(@NotNull WebSiteManagementClient client,
                                                                 @NotNull String webSpaceName)
            throws Exception {
        WebHostingPlanListResponse whplr = getWebHostingPlanOperations(client).list(webSpaceName);

        if (whplr == null) {
            throw new Exception("Unable to retrieve Web Hosting Plans information");
        }

        return whplr;
    }

    @NotNull
    private static WebSiteGetConfigurationResponse getWebSiteConfiguration(@NotNull WebSiteManagementClient client,
                                                                           @NotNull String webSpaceName,
                                                                           @NotNull String webSiteName)
            throws Exception {
        WebSiteGetConfigurationResponse wsgcr = getWebSiteOperations(client).getConfiguration(webSpaceName, webSiteName);

        if (wsgcr == null) {
            throw new Exception("Unable to retrieve Web Site Configuration information");
        }

        return wsgcr;
    }

    @NotNull
    private static void updateWebSiteConfiguration(@NotNull WebSiteManagementClient client,
                                                   @NotNull String webSpaceName,
                                                   @NotNull String webSiteName,
                                                   @NotNull WebSiteConfiguration webSiteConfiguration)
            throws Exception {
        WebSiteUpdateConfigurationParameters wsucp = loadWebSiteUpdateConfigurationParameters(webSiteConfiguration);

        OperationResponse or = getWebSiteOperations(client).updateConfiguration(webSpaceName, webSiteName, wsucp);

        //WebSiteOperationStatusResponse osr = getOperationStatusResponse(client, webSpaceName, webSiteName, or);
        //validateOperationStatus(osr);
    }

    @NotNull
    private static WebSiteGetPublishProfileResponse getWebSitePublishProfile(@NotNull WebSiteManagementClient client,
                                                                             @NotNull String webSpaceName,
                                                                             @NotNull String webSiteName)
            throws Exception {
        WebSiteGetPublishProfileResponse wsgppr = getWebSiteOperations(client).getPublishProfile(webSpaceName, webSiteName);

        if (wsgppr == null) {
            throw new Exception("Unable to retrieve Web Site Publish Profile information");
        }

        return wsgppr;
    }

    @Nullable
    private static OperationStatusResponse getOperationStatusResponse(@NotNull ComputeManagementClient client,
                                                                      @NotNull OperationResponse or)
            throws InterruptedException, java.util.concurrent.ExecutionException, ServiceException {
        OperationStatusResponse osr = client.getOperationStatusAsync(or.getRequestId()).get();
        int delayInSeconds = 30;

        if (client.getLongRunningOperationInitialTimeout() >= 0) {
            delayInSeconds = client.getLongRunningOperationInitialTimeout();
        }

        while (osr.getStatus() == OperationStatus.InProgress) {
            Thread.sleep(delayInSeconds * 1000);
            osr = client.getOperationStatusAsync(or.getRequestId()).get();
            delayInSeconds = 30;

            if (client.getLongRunningOperationRetryTimeout() >= 0) {
                delayInSeconds = client.getLongRunningOperationRetryTimeout();
            }
        }

        if (osr.getStatus() != OperationStatus.Succeeded) {
            if (osr.getError() != null) {
                ServiceException ex = new ServiceException(osr.getError().getCode() + " : " + osr.getError().getMessage());
                CloudError cloudError = new CloudError();
                cloudError.setCode(osr.getError().getCode());
                cloudError.setMessage(osr.getError().getMessage());
                ex.setError(cloudError);
                throw ex;
            } else {
                throw new ServiceException("");
            }
        }

        return osr;
    }

    @Nullable
    private static OperationStatusResponse getOperationStatusResponse(@NotNull StorageManagementClient client,
                                                                      @NotNull OperationResponse or)
            throws InterruptedException, java.util.concurrent.ExecutionException, ServiceException {
        OperationStatusResponse osr = client.getOperationStatusAsync(or.getRequestId()).get();
        int delayInSeconds = 30;

        if (client.getLongRunningOperationInitialTimeout() >= 0) {
            delayInSeconds = client.getLongRunningOperationInitialTimeout();
        }

        while (osr.getStatus() == OperationStatus.InProgress) {
            Thread.sleep(delayInSeconds * 1000);
            osr = client.getOperationStatusAsync(or.getRequestId()).get();
            delayInSeconds = 30;

            if (client.getLongRunningOperationRetryTimeout() >= 0) {
                delayInSeconds = client.getLongRunningOperationRetryTimeout();
            }
        }

        if (osr.getStatus() != OperationStatus.Succeeded) {
            if (osr.getError() != null) {
                ServiceException ex = new ServiceException(osr.getError().getCode() + " : " + osr.getError().getMessage());
                CloudError cloudError = new CloudError();
                cloudError.setCode(osr.getError().getCode());
                cloudError.setMessage(osr.getError().getMessage());
                ex.setError(cloudError);
                throw ex;
            } else {
                throw new ServiceException("");
            }
        }

        return osr;
    }

    @Nullable
    private static WebSiteOperationStatusResponse getOperationStatusResponse(@NotNull WebSiteManagementClient client,
                                                                             @NotNull String webSpaceName,
                                                                             @NotNull String webSiteName,
                                                                             @NotNull OperationResponse or)
            throws InterruptedException, java.util.concurrent.ExecutionException, ServiceException {
        WebSiteOperationStatusResponse osr = client.getOperationStatusAsync(webSpaceName, webSiteName, or.getRequestId()).get();
        int delayInSeconds = 30;

        if (client.getLongRunningOperationInitialTimeout() >= 0) {
            delayInSeconds = client.getLongRunningOperationInitialTimeout();
        }

        while (osr.getStatus() == WebSiteOperationStatus.InProgress) {
            Thread.sleep(delayInSeconds * 1000);
            osr = client.getOperationStatusAsync(webSpaceName, webSiteName, or.getRequestId()).get();
            delayInSeconds = 30;

            if (client.getLongRunningOperationRetryTimeout() >= 0) {
                delayInSeconds = client.getLongRunningOperationRetryTimeout();
            }
        }

        if (osr.getStatus() != WebSiteOperationStatus.Succeeded) {
            if (osr.getErrors() != null && osr.getErrors().size() > 0) {
                ServiceException ex = new ServiceException(osr.getErrors().get(0).getCode() + " : " + osr.getErrors().get(0).getMessage());
                CloudError cloudError = new CloudError();
                cloudError.setCode(osr.getErrors().get(0).getCode());
                cloudError.setMessage(osr.getErrors().get(0).getMessage());
                ex.setError(cloudError);
                throw ex;
            } else {
                throw new ServiceException("");
            }
        }

        return osr;
    }

    @NotNull
    private static CloudService loadDeployment(@NotNull DeploymentGetResponse deployment,
                                               @NotNull CloudService cloudService)
            throws Exception {
        if (deployment.getDeploymentSlot() != null) {
            CloudService.Deployment dep;

            switch (deployment.getDeploymentSlot()) {
                case Production:
                    dep = cloudService.getProductionDeployment();
                    break;
                case Staging:
                    dep = cloudService.getStagingDeployment();
                    break;
                default:
                    return cloudService;
            }

            dep.setName(deployment.getName() != null ? deployment.getName() : "");
            dep.setVirtualNetwork(deployment.getVirtualNetworkName() != null ? deployment.getVirtualNetworkName() : "");

            if (deployment.getRoles() != null) {
                Set<String> virtualMachines = dep.getVirtualMachines();
                Set<String> computeRoles = dep.getComputeRoles();
                Set<String> availabilitySets = dep.getAvailabilitySets();

                for (Role role : deployment.getRoles()) {
                    if (role.getRoleType() != null && role.getRoleType().equals(PERSISTENT_VM_ROLE)) {
                        if (role.getRoleName() != null && !role.getRoleName().isEmpty()) {
                            virtualMachines.add(role.getRoleName());
                        }

                        if (role.getAvailabilitySetName() != null && !role.getAvailabilitySetName().isEmpty()) {
                            availabilitySets.add(role.getAvailabilitySetName());
                        }
                    } else {
                        if (role.getRoleName() != null && !role.getRoleName().isEmpty()) {
                            computeRoles.add(role.getRoleName());
                        }
                    }
                }
            }
        }

        return cloudService;
    }


    @NotNull
    private static List<VirtualMachine> loadVirtualMachines(@NotNull ComputeManagementClient client,
                                                            @NotNull String subscriptionId,
                                                            @NotNull String serviceName,
                                                            @NotNull List<VirtualMachine> vmList)
            throws Exception {
        DeploymentGetResponse deployment = getDeployment(client, serviceName, DeploymentSlot.Production);

        if (deployment.getRoles() == null) {
            return vmList;
        }

        for (Role role : deployment.getRoles()) {
            if (role.getRoleType() != null
                    && role.getRoleType().equals(PERSISTENT_VM_ROLE)) {
                VirtualMachine vm = new VirtualMachine(
                        role.getRoleName() != null ? role.getRoleName() : "",
                        serviceName,
                        deployment.getName() != null ? deployment.getName() : "",
                        role.getAvailabilitySetName() != null ? role.getAvailabilitySetName() : "",
                        "",
                        role.getRoleSize() != null ? role.getRoleSize() : "",
                        getVMStatus(deployment, role),
                        subscriptionId);

                loadNetworkConfiguration(role, vm);

                vmList.add(vm);
            }
        }

        return vmList;
    }

    private static void loadNetworkConfiguration(@NotNull Role role, @NotNull VirtualMachine vm) {
        if (role.getConfigurationSets() != null) {
            List<Endpoint> endpoints = vm.getEndpoints();

            for (ConfigurationSet configurationSet : role.getConfigurationSets()) {
                if (configurationSet.getConfigurationSetType() != null
                        && configurationSet.getConfigurationSetType().equals(NETWORK_CONFIGURATION)) {
                    if (configurationSet.getInputEndpoints() != null) {
                        for (InputEndpoint inputEndpoint : configurationSet.getInputEndpoints()) {
                            endpoints.add(new Endpoint(
                                    inputEndpoint.getName() != null ? inputEndpoint.getName() : "",
                                    inputEndpoint.getProtocol() != null ? inputEndpoint.getProtocol() : "",
                                    inputEndpoint.getLocalPort(),
                                    inputEndpoint.getPort()));
                        }
                    }

                    if (configurationSet.getSubnetNames() != null && configurationSet.getSubnetNames().size() == 1) {
                        vm.setSubnet(configurationSet.getSubnetNames().get(0));
                    }

                    break;
                }
            }
        }
    }

    private static void deleteVMRole(@NotNull ComputeManagementClient client, @NotNull String serviceName,
                                     @NotNull String deploymentName, @NotNull String virtualMachineName,
                                     boolean deleteFromStorage)
            throws Exception {
        VirtualMachineOperations vmo = getVirtualMachineOperations(client);

        OperationStatusResponse osr = vmo.delete(serviceName, deploymentName, virtualMachineName, deleteFromStorage);

        validateOperationStatus(osr);
    }

    private static void deleteDeployment(@NotNull ComputeManagementClient client, @NotNull String serviceName,
                                         @NotNull String deploymentName, boolean deleteFromStorage)
            throws Exception {
        DeploymentOperations dop = getDeploymentOperations(client);

        OperationStatusResponse osr = dop.deleteByName(serviceName, deploymentName, deleteFromStorage);

        validateOperationStatus(osr);
    }


    @NotNull
    private static String getMediaLocation(@NotNull VirtualMachine virtualMachine,
                                           @NotNull StorageAccount storageAccount)
            throws Exception {
        Calendar calendar = GregorianCalendar.getInstance();
        String blobName = String.format("%s-%s-0-%04d%02d%02d%02d%02d%02d%04d.vhd",
                virtualMachine.getServiceName(),
                virtualMachine.getName(),
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DATE),
                calendar.get(Calendar.HOUR_OF_DAY),
                calendar.get(Calendar.MINUTE),
                calendar.get(Calendar.SECOND),
                calendar.get(Calendar.MILLISECOND));

        CloudBlobClient cloudBlobClient = getCloudBlobClient(storageAccount);

        CloudBlobContainer container = cloudBlobClient.getContainerReference("vhds");
        container.createIfNotExists();

        return container.getUri().toString() + "/" + blobName;
    }

    @NotNull
    private static ListenableFuture<List<VirtualMachineImage>> getOSImagesAsync(
            @NotNull final ComputeManagementClient client) {
        final SettableFuture<List<VirtualMachineImage>> future = SettableFuture.create();

        DefaultLoader.getIdeHelper().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
                try {
                    future.set(getOSImages(client));
                } catch (Exception e) {
                    future.setException(e);
                }
            }
        });

        return future;
    }

    @NotNull
    private static List<VirtualMachineImage> getOSImages(@NotNull ComputeManagementClient client)
            throws Exception {
        List<VirtualMachineImage> vmImageList = new ArrayList<VirtualMachineImage>();

        VirtualMachineOSImageListResponse osImages = getVirtualMachineOSImageOperations(client).list();

        if (osImages != null) {
            for (VirtualMachineOSImageListResponse.VirtualMachineOSImage osImage : osImages) {
                vmImageList.add(
                        new VirtualMachineImage(
                                osImage.getName() != null ? osImage.getName() : "",
                                PLATFORM_IMAGE,
                                osImage.getCategory() != null ? osImage.getCategory() : "",
                                osImage.getPublisherName() != null ? osImage.getPublisherName() : "",
                                osImage.getPublishedDate() != null ?
                                        osImage.getPublishedDate() :
                                        GregorianCalendar.getInstance(),
                                osImage.getLabel() != null ? osImage.getLabel() : "",
                                osImage.getDescription() != null ? osImage.getDescription() : "",
                                osImage.getOperatingSystemType() != null ? osImage.getOperatingSystemType() : "",
                                osImage.getLocation() != null ? osImage.getLocation() : "",
                                osImage.getEula() != null ? osImage.getEula() : "",
                                osImage.getPrivacyUri() != null ? osImage.getPrivacyUri().toString() : "",
                                osImage.getPricingDetailUri() != null ? osImage.getPricingDetailUri().toString() : "",
                                osImage.getRecommendedVMSize() != null ? osImage.getRecommendedVMSize() : "",
                                osImage.isShowInGui() != null ? osImage.isShowInGui() : true));
            }
        }

        return vmImageList;
    }

    @NotNull
    private static ListenableFuture<List<VirtualMachineImage>> getVMImagesAsync(
            @NotNull final ComputeManagementClient client) {
        final SettableFuture<List<VirtualMachineImage>> future = SettableFuture.create();

        DefaultLoader.getIdeHelper().executeOnPooledThread(new Runnable() {
            @Override
            public void run() {
                try {
                    future.set(getVMImages(client));
                } catch (Exception e) {
                    future.setException(e);
                }
            }
        });

        return future;
    }

    @NotNull
    private static List<VirtualMachineImage> getVMImages(@NotNull ComputeManagementClient client)
            throws Exception {
        List<VirtualMachineImage> vmImageList = new ArrayList<VirtualMachineImage>();

        VirtualMachineVMImageListResponse vmImages = getVirtualMachineVMImageOperations(client).list();

        if (vmImages != null) {
            for (VirtualMachineVMImageListResponse.VirtualMachineVMImage vmImage : vmImages) {
                vmImageList.add(
                        new VirtualMachineImage(
                                vmImage.getName() != null ? vmImage.getName() : "",
                                USER_IMAGE,
                                vmImage.getCategory() != null ? vmImage.getCategory() : "",
                                vmImage.getPublisherName() != null ? vmImage.getPublisherName() : "",
                                vmImage.getPublishedDate() != null ?
                                        vmImage.getPublishedDate() :
                                        GregorianCalendar.getInstance(),
                                vmImage.getLabel() != null ? vmImage.getLabel() : "",
                                vmImage.getDescription() != null ? vmImage.getDescription() : "",
                                vmImage.getOSDiskConfiguration() != null
                                        && vmImage.getOSDiskConfiguration().getOperatingSystem() != null ?
                                        vmImage.getOSDiskConfiguration().getOperatingSystem() :
                                        "",
                                vmImage.getLocation() != null ? vmImage.getLocation() : "",
                                vmImage.getEula() != null ? vmImage.getEula() : "",
                                vmImage.getPrivacyUri() != null ? vmImage.getPrivacyUri().toString() : "",
                                vmImage.getPricingDetailLink() != null ? vmImage.getPricingDetailLink().toString() : "",
                                vmImage.getRecommendedVMSize() != null ? vmImage.getRecommendedVMSize() : "",
                                vmImage.isShowInGui() != null ? vmImage.isShowInGui() : true));
            }
        }

        return vmImageList;
    }

    @NotNull
    private static List<VirtualMachineSize> loadVMSizes(@NotNull ManagementClient client,
                                                        @NotNull List<VirtualMachineSize> vmSizeList)
            throws Exception {
        RoleSizeListResponse rslr = getRoleSizeOperations(client).list();

        if (rslr == null) {
            throw new Exception("Unable to retrieve Role Sizes information");
        }

        if (rslr.getRoleSizes() != null) {
            for (RoleSizeListResponse.RoleSize rs : rslr.getRoleSizes()) {
                if (rs.isSupportedByVirtualMachines()) {
                    vmSizeList.add(
                            new VirtualMachineSize(
                                    rs.getName() != null ? rs.getName() : "",
                                    rs.getLabel() != null ? rs.getLabel() : "",
                                    rs.getCores(),
                                    rs.getMemoryInMb()
                            ));
                }
            }
        }

        return vmSizeList;
    }

    @NotNull
    private static List<Location> loadLocations(@NotNull ManagementClient client,
                                                @NotNull List<Location> locationList)
            throws Exception {
        LocationsListResponse llr = getLocationsOperations(client).list();

        if (llr == null) {
            throw new Exception("Unable to retrieve Locations information");
        }

        if (llr.getLocations() != null) {
            for (LocationsListResponse.Location location : llr.getLocations()) {
                locationList.add(
                        new Location(
                                location.getName() != null ? location.getName() : "",
                                location.getDisplayName() != null ? location.getDisplayName() : ""
                        ));
            }
        }

        return locationList;
    }

    @NotNull
    private static List<AffinityGroup> loadAffinityGroups(@NotNull ManagementClient client,
                                                          @NotNull List<AffinityGroup> affinityGroupList)
            throws Exception {
        AffinityGroupListResponse aglr = getAffinityGroupOperations(client).list();

        if (aglr == null) {
            throw new Exception("Unable to retrieve Affinity Groups information");
        }

        if (aglr.getAffinityGroups() != null) {
            for (AffinityGroupListResponse.AffinityGroup ag : aglr.getAffinityGroups()) {
                affinityGroupList.add(
                        new AffinityGroup(
                                ag.getName() != null ? ag.getName() : "",
                                ag.getLabel() != null ? ag.getLabel() : "",
                                ag.getLocation() != null ? ag.getLocation() : ""
                        ));
            }
        }

        return affinityGroupList;
    }

    @NotNull
    private static WebSite loadWebSite(@NotNull String subscriptionId,
                                       @NotNull String webSpaceName,
                                       @NotNull com.microsoft.windowsazure.management.websites.models.WebSite webSite) {
        WebSite ws = new WebSite(Strings.nullToEmpty(webSite.getName()),
                webSpaceName,
                subscriptionId);

        return loadWebSite(ws, webSite);
    }

    @NotNull
    private static WebSite loadWebSite(@NotNull WebSite ws,
                                       @NotNull com.microsoft.windowsazure.management.websites.models.WebSite webSite) {
        ws.setStatus(Strings.nullToEmpty(webSite.getState()));
        ws.setUrl(webSite.getUri() != null ? webSite.getUri().toString() : "");

        return ws;
    }

    private static WebSiteConfiguration loadWebSiteConfiguration(@NotNull String subscriptionId,
                                                                 @NotNull String webSpaceName,
                                                                 @NotNull String webSiteName,
                                                                 @NotNull WebSiteGetConfigurationResponse webSiteConfiguration) {
        WebSiteConfiguration wsc = new WebSiteConfiguration(webSpaceName, webSiteName, subscriptionId);

        return loadWebSiteConfiguration(wsc, webSiteConfiguration);
    }

    private static WebSiteConfiguration loadWebSiteConfiguration(@NotNull WebSiteConfiguration wsc,
                                                                 @NotNull WebSiteGetConfigurationResponse webSiteConfiguration) {
        wsc.setNetFrameworkVersion(Strings.nullToEmpty(webSiteConfiguration.getNetFrameworkVersion()));
        wsc.setJavaVersion(Strings.nullToEmpty(webSiteConfiguration.getJavaVersion()));
        wsc.setJavaContainer(Strings.nullToEmpty(webSiteConfiguration.getJavaContainer()));
        wsc.setJavaContainerVersion(Strings.nullToEmpty(webSiteConfiguration.getJavaContainerVersion()));
        wsc.setPhpVersion(Strings.nullToEmpty(webSiteConfiguration.getPhpVersion()));
        wsc.setHttpLoggingEnabled(webSiteConfiguration.isHttpLoggingEnabled() != null ?
                webSiteConfiguration.isHttpLoggingEnabled() :
                false);
        wsc.setDetailedErrorLoggingEnabled(webSiteConfiguration.isDetailedErrorLoggingEnabled() != null ?
                webSiteConfiguration.isDetailedErrorLoggingEnabled() :
                false);
        wsc.setRequestTracingEnabled(webSiteConfiguration.isRequestTracingEnabled() != null ?
                webSiteConfiguration.isRequestTracingEnabled() :
                false);
        wsc.setRequestTracingExpirationTime(webSiteConfiguration.getRequestTracingExpirationTime() != null ?
                webSiteConfiguration.getRequestTracingExpirationTime() :
                new GregorianCalendar());
        wsc.setRemoteDebuggingEnabled(webSiteConfiguration.isRemoteDebuggingEnabled() != null ?
                webSiteConfiguration.isRemoteDebuggingEnabled() :
                false);

        List<ConnectionInfo> connectionInfoList = wsc.getConnectionInfoList();

        for (WebSiteGetConfigurationResponse.ConnectionStringInfo connectionStringInfo :
                webSiteConfiguration.getConnectionStrings()) {
            if (connectionStringInfo.getName() != null && connectionStringInfo.getType() != null &&
                    connectionStringInfo.getConnectionString() != null) {
                connectionInfoList.add(new ConnectionInfo(connectionStringInfo.getName(),
                        connectionStringInfo.getType().toString(),
                        connectionStringInfo.getConnectionString()));
            }
        }

        return wsc;
    }

    @NotNull
    private static WebSiteUpdateConfigurationParameters loadWebSiteUpdateConfigurationParameters(@NotNull WebSiteConfiguration webSiteConfiguration) {
        WebSiteUpdateConfigurationParameters wsucp = new WebSiteUpdateConfigurationParameters();

        String javaVersion = webSiteConfiguration.getJavaVersion();
        wsucp.setJavaVersion(javaVersion);

        if (!javaVersion.equals("")) {
            String javaContainer = webSiteConfiguration.getJavaContainer();
            wsucp.setJavaContainer(javaContainer);

            if (javaContainer.equals("TOMCAT")) {
                wsucp.setJavaContainerVersion("7.0.50");
            } else if (javaContainer.equals("JETTY")) {
                wsucp.setJavaContainerVersion("9.1.0.20131115");
            }
        }

        return wsucp;
    }

    private static void createVM(@NotNull ComputeManagementClient client,
                                 @NotNull VirtualMachineOperations vmo,
                                 @NotNull VirtualMachine virtualMachine,
                                 @NotNull VirtualMachineImage vmImage,
                                 @NotNull String mediaLocation,
                                 @NotNull String username,
                                 @NotNull String password,
                                 @NotNull byte[] certificate)
            throws Exception {
        VirtualMachineCreateParameters vmcp = new VirtualMachineCreateParameters(virtualMachine.getName());

        if (!virtualMachine.getAvailabilitySet().isEmpty()) {
            vmcp.setAvailabilitySetName(virtualMachine.getAvailabilitySet());
        }

        if (vmImage.getType().equals(USER_IMAGE)) {
            vmcp.setVMImageName(vmImage.getName());
            vmcp.setMediaLocation(new URI(mediaLocation));
        } else if (vmImage.getType().equals(PLATFORM_IMAGE)) {
            OSVirtualHardDisk osVHD = new OSVirtualHardDisk();
            osVHD.setSourceImageName(vmImage.getName());
            osVHD.setMediaLink(new URI(mediaLocation));
            vmcp.setOSVirtualHardDisk(osVHD);
        }

        vmcp.setRoleSize(virtualMachine.getSize());

        vmcp.getConfigurationSets().add(getProvisioningConfigurationSet(client, virtualMachine, vmImage,
                username, password, certificate));

        if (virtualMachine.getEndpoints().size() > 0 || !virtualMachine.getSubnet().isEmpty()) {
            vmcp.getConfigurationSets().add(getNetworkConfigurationSet(virtualMachine));
        }

        OperationStatusResponse osr = vmo.create(virtualMachine.getServiceName(), virtualMachine.getDeploymentName(), vmcp);

        validateOperationStatus(osr);
    }

    private static void createVMDeployment(@NotNull ComputeManagementClient client,
                                           @NotNull VirtualMachineOperations vmo,
                                           @NotNull VirtualMachine virtualMachine,
                                           @NotNull VirtualMachineImage vmImage,
                                           @NotNull String mediaLocation,
                                           @NotNull String virtualNetwork,
                                           @NotNull String username,
                                           @NotNull String password,
                                           @NotNull byte[] certificate)
            throws Exception {
        VirtualMachineCreateDeploymentParameters vmcdp = new VirtualMachineCreateDeploymentParameters();
        vmcdp.setName(virtualMachine.getName());
        vmcdp.setLabel(virtualMachine.getName());
        vmcdp.setDeploymentSlot(DeploymentSlot.Production);

        if (!virtualNetwork.isEmpty()) {
            vmcdp.setVirtualNetworkName(virtualNetwork);
        }

        Role role = new Role();
        role.setRoleName(virtualMachine.getName());

        if (!virtualMachine.getAvailabilitySet().isEmpty()) {
            role.setAvailabilitySetName(virtualMachine.getAvailabilitySet());
        }

        if (vmImage.getType().equals("User")) {
            role.setVMImageName(vmImage.getName());
            role.setMediaLocation(new URI(mediaLocation));
        } else if (vmImage.getType().equals("Platform")) {
            OSVirtualHardDisk osVHD = new OSVirtualHardDisk();
            osVHD.setSourceImageName(vmImage.getName());
            osVHD.setMediaLink(new URI(mediaLocation));
            role.setOSVirtualHardDisk(osVHD);
        }

        role.setRoleSize(virtualMachine.getSize());
        role.setRoleType(PERSISTENT_VM_ROLE);

        role.getConfigurationSets().add(getProvisioningConfigurationSet(client, virtualMachine, vmImage,
                username, password, certificate));

        if (virtualMachine.getEndpoints().size() > 0 || !virtualMachine.getSubnet().isEmpty()) {
            role.getConfigurationSets().add(getNetworkConfigurationSet(virtualMachine));
        }

        vmcdp.getRoles().add(role);

        OperationStatusResponse osr = vmo.createDeployment(virtualMachine.getServiceName(), vmcdp);

        validateOperationStatus(osr);
    }

    @NotNull
    private static ConfigurationSet getProvisioningConfigurationSet(@NotNull ComputeManagementClient client,
                                                                    @NotNull VirtualMachine virtualMachine,
                                                                    @NotNull VirtualMachineImage vmImage,
                                                                    @NotNull String username,
                                                                    @NotNull String password,
                                                                    @NotNull byte[] certificate)
            throws AzureCmdException {
        ConfigurationSet provConfSet = new ConfigurationSet();

        if (vmImage.getOperatingSystemType().equals(WINDOWS_OS_TYPE)) {
            provConfSet.setConfigurationSetType(WINDOWS_PROVISIONING_CONFIGURATION);
            provConfSet.setAdminUserName(username);
            provConfSet.setAdminPassword(password);
            provConfSet.setComputerName(String.format("%s-%s-%02d",
                    virtualMachine.getServiceName().substring(0, 5),
                    virtualMachine.getName().substring(0, 5),
                    1));
        } else if (vmImage.getOperatingSystemType().equals(LINUX_OS_TYPE)) {
            provConfSet.setConfigurationSetType(LINUX_PROVISIONING_CONFIGURATION);
            provConfSet.setUserName(username);

            if (!password.isEmpty()) {
                provConfSet.setUserPassword(password);
                provConfSet.setDisableSshPasswordAuthentication(false);
            }

            if (certificate.length > 0) {
                try {
                    String fingerprint = createServiceCertificate(virtualMachine.getServiceName(),
                            certificate,
                            "").execute(client);

                    SshSettings sshSettings = new SshSettings();
                    String keyLocation = String.format("/home/%s/.ssh/authorized_keys", username);
                    sshSettings.getPublicKeys().add(new SshSettingPublicKey(fingerprint, keyLocation));
                    provConfSet.setSshSettings(sshSettings);
                } catch (Throwable throwable) {
                    if (throwable instanceof AzureCmdException) {
                        throw (AzureCmdException) throwable;
                    } else if (throwable instanceof ExecutionException) {
                        throw new AzureCmdException(throwable.getCause().getMessage(), throwable.getCause());
                    }

                    throw new AzureCmdException(throwable.getMessage(), throwable);
                }
            }

            provConfSet.setHostName(String.format("%s-%s-%02d",
                    virtualMachine.getServiceName().substring(0, 5),
                    virtualMachine.getName().substring(0, 5),
                    1));
        }

        return provConfSet;
    }

    @NotNull
    private static ConfigurationSet getNetworkConfigurationSet(@NotNull VirtualMachine virtualMachine) {
        ConfigurationSet netConfSet = new ConfigurationSet();
        netConfSet.setConfigurationSetType(NETWORK_CONFIGURATION);
        ArrayList<InputEndpoint> inputEndpoints = netConfSet.getInputEndpoints();

        for (Endpoint endpoint : virtualMachine.getEndpoints()) {
            InputEndpoint inputEndpoint = new InputEndpoint();
            inputEndpoint.setName(endpoint.getName());
            inputEndpoint.setProtocol(endpoint.getProtocol());
            inputEndpoint.setLocalPort(endpoint.getPrivatePort());
            inputEndpoint.setPort(endpoint.getPublicPort());

            inputEndpoints.add(inputEndpoint);
        }

        if (!virtualMachine.getSubnet().isEmpty()) {
            netConfSet.getSubnetNames().add(virtualMachine.getSubnet());
        }

        return netConfSet;
    }

    @NotNull
    private static VirtualMachine.Status getVMStatus(@NotNull DeploymentGetResponse deployment, @NotNull Role role) {
        VirtualMachine.Status result = VirtualMachine.Status.Unknown;

        if (deployment.getRoleInstances() != null) {
            RoleInstance vmRoleInstance = null;

            for (RoleInstance roleInstance : deployment.getRoleInstances()) {
                if (roleInstance.getRoleName() != null && roleInstance.getRoleName().equals(role.getRoleName())) {
                    vmRoleInstance = roleInstance;
                    break;
                }
            }

            if (vmRoleInstance != null && vmRoleInstance.getInstanceStatus() != null) {
                result = getRoleStatus(vmRoleInstance.getInstanceStatus());
            }
        }

        return result;
    }

    @NotNull
    private static VirtualMachine.Status getRoleStatus(@NotNull String instanceStatus) {
        VirtualMachine.Status result = VirtualMachine.Status.Unknown;

        if (instanceStatus.equals(StatusLiterals.UNKNOWN)) {
            result = VirtualMachine.Status.Unknown;
        } else if (instanceStatus.equals(StatusLiterals.READY_ROLE)) {
            result = VirtualMachine.Status.Ready;
        } else if (instanceStatus.equals(StatusLiterals.STOPPED_VM)) {
            result = VirtualMachine.Status.Stopped;
        } else if (instanceStatus.equals(StatusLiterals.STOPPED_DEALLOCATED)) {
            result = VirtualMachine.Status.StoppedDeallocated;
        } else if (instanceStatus.equals(StatusLiterals.BUSY_ROLE)) {
            result = VirtualMachine.Status.Busy;
        } else if (instanceStatus.equals(StatusLiterals.CREATING_VM) ||
                instanceStatus.equals(StatusLiterals.CREATING_ROLE)) {
            result = VirtualMachine.Status.Creating;
        } else if (instanceStatus.equals(StatusLiterals.STARTING_VM) ||
                instanceStatus.equals(StatusLiterals.STARTING_ROLE)) {
            result = VirtualMachine.Status.Starting;
        } else if (instanceStatus.equals(StatusLiterals.STOPPING_VM) ||
                instanceStatus.equals(StatusLiterals.STOPPING_ROLE)) {
            result = VirtualMachine.Status.Stopping;
        } else if (instanceStatus.equals(StatusLiterals.DELETING_VM)) {
            result = VirtualMachine.Status.Deleting;
        } else if (instanceStatus.equals(StatusLiterals.RESTARTING_ROLE)) {
            result = VirtualMachine.Status.Restarting;
        } else if (instanceStatus.equals(StatusLiterals.CYCLING_ROLE)) {
            result = VirtualMachine.Status.Cycling;
        } else if (instanceStatus.equals(StatusLiterals.FAILED_STARTING_VM) ||
                instanceStatus.equals(StatusLiterals.FAILED_STARTING_ROLE)) {
            result = VirtualMachine.Status.FailedStarting;
        } else if (instanceStatus.equals(StatusLiterals.UNRESPONSIVE_ROLE)) {
            result = VirtualMachine.Status.Unresponsive;
        } else if (instanceStatus.equals(StatusLiterals.PREPARING)) {
            result = VirtualMachine.Status.Preparing;
        }

        return result;
    }

    @NotNull
    private static String bytesToHex(@NotNull byte[] bytes) {
        char[] hexChars = new char[bytes.length * 2];

        for (int j = 0; j < bytes.length; j++) {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }

        return new String(hexChars);
    }

    @Nullable
    private static Configuration getConfigurationFromAuthToken(@NotNull String subscriptionId)
            throws SAXException, ParserConfigurationException, XPathExpressionException, IOException {
        // NOTE: This implementation has to be considered as somewhat hacky. It relies on certain
        // internal implementation details of the Azure SDK for Java. For example we supply null
        // values for the key store location and password and specify a key store type value
        // though it will not be used. We also supply a no-op "credential provider". Ideally we want
        // the SDK to directly support the scenario we need.

        String azureServiceManagementUri = DefaultLoader.getPluginComponent().getSettings().getAzureServiceManagementUri();

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagerImpl.class.getClassLoader());

        try {
            // create a default configuration object
            Configuration configuration = ManagementConfiguration.configure(
                    URI.create(azureServiceManagementUri),
                    subscriptionId, null, null, KeyStoreType.pkcs12);

            if (configuration != null) {
                // replace the credential provider with a custom one that does nothing
                configuration.setProperty(
                        ManagementConfiguration.SUBSCRIPTION_CLOUD_CREDENTIALS,
                        new EmptyCloudCredentials(subscriptionId));

                // remove the SSL connection factory in case one was added; this is needed
                // in the case when the user switches from subscription based auth to A/D
                // sign-in because in that scenario the CertificateCloudCredentials class
                // would have added an SSL connection factory object to the configuration
                // object which would then be used when making the SSL call to the Azure
                // service management API. This tells us that the configuration object is
                // reused across calls to ManagementConfiguration.configure. The SSL connection
                // factory object so configured will attempt to use certificate based auth
                // which will fail since we don't have a certificate handy when using A/D auth.
                configuration.getProperties().remove(ApacheConfigurationProperties.PROPERTY_SSL_CONNECTION_SOCKET_FACTORY);
            }

            return configuration;
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    @Nullable
    private static Configuration getConfigurationFromCertificate(@NotNull String subscriptionId,
                                                                 @NotNull String managementCertificate,
                                                                 @NotNull String serviceManagementUrl)
            throws KeyStoreException, CertificateException, NoSuchAlgorithmException, IOException,
            ParserConfigurationException, XPathExpressionException, SAXException, AzureCmdException {
        String keyStorePath = File.createTempFile("azk", null).getPath();

        initKeyStore(
                managementCertificate,
                OpenSSLHelper.PASSWORD,
                keyStorePath,
                OpenSSLHelper.PASSWORD);

        ClassLoader old = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(AzureManagerImpl.class.getClassLoader());

        try {
            return ManagementConfiguration.configure(URI.create(serviceManagementUrl), subscriptionId, keyStorePath,
                    OpenSSLHelper.PASSWORD, KeyStoreType.pkcs12);
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    private static void initKeyStore(@NotNull String base64Certificate, @NotNull String certificatePwd,
                                     @NotNull String keyStorePath, @NotNull String keyStorePwd)
            throws KeyStoreException, IOException, NoSuchAlgorithmException, CertificateException {
        FileOutputStream keyStoreOutputStream = new FileOutputStream(keyStorePath);

        try {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(null, null);

            final byte[] decode = Base64.decode(base64Certificate);
            InputStream sslInputStream = new ByteArrayInputStream(decode);
            store.load(sslInputStream, certificatePwd.toCharArray());

            // we need to a create a physical key store as well here
            store.store(keyStoreOutputStream, keyStorePwd.toCharArray());
        } finally {
            keyStoreOutputStream.close();
        }
    }
}