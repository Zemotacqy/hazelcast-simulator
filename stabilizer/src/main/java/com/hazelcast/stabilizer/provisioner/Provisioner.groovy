package com.hazelcast.stabilizer.provisioner

import com.google.common.base.Predicate
import com.hazelcast.logging.ILogger
import com.hazelcast.logging.Logger
import com.hazelcast.stabilizer.Utils
import com.hazelcast.stabilizer.agent.AgentRemoteService
import com.hazelcast.stabilizer.agent.workerjvm.WorkerJvmManager
import org.jclouds.ContextBuilder
import org.jclouds.compute.ComputeService
import org.jclouds.compute.ComputeServiceContext
import org.jclouds.compute.domain.ExecResponse
import org.jclouds.compute.domain.NodeMetadata
import org.jclouds.compute.domain.Template
import org.jclouds.compute.domain.TemplateBuilderSpec
import org.jclouds.logging.log4j.config.Log4JLoggingModule
import org.jclouds.scriptbuilder.statements.login.AdminAccess
import org.jclouds.sshj.config.SshjSshClientModule

import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

import static com.hazelcast.stabilizer.Utils.appendText
import static com.hazelcast.stabilizer.Utils.getVersion
import static java.lang.String.format
import static java.util.Arrays.asList
import static org.jclouds.compute.options.RunScriptOptions.Builder.overrideAuthenticateSudo

//https://jclouds.apache.org/start/compute/ good read
public class Provisioner {
    private final static ILogger log = Logger.getLogger(Provisioner.class.getName());

    def config
    final String STABILIZER_HOME = Utils.getStablizerHome().getAbsolutePath()
    final File CONF_DIR = new File(STABILIZER_HOME, "conf");
    final File agentsFile = new File("agents.txt")
    //big number of threads, but they are used to offload ssh tasks. So there is no load on this machine..
    final ExecutorService executor = Executors.newFixedThreadPool(10);

    final List<String> privateIps = Collections.synchronizedList(new LinkedList<String>());

    Provisioner() {
        log.info("Hazelcast Stabilizer Provisioner");
        log.info(format("Version: %s", getVersion()));
        log.info(format("STABILIZER_HOME: %s", STABILIZER_HOME));

        Properties props = new Properties()

        new File("stabilizer.properties").withInputStream {
            stream -> props.load(stream)
        }
        config = new ConfigSlurper().parse(props)

        if (!agentsFile.exists()) {
            agentsFile.createNewFile()
        }
        agentsFile.text.eachLine { String line -> privateIps << line }
    }

    void installAgent(String ip) {
//        echoImportant "Installing Agent on ${ip}"

//        echo "Copying stabilizer files"
        //first we remove the old lib files to prevent different versions of the same jar to bite us.
        sshQuiet ip, "rm -fr hazelcast-stabilizer-${getVersion()}/lib"
        //then we copy the stabilizer directory
        scpToRemote ip, STABILIZER_HOME, ""

//        echoImportant("Successfully installed Agent on ${ip}");
    }


    void startAgents() {
        echoImportant("Starting ${privateIps.size()} Agents");

        for (String ip : privateIps) {
            echo "Killing Agent $ip"
            ssh ip, "killall -9 java || true"
        }

        for (String ip : privateIps) {
            echo "Starting Agent $ip"
            ssh ip, "nohup hazelcast-stabilizer-${getVersion()}/bin/agent > agent.out 2> agent.err < /dev/null &"
        }

        echoImportant("Successfully started ${privateIps.size()} Agents");
    }

    void startAgent(String ip) {
//        echoImportant("Starting ${privateIps.size()} Agents");

//        for (String ip : privateIps) {
//            echo "Killing Agent $ip"
        ssh ip, "killall -9 java || true"
//        }

        //       for (String ip : privateIps) {
        //           echo "Starting Agent $ip"
        ssh ip, "nohup hazelcast-stabilizer-${getVersion()}/bin/agent > agent.out 2> agent.err < /dev/null &"
        //       }

        //       echoImportant("Successfully started ${privateIps.size()} Agents");
    }

    void killAgents() {
        echoImportant("Killing ${privateIps.size()} Agents");

        for (String ip : privateIps) {
            echo "Killing Agent $ip"
            ssh ip, "killall -9 java || true"
        }

        echoImportant("Successfully killed ${privateIps.size()} Agents");
    }

    void installAgents() {
        privateIps.each { String ip -> installAgent(ip) }
    }

    int calcSize(String sizeType) {
        try {
            return Integer.parseInt(sizeType)
        } catch (NumberFormatException e) {
            println "Unknown size: $sizeType"
            System.exit 1
        }
    }

    void scale(String sizeType) {
        int delta = calcSize(sizeType) - privateIps.size();
        if (delta == 0) {
            echo("Ignoring spawn machines, desired number of machines already exists");
        } else if (delta < 0) {
            terminate(-delta);
        } else {
            scaleUp(delta);
        }
    }

    private int[] inboundPorts() {
        List<Integer> ports = new ArrayList<Integer>();
        ports.add(22);
        //todo:the following 2 ports should not be needed
        ports.add(443);
        ports.add(80);
        ports.add(AgentRemoteService.PORT);
        ports.add(WorkerJvmManager.PORT);
        for (int k = 5701; k < 5901; k++) {
            ports.add(k);
        }

        int[] result = new int[ports.size()];
        for (int k = 0; k < result.length; k++) {
            result[k] = ports[k];
        }
        return result;
    }

    private void scaleUp(int delta) {
        echoImportant("Provisioning ${delta} ${config.CLOUD_PROVIDER} machines");
        echo(config.MACHINE_SPEC);

        if ("outofthebox".equals(config.JDK_FLAVOR)) {
            log.info("Machines will use Java: Out of the Box.");
        } else {
            log.info("Machines will use Java: ${config.JDK_FLAVOR} ${config.JDK_VERSION}")
        }

        ComputeService compute = getComputeService()

        echo("Created compute")

        Template template = compute.templateBuilder()
                .from(TemplateBuilderSpec.parse(config.MACHINE_SPEC))
                .build();

        echo("Created template")

        template.getOptions()
                .inboundPorts(inboundPorts())
                .overrideLoginUser("hazelcast")
                .authorizePublicKey(Utils.fileAsText("/home/ec2-user/.ssh/id_rsa.pub"))
                .runScript(AdminAccess.standard())
                .securityGroups(config.SECURITY_GROUP)

        echo("Creating nodes")

        Set<NodeMetadata> nodes = compute.createNodesInGroup("stabilizer-agent", delta, template)

        echo("Created machines, waiting for startup (can take a few minutes)")

        for (NodeMetadata node : nodes) {
            String ip = node.privateAddresses.iterator().next()
            echo("\t" + ip + " LAUNCHED");
            appendText(ip + "\n", agentsFile)
            privateIps.add(ip)
        }

        Set<Future> futures = new LinkedList<Future>();
        for (NodeMetadata node : nodes) {
            Future f = executor.submit(new InstallNodeTask(node, compute));
            futures.add(f);
        }

        for (Future f : futures) {
            try {
                f.get();
            } catch (ExecutionException e) {
                log.severe("Failed provision", e)
                System.exit(1)
            }
        }

        echoImportant("Successfully provisioned ${delta} ${config.CLOUD_PROVIDER} machines ");
    }

    private class InstallNodeTask implements Runnable {
        private final NodeMetadata node;
        private final ComputeService compute;
        private final String ip;

        InstallNodeTask(NodeMetadata node, ComputeService compute) {
            this.node = node
            this.compute = compute
            this.ip = node.getPrivateAddresses().iterator().next();
        }

        public void run() {
            //initAccount()

            //install java if needed
            if (!"outofthebox".equals(config.JDK_FLAVOR)) {
                installJava(node, compute);
                echo("\t" + ip + " JAVA INSTALLED");
            }

            installAgent(ip)
            echo("\t" + ip + " STABILIZER AGENT INSTALLED");

            startAgent(ip)
            echo("\t" + ip + " STABILIZER AGENT STARTED");
        }

        private void initAccount() {
            ExecResponse response = compute.runScriptOnNode(node.getId(), AdminAccess.standard());
            if (response.exitStatus != 0) {
                log.severe("Failed to initialize ssh: " + response.exitStatus)
                log.severe(response.getError());
                log.severe(response.getOutput());
                System.exit(1)
            }
            echo("\t" + ip + " SSH STARTED");
        }
    }

    private ComputeService getComputeService() {
        return ContextBuilder.newBuilder(config.CLOUD_PROVIDER)
                .credentials(config.CLOUD_IDENTITY, config.CLOUD_CREDENTIAL)
                .modules(asList(new Log4JLoggingModule(), new SshjSshClientModule()))
                .buildView(ComputeServiceContext.class)
                .getComputeService();
    }

    private void installJava(NodeMetadata node, ComputeService compute) {
        String script = loadJavaInstallScript()

        ExecResponse response = compute.runScriptOnNode(
                node.getId(),
                script,
                overrideAuthenticateSudo(true))

        if (response.exitStatus != 0) {
            echo("------------------------------------------------------------------------------");
            echo("Exit code install java: " + response.getExitStatus());
            echo("Failing machine private ip: "+node.getPrivateAddresses().iterator().next())
            echo("Failing machine public ip: "+node.getPublicAddresses().iterator().next())
            echo("------------------------------------------------------------------------------");

            log.info(response.output);
            log.info(response.error);

            System.exit(1)
        }
    }

    private String loadJavaInstallScript() {
        String flavor = config.JDK_FLAVOR;
        String version = config.JDK_VERSION;

        String script = "jdk-"+flavor+"-"+version+".sh";
        return Utils.fileAsText(new File(CONF_DIR, script));
    }

    def downloadArtifacts() {
        echoImportant("Download artifacts of ${privateIps.size()} machines");

        bash("mkdir -p workers");

        for (String ip : privateIps) {
            echo("Downoading from $ip");

            bash("""rsync -av -e "ssh ${config.SSH_OPTIONS}" \
                ${config.USER}@$ip:hazelcast-stabilizer-${getVersion()}/workers/ \
                workers""");
        }

        echoImportant("Finished Downloading Artifacts of ${privateIps.size()} machines");
    }

    void terminate(count = Integer.MAX_VALUE) {
        if (count > privateIps.size()) {
            count = privateIps.size();
        }

        echoImportant(format("Terminating %s %s machines (can take some time)", count, config.CLOUD_PROVIDER));

        final List<String> terminateList = privateIps.subList(0, count);

        ComputeService computeService = getComputeService();
        computeService.destroyNodesMatching(
                new Predicate<NodeMetadata>() {
                    @Override
                    boolean apply(NodeMetadata nodeMetadata) {
                        for (String ip : nodeMetadata.privateAddresses) {
                            if (terminateList.remove(ip)) {
                                echo(format("\t%s Terminating", ip))
                                privateIps.remove(ip)
                                return true;
                            }
                        }
                        return false;
                    }
                }
        )

        log.info("Updating " + agentsFile.getAbsolutePath());
        agentsFile.write("")
        for (String ip : privateIps) {
            agentsFile.text += "$ip\n"
        }

        echoImportant("Finished terminating $count ${config.CLOUD_PROVIDER} machines");
    }

    void bash(String command) {
        def sout = new StringBuffer(), serr = new StringBuffer()

        // create a process for the shell
        ProcessBuilder pb = new ProcessBuilder("bash", "-c", command);
        Process shell = pb.start();
        shell.consumeProcessOutput(sout, serr)

        // wait for the shell to finish and get the return code
        int shellExitStatus = shell.waitFor();
        if (shellExitStatus != 0) {
            echo("Failed to execute [$command]");
            println "out> $sout err> $serr"
            System.exit 1
        }
    }

    void scpToRemote(String ip, String src, String target) {
        String command = "scp -r ${config.SSH_OPTIONS} $src ${config.USER}@$ip:$target";
        bash(command);
    }

    void ssh(String ip, String command) {
        String sshCommand = "ssh ${config.SSH_OPTIONS} -q ${config.USER}@$ip \"$command\"";
        bash(sshCommand);
    }

    void sshQuiet(String ip, String command) {
        String sshCommand = "ssh ${config.SSH_OPTIONS} -q ${config.USER}@$ip \"$command\" || true";
        bash(sshCommand);
    }

    void echo(Object s) {
        log.info(s == null ? "null" : s.toString());
    }

    void echoImportant(Object s) {
        echo("==============================================================");
        echo(s);
        echo("==============================================================");
    }

    public static void main(String[] args) {
        def cli = new CliBuilder(
                usage: 'cluster [options]',
                header: '\nAvailable options (use -h for help):\n',
                stopAtNonOption: false)
        cli.with {
            h(longOpt: 'help', 'print this message')
            r(longOpt: 'restart', 'Restarts all agents')
            d(longOpt: 'download', 'Downloads the logs')
            s(longOpt: 'scale', args: 1, 'Scales the cluster')
            t(longOpt: 'terminate', 'Terminate all members in the cluster')
            k(longOpt: 'kill', 'Kills all agents')
        }

        OptionAccessor opt = cli.parse(args)

        if (!opt) {
            println "Failure parsing options"
            System.exit 1
            return
        }

        if (opt.h) {
            cli.usage()
            System.exit 0
        }

        if (opt.r) {
            def cluster = new Provisioner()
            cluster.installAgents()
            cluster.startAgents()
            System.exit 0
        }

        if (opt.k) {
            def cluster = new Provisioner()
            cluster.killAgents()
            System.exit 0
        }


        if (opt.d) {
            def cluster = new Provisioner()
            cluster.downloadArtifacts()
            System.exit 0
        }

        if (opt.t) {
            def cluster = new Provisioner()
            cluster.terminate()
            System.exit 0
        }

        if (opt.s) {
            def cluster = new Provisioner()

            String sizeType = opt.s
            cluster.scale(sizeType)
            System.exit 0
        }
    }
}
