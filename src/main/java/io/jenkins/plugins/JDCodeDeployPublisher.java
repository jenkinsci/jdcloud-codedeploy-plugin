package io.jenkins.plugins;

import com.amazonaws.ClientConfiguration;
import com.amazonaws.SDKGlobalConfiguration;
import com.amazonaws.auth.AWSCredentials;
import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSStaticCredentialsProvider;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.client.builder.AwsClientBuilder;
import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3Client;
import com.google.common.base.Strings;
import com.google.common.collect.Lists;
import com.jdcloud.sdk.auth.CredentialsProvider;
import com.jdcloud.sdk.auth.StaticCredentialsProvider;
import com.jdcloud.sdk.client.Environment;
import com.jdcloud.sdk.http.HttpRequestConfig;
import com.jdcloud.sdk.http.Protocol;
import com.jdcloud.sdk.service.common.model.Filter;
import com.jdcloud.sdk.service.deploy.client.DeployClient;
import com.jdcloud.sdk.service.deploy.model.*;
import hudson.*;
import hudson.model.AbstractProject;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.util.DirScanner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.io.ArchiverFactory;
import jenkins.tasks.SimpleBuildStep;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import javax.servlet.ServletException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class JDCodeDeployPublisher extends Publisher implements SimpleBuildStep {

    public static final long      DEFAULT_TIMEOUT_SECONDS           = 900;
    public static final long      DEFAULT_POLLING_FREQUENCY_SECONDS = 15;

    private final String ossBucket;
    private final String ossObject;
    private final String applicationName;
    private final String deploymentGroupName;
    private final Long pollingTimeoutSec;
    private final Long pollingFreqSec;
    private final boolean waitForCompletion;
    private final String regionId;
    private final String includes;
    private final String excludes;
    private final String subdirectory;
    private final String proxyHost;
    private final int proxyPort;
    private final String deploySource;
    private final String downloadUrl;

    private final String accessKey;
    private final String secretKey;
//    private final String credentials;
    private final String deploymentMethod;

    private String ossObjectName;
    private String deploymentGroupId;

    private PrintStream logger;
    private Map<String, String> envVars;

    private final static List<String> REGIONS = Lists.newArrayList(
            "cn-north-1", "cn-east-1", "cn-east-2", "cn-south-1");

    private final static Map<Integer, String> DEPLOY_STATUS = new HashMap<Integer, String>() {{
        put(0, "Creating");
        put(1, "Deploying");
        put(2, "Deploy Succeed");
        put(3, "Deploy Failed");
        put(4, "Rolling Back");
        put(5, "RollBack Succeed");
        put(6, "Rollback Failed");
        put(7, "Cancel");
    }};

    // Fields in config.jelly must match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public JDCodeDeployPublisher(
            String ossBucket,
            String ossObject,
            String applicationName,
            String deploymentGroupName,
            String region,
            Boolean waitForCompletion,
            Long pollingTimeoutSec,
            Long pollingFreqSec,
//            String credentials,
            String deploymentMethod,
            String accessKey,
            String secretKey,
            String includes,
            String proxyHost,
            int proxyPort,
            String downloadUrl,
            String deploySource,
            String excludes,
            String subdirectory) {

        this.applicationName = applicationName;
        this.deploymentGroupName = deploymentGroupName;
        this.regionId = region;
        this.includes = includes;
        this.excludes = excludes;
        this.subdirectory = subdirectory;
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
//        this.credentials = credentials;
        this.deploymentMethod = deploymentMethod;
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.downloadUrl = downloadUrl;
        this.deploySource = deploySource;

        if (waitForCompletion != null && waitForCompletion) {
            this.waitForCompletion = true;
            if (pollingTimeoutSec == null) {
                this.pollingTimeoutSec = DEFAULT_TIMEOUT_SECONDS;
            } else {
                this.pollingTimeoutSec = pollingTimeoutSec;
            }
            if (pollingFreqSec == null) {
                this.pollingFreqSec = DEFAULT_POLLING_FREQUENCY_SECONDS;
            } else {
                this.pollingFreqSec = pollingFreqSec;
            }
        } else {
            this.waitForCompletion = false;
            this.pollingTimeoutSec = null;
            this.pollingFreqSec = null;
        }

        this.ossBucket = ossBucket;
        if (ossObject == null || ossObject.equals("/") || ossObject.length() == 0) {
            this.ossObject = "";
        } else {
            this.ossObject = ossObject;
        }
    }

    @Override
    public void perform(@Nonnull Run<?, ?> build, @Nonnull FilePath workspace, @Nonnull Launcher launcher, @Nonnull TaskListener listener) throws InterruptedException, IOException {

        this.logger = listener.getLogger();
        envVars = build.getEnvironment(listener);
        final boolean buildFailed = build.getResult() == Result.FAILURE;
        if (buildFailed) {
            logger.println("Skipping CodeDeploy publisher as build failed");
            return;
        }

        logger.println("Start publish ...");
        boolean success = false;

        try {

            DeployClient deployClient = genJDClient();
            verifyCodeDeployAppAndGroup(deployClient);

            if (Strings.isNullOrEmpty(this.downloadUrl)) {
                final String projectName = build.getFullDisplayName().replace(build.getDisplayName(), "").trim();
                final FilePath sourceDirectory = getSourceDirectory(workspace);
                tarAndUpload(genAmazonS3(), projectName, sourceDirectory);
            }
            String deployId = createDeployment(deployClient);
            success = waitForDeployment(deployClient, deployId);


        } catch (Exception e) {

            this.logger.println("Failed CodeDeploy post-build step; exception follows.");
            this.logger.println(e.getMessage());
            e.printStackTrace(this.logger);
        }

        if (!success) {
            throw new AbortException();
        }
    }

    private DeployClient genJDClient() {
        CredentialsProvider credentialsProvider = new StaticCredentialsProvider(this.accessKey, this.secretKey);
        return DeployClient.builder()
                .credentialsProvider(credentialsProvider)
                .httpRequestConfig(new HttpRequestConfig.Builder().protocol(Protocol.HTTPS).build()) //默认为HTTPS
//                .environment(new Environment.Builder().endpoint(genDeployClientEndpoint()).build())
                .build();
    }

    private AmazonS3 genAmazonS3() {
        final String accessKey = this.accessKey;
        final String secretKey = this.secretKey;
        System.setProperty(SDKGlobalConfiguration.ENABLE_S3_SIGV4_SYSTEM_PROPERTY, "true");
        ClientConfiguration config = new ClientConfiguration();

        AwsClientBuilder.EndpointConfiguration endpointConfig =
                new AwsClientBuilder.EndpointConfiguration(genS3Endpoint(), this.regionId);

        AWSCredentials awsCredentials = new BasicAWSCredentials(accessKey,secretKey);
        AWSCredentialsProvider awsCredentialsProvider = new AWSStaticCredentialsProvider(awsCredentials);

        return AmazonS3Client.builder()
                .withEndpointConfiguration(endpointConfig)
                .withClientConfiguration(config)
                .withCredentials(awsCredentialsProvider)
                .disableChunkedEncoding()
                .withPathStyleAccessEnabled(true)
                .build();
    }

    private String genS3Endpoint() {
        return "s3." + this.regionId + ".jcloudcs.com";
    }

//    private String genDeployClientEndpoint() {
//        return "apigw-internal." + this.regionId + ".jcloudcs.com";
//    }

    private String genFormattedTime() {
        LocalDateTime now = LocalDateTime.now();
        DateTimeFormatter dateTimeFormatter = DateTimeFormatter.ofPattern("yyMMddHHmmSS");
        return now.format(dateTimeFormatter);
    }


    private void verifyCodeDeployAppAndGroup(DeployClient deployClient) throws IllegalArgumentException {
        // Check that the application exists
        logger.println("Check that the application exists");
        DescribeAppsRequest appsRequest = new DescribeAppsRequest();
        appsRequest.setRegionId(this.regionId);
        Filter filter = new Filter();
        filter.setName("appName");
        filter.setOperator("in");
        filter.addValue(this.applicationName);
        appsRequest.addFilter(filter);
        DescribeAppsResponse appsResponse = deployClient.describeApps(appsRequest);
        if (appsResponse.getError() != null) {
            logger.println("query application error: " + appsResponse.getError().getMessage());
        }
        if (appsResponse.getResult().getTotalCount().intValue() == 0) {
            logger.println("Cannot find application named " + this.applicationName);
            throw new IllegalArgumentException("Cannot find application named " + this.applicationName);
        }
        // Check that the deployment group exists
        logger.println("Check that the deployment group exists");
        DescribeGroupsRequest groupsRequest = new DescribeGroupsRequest();
        groupsRequest.setRegionId(regionId);
        Filter groupFilter = new Filter();
        groupFilter.setName("groupName");
        groupFilter.addValue(this.deploymentGroupName);
        groupsRequest.addFilter(groupFilter);
        DescribeGroupsResponse groupsResponse = deployClient.describeGroups(groupsRequest);
        if (groupsResponse.getError() != null) {
            throw new IllegalArgumentException("Query deployment group error.");
        }
        if (groupsResponse.getResult().getTotalCount().intValue() == 0) {
            throw new IllegalArgumentException("Cannot find deployment group named " + this.deploymentGroupName);
        }
        this.deploymentGroupId = groupsResponse.getResult().getGroups().get(0).getGroupId();
    }

    private FilePath getSourceDirectory(FilePath basePath) throws IOException, InterruptedException {
        String subdirectory = StringUtils.trimToEmpty(this.subdirectory);
        if (!subdirectory.isEmpty() && !subdirectory.startsWith("/")) {
            subdirectory = "/" + subdirectory;
        }
        FilePath sourcePath = basePath.withSuffix(subdirectory).absolutize();
        if (!sourcePath.isDirectory() || !isSubDirectory(basePath, sourcePath)) {
            throw new IllegalArgumentException("Provided path (resolved as '" + sourcePath
                    +"') is not a subdirectory of the workspace (resolved as '" + basePath + "')");
        }
        return sourcePath;
    }

    private boolean isSubDirectory(FilePath parent, FilePath child) {
        FilePath parentFolder = child;
        while (parentFolder!=null) {
            if (parent.equals(parentFolder)) {
                return true;
            }
            parentFolder = parentFolder.getParent();
        }
        return false;
    }

    public String tarAndUpload(AmazonS3 s3, String projectName, FilePath sourceDirectory) throws IOException, InterruptedException, IllegalArgumentException {

        File tarFile = File.createTempFile(projectName + "-", ".tar.gz");
        String key = projectName + "-" + genFormattedTime() + ".tar.gz";
        String prefix = this.ossObject;
        String bucket = this.ossBucket;

        if(bucket.indexOf("/") > 0){
            throw new IllegalArgumentException("S3 Bucket field cannot contain any subdirectories.  Bucket name only!");
        }

        try {

            logger.println("Taring files into " + tarFile.getAbsolutePath());

            FileOutputStream outputStream = new FileOutputStream(tarFile);
            try {
                sourceDirectory.archive(
                        ArchiverFactory.TARGZ,
                        outputStream,
                        new DirScanner.Glob(this.includes, this.excludes)
                );
            } finally {
                outputStream.close();
            }

            if (!prefix.isEmpty()) {
                if (prefix.endsWith("/")) {
                    key = Util.replaceMacro(prefix, envVars) + key;
                } else {
                    key = Util.replaceMacro(prefix, envVars) + "/" + key;
                }
            }

            logger.println("Uploading package to s3://" + bucket + "/" + key);
            s3.putObject(bucket, key, tarFile);
            logger.println("Upload finished: " + key);
            this.ossObjectName = key;
            return key;

        } finally {
            final boolean deleted = tarFile.delete();
            if (!deleted) {
                logger.println("Failed to clean up file " + tarFile.getPath());
            }
        }
    }

    private String createDeployment(DeployClient deployClient) {
        logger.println("groupId: " + deploymentGroupId);
        CreateDeployRequest deployRequest = new CreateDeployRequest();
        deployRequest.setRegionId(this.regionId);
        deployRequest.setGroupId(this.deploymentGroupId);
        deployRequest.setCmdSource(2);
        deployRequest.setFileType(3);
        deployRequest.setDesc("Created by jenkins");
        if (!Strings.isNullOrEmpty(this.downloadUrl)) {
            deployRequest.setDeploySource(1);
            deployRequest.setDownloadUrl(downloadUrl);
        } else {
            deployRequest.setDeploySource(3);
            deployRequest.setOssSpace(this.ossBucket);
            deployRequest.setOssDir(this.ossObjectName);
        }
        CreateDeployResponse deployResponse = deployClient.createDeploy(deployRequest);
        if (deployResponse.getError() != null) {
            logger.println("Create deploy error: " + deployResponse.getError().getMessage());
            throw new IllegalArgumentException("Create deploy error: " + deployResponse.getError().getMessage());
        }
        return deployResponse.getResult().getDeployId();
    }

    private boolean waitForDeployment(DeployClient deployClient, String deployId) throws InterruptedException {

        if (!this.waitForCompletion) {
            System.out.println("end");
            return true;
        }

        logger.println("Monitoring deployment with ID " + deployId + "...");

        Deploy deploy = getDeploymentInfo(deployClient, deployId);

        if (deploy == null) {
            logger.println("Cannot get deployment of ID " + deployId + ", try again ...");
        }

        long startTimeMillis;
        if (deploy == null || deploy.getStartTime() == 0) {
            startTimeMillis = new Date().getTime();
        } else {
            startTimeMillis = deploy.getStartTime() * 1000L;
        }

        boolean success = true;
        long pollingTimeoutMillis = this.pollingTimeoutSec * 1000L;
        long pollingFreqMillis = this.pollingFreqSec * 1000L;

        while (deploy == null || deploy.getEndTime() == 0) {

            deploy = getDeploymentInfo(deployClient, deployId);
            Date now = new Date();
            if (now.getTime() - startTimeMillis >= pollingTimeoutMillis) {
                this.logger.println("Exceeded maximum polling time of " + pollingTimeoutMillis + " milliseconds.");
                success = false;
                break;
            }
            Thread.sleep(pollingFreqMillis);
        }

        logger.println("Deployment status: " + DEPLOY_STATUS.get(deploy.getDeployStatus()));

        if (deploy.getDeployStatus() != 2) {
            this.logger.println("Deployment did not succeed. Final status: " + DEPLOY_STATUS.get(deploy.getDeployStatus()));
            success = false;
        }

        return success;
    }

    private Deploy getDeploymentInfo(DeployClient deployClient, String deployId) {
        DescribeDeployRequest deployRequest = new DescribeDeployRequest();
        deployRequest.setRegionId(this.regionId);
        deployRequest.setDeployId(deployId);
        DescribeDeployResponse deployResponse = deployClient.describeDeploy(deployRequest);
        if (deployResponse.getError() != null) {
            logger.println("Describe deploy error: " + deployResponse.getError().getMessage());
            throw new IllegalArgumentException("Describe deploy error: " + deployResponse.getError().getMessage());
        }
        return deployResponse.getResult().getDeploy();
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {

        return (DescriptorImpl) super.getDescriptor();
    }

    @Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    /**
     *
     * Descriptor for {@link }. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * See <tt>src/main/resources/com/amazonaws/codedeploy/AWSCodeDeployPublisher/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension // This indicates to Jenkins that this is an implementation of an extension point.
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String awsAccessKey;
        private String awsSecretKey;
        private String proxyHost;
        private int proxyPort;

        /**
         * In order to load the persisted global configuration, you have to
         * call load() in the constructor.
         */
        public DescriptorImpl() {
            load();
        }

        public FormValidation doCheckName(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0)
                return FormValidation.error("Please add the appropriate values");
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // Indicates that this builder can be used with all kinds of project types
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return Messages.JDCodeDeployPublisher_getDisplayName();
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {

            awsAccessKey = formData.getString("accessKey");
            awsSecretKey = formData.getString("secretKey");
            proxyHost = formData.getString("proxyHost");
            proxyPort = Integer.parseInt(formData.getString("proxyPort"));

            req.bindJSON(this, formData);
            save();
            return super.configure(req, formData);
        }

        public ListBoxModel doFillRegionItems() {
            ListBoxModel items = new ListBoxModel();
            for (String regionId : REGIONS) {
                items.add(getRegionName(regionId), regionId);
            }
            return items;
        }

        private String getRegionName(String regionId) {
            switch (regionId) {
                case "cn-north-1": return Messages.JDCodeDeployPublisher_getRegionNameNorthBeijing();
                case "cn-east-1": return Messages.JDCodeDeployPublisher_getRegionNameEastSuqian();
                case "cn-east-2": return Messages.JDCodeDeployPublisher_getRegionNameEastShanghai();
                case "cn-south-1": return Messages.JDCodeDeployPublisher_getRegionNameSouthGuangzhou();
                default: return "";
            }
        }

        public void setProxyHost(String proxyHost) {
            this.proxyHost = proxyHost;
        }

        public String getProxyHost() {
            return proxyHost;
        }

        public void setProxyPort(int proxyPort) {
            this.proxyPort = proxyPort;
        }

        public int getProxyPort() {
            return proxyPort;
        }

        public String getAwsSecretKey()
        {
            return awsSecretKey;
        }

        public void setAwsSecretKey(String awsSecretKey)
        {
            this.awsSecretKey = awsSecretKey;
        }

        public String getAwsAccessKey()
        {
            return awsAccessKey;
        }

        public void setAwsAccessKey(String awsAccessKey)
        {
            this.awsAccessKey = awsAccessKey;
        }

    }

    public String getOssBucket() {
        return ossBucket;
    }

    public String getOssObject() {
        return ossObject;
    }

    public String getApplicationName() {
        return applicationName;
    }

    public String getDeploymentGroupName() {
        return deploymentGroupName;
    }

    public Long getPollingTimeoutSec() {
        return pollingTimeoutSec;
    }

    public Long getPollingFreqSec() {
        return pollingFreqSec;
    }

    public boolean isWaitForCompletion() {
        return waitForCompletion;
    }

    public String getRegionId() {
        return regionId;
    }

    public String getIncludes() {
        return includes;
    }

    public String getExcludes() {
        return excludes;
    }

    public String getSubdirectory() {
        return subdirectory;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public String getAccessKey() {
        return accessKey;
    }

    public String getSecretKey() {
        return secretKey;
    }

//    public String getCredentials() {
//        return credentials;
//    }

    public String getDeploymentMethod() {
        return deploymentMethod;
    }

    public String getDownloadUrl() {
        return downloadUrl;
    }

    public String getDeploySource() {
        return deploySource;
    }
}
