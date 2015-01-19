package org.csanchez.jenkins.plugins.kubernetes;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;

import java.net.URISyntaxException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.ws.rs.ext.RuntimeDelegate;

import jenkins.model.Jenkins;

import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import com.github.kubernetes.java.client.exceptions.KubernetesClientException;
import com.github.kubernetes.java.client.interfaces.KubernetesAPIClientInterface;
import com.github.kubernetes.java.client.model.Container;
import com.github.kubernetes.java.client.model.Manifest;
import com.github.kubernetes.java.client.model.Pod;
import com.github.kubernetes.java.client.model.PodList;
import com.github.kubernetes.java.client.model.ReplicationController;
import com.github.kubernetes.java.client.model.Selector;
import com.github.kubernetes.java.client.model.State;
import com.github.kubernetes.java.client.v2.KubernetesApiClient;
import com.github.kubernetes.java.client.v2.RestFactory;
import com.google.common.base.MoreObjects;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.nirima.jenkins.plugins.docker.DockerTemplate;

/**
 * @author Carlos Sanchez <carlos@apache.org>
 */
public class KubernetesCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(KubernetesCloud.class.getName());

    public static final String CLOUD_ID_PREFIX = "kubernetes-";

    protected static final String DEFAULT_LABEL = "jenkins-slave";

    public final List<DockerTemplate> templates;
    public final String serverUrl;
    public final String username;
    public final String password;
    public final int containerCap;

    private transient KubernetesAPIClientInterface connection;

    @DataBoundConstructor
    public KubernetesCloud(String name, List<? extends DockerTemplate> templates, String serverUrl, String username,
            String password, String containerCapStr, int connectTimeout, int readTimeout) {
        super(name);

        Preconditions.checkNotNull(serverUrl);

        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
        if (templates != null)
            this.templates = new ArrayList<DockerTemplate>(templates);
        else
            this.templates = new ArrayList<DockerTemplate>();

        if (containerCapStr.equals("")) {
            this.containerCap = Integer.MAX_VALUE;
        } else {
            this.containerCap = Integer.parseInt(containerCapStr);
        }
    }

    public String getContainerCapStr() {
        if (containerCap == Integer.MAX_VALUE) {
            return "";
        } else {
            return String.valueOf(containerCap);
        }
    }

    /**
     * Connects to Docker.
     *
     * @return Docker client.
     */
    public KubernetesAPIClientInterface connect() {

        LOGGER.log(Level.FINE, "Building connection to Kubernetes host " + name + " URL " + serverUrl);

        if (connection == null) {
            synchronized (this) {
                if (connection != null)
                    return connection;

                RestFactory factory = new RestFactory(getClass().getClassLoader());
                // we need the RestEasy implementation, not the Jersey one
                // loaded by default from the thread classloader
                RuntimeDelegate.setInstance(new ResteasyProviderFactory());
                connection = new KubernetesApiClient(serverUrl.toString() + "/api/v1beta1/", username, password,
                        factory);
            }
        }
        return connection;

    }

    private String getIdForLabel(Label label) {
        if (label == null) {
            return "jenkins-slave";
        }
        return "jenkins-" + label.getName();
    }

    private ReplicationController getOrCreateReplicationController(Label label) throws KubernetesClientException {
        String id = getIdForLabel(label);
        ReplicationController replicationController = null;
        try {
            replicationController = connect().getReplicationController(id);
        } catch (KubernetesClientException e) {
            // probably not found
        }
        if (replicationController == null) {
            LOGGER.log(Level.INFO, "Creating Replication Controller: {0}", id);

            com.github.kubernetes.java.client.model.Label labels = new com.github.kubernetes.java.client.model.Label(id);
            replicationController = new ReplicationController();
            replicationController.setId(id);
            replicationController.setLabels(labels);

            // Desired State
            State state = new State();
            state.setReplicas(0);
            state.setReplicaSelector(new Selector(id));

            // Pod
            Pod podTemplate = new Pod();
            podTemplate.setLabels(labels);
            Container container = new Container();
            container.setName(id);
            container.setImage("csanchez/jenkins-swarm-slave:1.21");
            container.setCommand("sh", "-c", "/usr/local/bin/jenkins-slave.sh -master http://$JENKINS_SERVICE_HOST:$JENKINS_SERVICE_PORT -tunnel $JENKINS_SLAVE_SERVICE_HOST:$JENKINS_SLAVE_SERVICE_PORT -username jenkins -password jenkins -executors 1");
            Manifest manifest = new Manifest(Collections.singletonList(container), null);
            podTemplate.setDesiredState(new State(manifest));
            state.setPodTemplate(podTemplate);

            replicationController.setDesiredState(state);
            connect().createReplicationController(replicationController);
            LOGGER.log(Level.INFO, "Created Replication Controller: {0}", id);
        }
        return replicationController;
    }

    @Override
    public synchronized Collection<NodeProvisioner.PlannedNode> provision(final Label label, final int excessWorkload) {
        try {

            LOGGER.log(Level.INFO, "Excess workload after pending Spot instances: " + excessWorkload);

            List<NodeProvisioner.PlannedNode> r = new ArrayList<NodeProvisioner.PlannedNode>();

            final DockerTemplate t = getTemplate(label);

            for (int i = 1; i <= excessWorkload; i++) {
                final int current = i;
                r.add(new NodeProvisioner.PlannedNode(t.getDisplayName(), Computer.threadPoolForRemoting
                        .submit(new Callable<Node>() {
                            public Node call() throws Exception {

                                KubernetesSlave slave = null;
                                ReplicationController replicationController = null;
                                int previousReplicas = -1;
                                try {

                                    // Only call API once
                                    if (current == 1) {
                                        replicationController = getOrCreateReplicationController(label);

                                        State state = replicationController.getDesiredState();
                                        connect().updateReplicationController(replicationController.getId(),
                                                state.getReplicas() + (excessWorkload / t.getNumExecutors()));
                                        previousReplicas = state.getReplicas();
                                    }

                                    String labelName = label == null ? DEFAULT_LABEL : label.getName();
                                    PodList pods = connect().getSelectedPods(
                                            ImmutableList.of(new com.github.kubernetes.java.client.model.Label(
                                                    labelName)));
                                    for (Pod pod : pods.getItems()) {
                                        if (Jenkins.getInstance().getNode(pod.getId()) == null) {
                                            slave = new KubernetesSlave(pod, t, getIdForLabel(label));
                                            Jenkins.getInstance().addNode(slave);
                                            break;
                                        }
                                    }
                                    // Docker instances may have a long init
                                    // script. If we declare
                                    // the provisioning complete by returning
                                    // without the connect
                                    // operation, NodeProvisioner may decide
                                    // that it still wants
                                    // one more instance, because it sees that
                                    // (1) all the slaves
                                    // are offline (because it's still being
                                    // launched) and
                                    // (2) there's no capacity provisioned yet.
                                    //
                                    // deferring the completion of provisioning
                                    // until the launch
                                    // goes successful prevents this problem.
                                    slave.toComputer().connect(false).get();
                                    return slave;
                                } catch (Exception ex) {
                                    if ((current == 1) && (replicationController != null) && (previousReplicas >= 0)) {
                                        // undo the resizing of the controller
                                        connect().updateReplicationController(replicationController.getId(),
                                                previousReplicas);
                                    }
                                    LOGGER.log(Level.SEVERE, "Error in provisioning; slave={0}, template={1}",
                                            new Object[] { slave, t });

                                    ex.printStackTrace();
                                    throw Throwables.propagate(ex);
                                }
                            }
                        }), t.getNumExecutors()));
            }
            return r;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to count the # of live instances on Kubernetes", e);
            return Collections.emptyList();
        }
    }

    @Override
    public boolean canProvision(Label label) {
        return getTemplate(label) != null;
    }

    public DockerTemplate getTemplate(String template) {
        for (DockerTemplate t : templates) {
            if (t.image.equals(template)) {
                return t;
            }
        }
        return null;
    }

    /**
     * Gets {@link DockerTemplate} that has the matching {@link Label}.
     */
    public DockerTemplate getTemplate(Label label) {
        for (DockerTemplate t : templates) {
            if (label == null || label.matches(t.getLabelSet())) {
                return t;
            }
        }
        return null;
    }

    /**
     * Add a new template to the cloud
     */
    public void addTemplate(DockerTemplate t) {
        this.templates.add(t);
        // t.parent = this;
    }

    /**
     * Remove a
     * 
     * @param t
     */
    public void removeTemplate(DockerTemplate t) {
        this.templates.remove(t);
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<Cloud> {
        @Override
        public String getDisplayName() {
            return "Kubernetes";
        }

        public FormValidation doTestConnection(@QueryParameter URL serverUrl, @QueryParameter String username,
                @QueryParameter String password) throws KubernetesClientException, URISyntaxException {

            RestFactory factory = new RestFactory(KubernetesCloud.class.getClassLoader());
            KubernetesAPIClientInterface client = new KubernetesApiClient(serverUrl.toString() + "/api/v1beta1/",
                    username, password, factory);
            client.getAllPods();

            return FormValidation.ok("Connection successful");
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).add("name", name).add("serverUrl", serverUrl).toString();
    }
}