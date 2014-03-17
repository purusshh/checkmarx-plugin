package com.checkmarx.jenkins;

import com.checkmarx.components.zipper.ZipListener;
import com.checkmarx.components.zipper.Zipper;
import com.checkmarx.ws.CxJenkinsWebService.*;
import com.thoughtworks.xstream.annotations.XStreamOmitField;
import hudson.AbortException;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.Builder;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import net.sf.json.JSONObject;
import org.apache.commons.codec.binary.Base64OutputStream;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.log4j.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import java.io.*;
import java.net.MalformedURLException;
import java.util.List;

/**
 * The main entry point for Checkmarx plugin. This class implements the Builder
 * build stage that scans the source code.
 *
 * @author Denis Krivitski
 * @since 3/10/13
 */

public class CxScanBuilder extends Builder {


    //////////////////////////////////////////////////////////////////////////////////////
    // Persistent plugin configuration parameters
    //////////////////////////////////////////////////////////////////////////////////////

    private boolean useOwnServerCredentials;
    private String serverUrl;
    private String username;
    private String password;
    private String projectName;

    private String preset;
    private boolean presetSpecified;
    private String excludeFolders;
    private String filterPattern;

    private boolean incremental;
    private String sourceEncoding;
    private String comment;

    private boolean waitForResultsEnabled;

    private boolean vulnerabilityThresholdEnabled;
    private int highThreshold;
    private boolean generatePdfReport;

    //////////////////////////////////////////////////////////////////////////////////////
    // Private variables
    //////////////////////////////////////////////////////////////////////////////////////
    static {
         BasicConfigurator.configure();  // Set the log4j system to log to console
    }
    private final static Logger staticLogger = Logger.getLogger(CxScanBuilder.class);
    private Logger instanceLogger = staticLogger; // Instance logger redirects to static logger until
                                                  // it is initialized in perform method
    @XStreamOmitField
    private FileAppender fileAppender;

    //////////////////////////////////////////////////////////////////////////////////////
    // Constructors
    //////////////////////////////////////////////////////////////////////////////////////

    @DataBoundConstructor
    public CxScanBuilder(boolean useOwnServerCredentials,
                         String serverUrl,
                         String username,
                         String password,
                         String projectName,
                         String preset,
                         boolean presetSpecified,
                         String excludeFolders,
                         String filterPattern,
                         boolean incremental,
                         String sourceEncoding,
                         String comment,
                         boolean waitForResultsEnabled,
                         boolean vulnerabilityThresholdEnabled,
                         int highThreshold,
                         boolean generatePdfReport)
    {
        this.useOwnServerCredentials = useOwnServerCredentials;
        this.serverUrl = serverUrl;
        this.username = username;
        this.password = password;
        this.projectName = projectName;
        this.preset = preset;
        this.presetSpecified = presetSpecified;
        this.excludeFolders = excludeFolders;
        this.filterPattern = filterPattern;
        this.incremental = incremental;
        this.sourceEncoding = sourceEncoding;
        this.comment = comment;
        this.waitForResultsEnabled = waitForResultsEnabled;
        this.vulnerabilityThresholdEnabled = vulnerabilityThresholdEnabled;
        this.highThreshold = highThreshold;
        this.generatePdfReport =  generatePdfReport;
    }

    //////////////////////////////////////////////////////////////////////////////////////
    // Configuration fields getters
    //////////////////////////////////////////////////////////////////////////////////////


    public boolean isUseOwnServerCredentials() {
        return useOwnServerCredentials;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public String getProjectName() {
        return projectName;
    }

    public String getPreset() {
        return preset;
    }

    public boolean isPresetSpecified() {
        return presetSpecified;
    }

    public String getExcludeFolders() {
        return excludeFolders;
    }

    public String getFilterPattern() {
        return filterPattern;
    }

    public boolean isIncremental() {
        return incremental;
    }

    public String getSourceEncoding() {
        return sourceEncoding;
    }

    public String getComment() {
        return comment;
    }

    public boolean isWaitForResultsEnabled() {
        return waitForResultsEnabled;
    }

    public boolean isVulnerabilityThresholdEnabled() {
        return vulnerabilityThresholdEnabled;
    }

    public int getHighThreshold() {
        return highThreshold;
    }

    public boolean isGeneratePdfReport() {
        return generatePdfReport;
    }

    @Override
    public boolean perform(final AbstractBuild<?, ?> build,
                           final Launcher launcher,
                           final BuildListener listener) throws InterruptedException, IOException {

        try {
            File checkmarxBuildDir = new File(build.getRootDir(),"checkmarx");
            checkmarxBuildDir.mkdir();


            initLogger(checkmarxBuildDir,listener,instanceLoggerSuffix(build));

            listener.started(null);
            instanceLogger.info("Checkmarx Jenkins plugin version: " + CxConfig.version());

            String serverUrlToUse = isUseOwnServerCredentials() ? getServerUrl() : getDescriptor().getServerUrl();
            String usernameToUse  = isUseOwnServerCredentials() ? getUsername()  : getDescriptor().getUsername();
            String passwordToUse  = isUseOwnServerCredentials() ? getPassword()  : getDescriptor().getPassword();
            CxWebService cxWebService = new CxWebService(serverUrlToUse,instanceLoggerSuffix(build));
            cxWebService.login(usernameToUse,passwordToUse);


            instanceLogger.info("Checkmarx server login successful");

            CxWSResponseRunID cxWSResponseRunID = submitScan(build, cxWebService);
            instanceLogger.info("\nScan job submitted successfully\n");


            if (!isWaitForResultsEnabled())
            {
                listener.finished(Result.SUCCESS);
                return true;
            }

            long scanId =  cxWebService.trackScanProgress(cxWSResponseRunID);

            File xmlReportFile = new File(checkmarxBuildDir,"ScanReport.xml");
            cxWebService.retrieveScanReport(scanId,xmlReportFile,CxWSReportType.XML);

            if (this.generatePdfReport)
            {
                File pdfReportFile = new File(checkmarxBuildDir,"ScanReport.pdf");
                cxWebService.retrieveScanReport(scanId, pdfReportFile, CxWSReportType.PDF);
            }


            // Parse scan report and present results in Jenkins

            CxScanResult cxScanResult = new CxScanResult(build,instanceLoggerSuffix(build));
            cxScanResult.readScanXMLReport(xmlReportFile);
            build.addAction(cxScanResult);

            instanceLogger.info("Number of high severity vulnerabilities: " +
                    cxScanResult.getHighCount() + " stability threshold: " + this.getHighThreshold());

            if (this.isVulnerabilityThresholdEnabled())
            {
                if (cxScanResult.getHighCount() >  this.getHighThreshold())
                {
                    build.setResult(Result.UNSTABLE);    // Marks the build result as UNSTABLE
                    listener.finished(Result.UNSTABLE);
                    return true;
                }
            }

            listener.finished(Result.SUCCESS);
            return true;
        } catch (Error e)
        {
            instanceLogger.error(e);
            closeLogger();
            throw e;
        }  catch (AbortException e)
        {
            instanceLogger.error(e);
            closeLogger();
            throw e;
        }catch (IOException e)
        {
            instanceLogger.error(e);
            closeLogger();
            throw e;
        } finally {
            closeLogger();
        }
    }

    private String instanceLoggerSuffix(final AbstractBuild<?, ?> build)
    {
        return build.getProject().getDisplayName() + "-" + build.getDisplayName();
    }

    private void initLogger(final File checkmarxBuildDir, final BuildListener listener, final String loggerSuffix)
    {
        instanceLogger = CxLogUtils.loggerWithSuffix(getClass(),loggerSuffix);
        final WriterAppender writerAppender = new WriterAppender(new PatternLayout("%m%n"),listener.getLogger());
        writerAppender.setThreshold(Level.INFO);
        final Logger parentLogger = CxLogUtils.parentLoggerWithSuffix(loggerSuffix);
        parentLogger.addAppender(writerAppender);
        String logFileName = checkmarxBuildDir.getAbsolutePath() + File.separator + "checkmarx.log";

        try {
            fileAppender = new FileAppender(new PatternLayout("%C: [%d] %-5p: %m%n"),logFileName);
            fileAppender.setThreshold(Level.DEBUG);
            parentLogger.addAppender(fileAppender);
        } catch (IOException e)
        {
            staticLogger.warn("Could not open log file for writing: " + logFileName);
            staticLogger.debug(e);
        }
    }

    private void closeLogger()
    {
        instanceLogger.removeAppender(fileAppender);
        fileAppender.close();
        instanceLogger = staticLogger; // Redirect all logs back to static logger
    }

    private CxWSResponseRunID submitScan(AbstractBuild<?, ?> build, CxWebService cxWebService) throws IOException
    {

        instanceLogger.info("Starting to zip the workspace");

        try {
            // hudson.FilePath will work in distributed Jenkins installation
            FilePath baseDir = build.getWorkspace();
            String combinedFilterPattern = this.getFilterPattern() + "," + processExcludeFolders(this.getExcludeFolders());
            // Implementation of FilePath.FileCallable allows extracting a java.io.File from
            // hudson.FilePath and still working with it in remote mode
            CxZipperCallable zipperCallable = new CxZipperCallable(combinedFilterPattern);

            CxZipResult zipResult = baseDir.act(zipperCallable);
            final FilePath tempFile = zipResult.getTempFile();
            final int numOfZippedFiles = zipResult.getNumOfZippedFiles();

            instanceLogger.info("Zipping complete with " + numOfZippedFiles + " files, total compressed size: " +
                    FileUtils.byteCountToDisplaySize(tempFile.length() / 8 * 6)); // We print here the size of compressed sources before encoding to base 64
            instanceLogger.info("Temporary file with zipped and base64 encoded sources was created at: " + tempFile.getRemote()); //TODO: Log remote machine name

            // Create cliScanArgs object with dummy byte array for zippedFile field
            // Streaming scan web service will nullify zippedFile filed and use tempFile
            // instead
            final CliScanArgs cliScanArgs = createCliScanArgs(new byte[]{});
            final CxWSResponseRunID cxWSResponseRunID = cxWebService.scanStreaming(cliScanArgs, tempFile);
            tempFile.delete();

            return cxWSResponseRunID;
        }
        catch (Zipper.MaxZipSizeReached e)
        {
            throw new AbortException("Checkmarx Scan Failed: Reached maximum upload size limit of " + FileUtils.byteCountToDisplaySize(CxConfig.maxZipSize()));
        }
        catch (Zipper.NoFilesToZip e)
        {
            throw new AbortException("Checkmarx Scan Failed: No files to scan");
        }
        catch (InterruptedException e) {
            throw new AbortException("Checkmarx Scan Failed: Remote scan interrupted");
        }
    }

    @NotNull
    private String processExcludeFolders(String excludeFolders)
    {
        if (excludeFolders==null)
        {
            return "";
        }
        StringBuilder result = new StringBuilder();
        String[] patterns = StringUtils.split(excludeFolders, ",\n");
        for(String p : patterns)
        {
            p = p.trim();
            if (p.length()>0)
            {
                result.append("!**/");
                result.append(p);
                result.append("/**/*, ");
            }
        }
        instanceLogger.debug("Exclude folders converted to: " +result.toString());
        return result.toString();
    }

    private CliScanArgs createCliScanArgs(byte[] compressedSources)
    {

        ProjectSettings projectSettings = new ProjectSettings();
        projectSettings.setDescription(getComment()); // TODO: Move comment to other web service
        long presetLong = 0; // Default value to use in case of exception
        try {
            presetLong = Long.parseLong(getPreset());
        } catch (Exception e)
        {
            instanceLogger.error("Encountered illegal preset value: " + getPreset() + ". Using default preset.");
        }

        projectSettings.setPresetID(presetLong);
        projectSettings.setProjectName(getProjectName());

        long configuration = 0; // Default value to use in case of exception
        try {
            configuration = Long.parseLong(getSourceEncoding());
        } catch (Exception e)
        {
            instanceLogger.error("Encountered illegal source encoding (configuration) value: " + getSourceEncoding() + ". Using default configuration.");
        }
        projectSettings.setScanConfigurationID(configuration);

        LocalCodeContainer localCodeContainer = new LocalCodeContainer();
        localCodeContainer.setFileName("src.zip");
        localCodeContainer.setZippedFile(compressedSources);

        SourceCodeSettings sourceCodeSettings = new SourceCodeSettings();
        sourceCodeSettings.setSourceOrigin(SourceLocationType.LOCAL);
        sourceCodeSettings.setPackagedCode(localCodeContainer);

        CliScanArgs args = new CliScanArgs();
        args.setIsIncremental(isIncremental());
        args.setIsPrivateScan(false);
        args.setPrjSettings(projectSettings);
        args.setSrcCodeSettings(sourceCodeSettings);

        return args;
    }


    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }


    /*public String getIconPath() {
        PluginWrapper wrapper = Hudson.getInstance().getPluginManager().getPlugin([YOUR-PLUGIN-MAIN-CLASS].class);
        return Hudson.getInstance().getRootUrl() + "plugin/"+ wrapper.getShortName()+"/";
    }*/


    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        public final static String DEFAULT_FILTER_PATTERNS = CxConfig.defaultFilterPattern();
        private final static Logger logger = Logger.getLogger(DescriptorImpl.class);

        @XStreamOmitField // The @XStreamOmitField annotation makes the xStream serialization
        // system ignore this field while saving class state to a file
        @Nullable
        private CxWebService cxWebService;

        //////////////////////////////////////////////////////////////////////////////////////
        //  Persistent plugin global configuration parameters
        //////////////////////////////////////////////////////////////////////////////////////

        private String serverUrl;
        private String username;
        private String password;

        public String getServerUrl() {
            return serverUrl;
        }

        public void setServerUrl(String serverUrl) {
            this.serverUrl = serverUrl;
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        public DescriptorImpl() {
            load();
        }

        //////////////////////////////////////////////////////////////////////////////////////
        //  Helper methods for jelly views
        //////////////////////////////////////////////////////////////////////////////////////

        // Provides a description string to be displayed near "Use default server credentials"
        // configuration option
        public String getCredentialsDescription()
        {
            if (getServerUrl()==null || getServerUrl().isEmpty() ||
                getUsername()==null || getUsername().isEmpty())
            {
                return "not set";
            }

            return "Server URL: " + getServerUrl() + " username: " + getUsername();

        }

        //////////////////////////////////////////////////////////////////////////////////////
        // Field value validators
        //////////////////////////////////////////////////////////////////////////////////////

        /* This method is synchronized to avoid multiple threads performing simultaneous login web service calls.
         * Simultaneous login calls result in all session id, except the most recently generated to be invalid.
         * Using an invalid session id, results in ReConnect error message coming from server. Call to this method
         * is performed concurrently with other doCheckXXX and doFillXXXItems methods.
         */

        public synchronized FormValidation doCheckServerUrl(@QueryParameter String value) {
            try {
                this.cxWebService = null;
                this.cxWebService = new CxWebService(value);
                return FormValidation.ok("Server Validated Successfully");
            } catch (Exception e)
            {
                return FormValidation.error(e.getMessage());
            }
        }

        /* This method is synchronized to avoid multiple threads performing simultaneous login web service calls.
         * Simultaneous login calls result in all session id, except the most recently generated to be invalid.
         * Using an invalid session id, results in ReConnect error message coming from server. Call to this method
         * is performed concurrently with other doCheckXXX and doFillXXXItems methods.
         */

        public synchronized FormValidation doCheckPassword(@QueryParameter String serverUrl,
                                              @QueryParameter String password,
                                              @QueryParameter String username) {


            if (this.cxWebService==null) {
                try {
                    this.cxWebService = new CxWebService(serverUrl);
                } catch (Exception e) {
                    return FormValidation.warning("Server URL not set");
                }
            }

            try {
                this.cxWebService.login(username,password);
                return FormValidation.ok("Login Successful");

            } catch (Exception e)
            {
                return FormValidation.error(e.getMessage());
            }
        }

        // Prepares a this.cxWebService object to be connected and logged in
        private void prepareLoggedInWebservice(boolean useOwnServerCredentials,
                                               String serverUrl,
                                               String username,
                                               String password)
                throws AbortException, MalformedURLException
        {
            String serverUrlToUse = !useOwnServerCredentials ? serverUrl : getServerUrl();
            String usernameToUse  = !useOwnServerCredentials ? username  : getUsername();
            String passwordToUse  = !useOwnServerCredentials ? password  : getPassword();
            logger.debug("prepareLoggedInWebservice: server: " + serverUrlToUse + " user: " + usernameToUse + " pass: " + passwordToUse);

            if (this.cxWebService == null) {
                this.cxWebService = new CxWebService(serverUrlToUse);
                logger.debug("prepareLoggedInWebservice: created cxWebService");
            }

            if (!this.cxWebService.isLoggedIn()) {
                this.cxWebService.login(usernameToUse, passwordToUse);
                logger.debug("prepareLoggedInWebservice: logged in");
            }
        }

        /* This method is synchronized to avoid multiple threads performing simultaneous login web service calls.
         * Simultaneous login calls result in all session id, except the most recently generated to be invalid.
         * Using an invalid session id, results in ReConnect error message coming from server. Call to this method
         * is performed concurrently with other doCheckXXX and doFillXXXItems methods.
         */

        public synchronized ComboBoxModel doFillProjectNameItems(@QueryParameter boolean useOwnServerCredentials,
                                                    @QueryParameter String serverUrl,
                                                    @QueryParameter String username,
                                                    @QueryParameter String password)
        {


            ComboBoxModel projectNames = new ComboBoxModel();

            try {
                prepareLoggedInWebservice(useOwnServerCredentials,serverUrl,username,password);

                List<ProjectDisplayData> projectsDisplayData = this.cxWebService.getProjectsDisplayData();
                for(ProjectDisplayData pd : projectsDisplayData)
                {
                    projectNames.add(pd.getProjectName());
                }

                logger.debug("Projects list: " + projectNames.size());
                return projectNames;

            } catch (Exception e) {
                logger.debug("Projects list: empty");
                return projectNames; // Return empty list of project names
            }
        }

        public FormValidation doCheckProjectName(@QueryParameter String projectName)
        {
            if (this.cxWebService==null)
            {
                return FormValidation.warning("Can't validate project name without server credentials");
            }

            CxWSBasicRepsonse cxWSBasicRepsonse = this.cxWebService.validateProjectName(projectName);
            if (cxWSBasicRepsonse.isIsSuccesfull())
            {
                return FormValidation.ok("Project Name Validated Successfully");
            } else {
                if (cxWSBasicRepsonse.getErrorMessage().equalsIgnoreCase("Illegal project name"))
                {
                    return FormValidation.error("Illegal project name");
                } else {
                    logger.warn("Couldn't validate project name with Checkmarx sever:\n" + cxWSBasicRepsonse.getErrorMessage());
                    return FormValidation.warning("Can't reach server to validate project name");
                }
            }
        }


        // Provides a list of presets from checkmarx server for dynamic drop-down list in configuration page

        /* This method is synchronized to avoid multiple threads performing simultaneous login web service calls.
         * Simultaneous login calls result in all session id, except the most recently generated to be invalid.
         * Using an invalid session id, results in ReConnect error message coming from server. Call to this method
         * is performed concurrently with other doCheckXXX and doFillXXXItems methods.
         */

        public synchronized ListBoxModel doFillPresetItems(@QueryParameter boolean useOwnServerCredentials,
                                              @QueryParameter String serverUrl,
                                              @QueryParameter String username,
                                              @QueryParameter String password)
        {
            ListBoxModel listBoxModel = new ListBoxModel();
            try {
                prepareLoggedInWebservice(useOwnServerCredentials,serverUrl,username,password);

                List<Preset> presets = this.cxWebService.getPresets();
                for(Preset p : presets)
                {
                    listBoxModel.add(new ListBoxModel.Option(p.getPresetName(),Long.toString(p.getID())));
                }

                logger.debug("Presets list: " + listBoxModel.size());
                return listBoxModel;

            } catch (Exception e) {
                logger.debug("Presets list: empty");
                String message = "Provide Checkmarx server credentials to see presets list";
                listBoxModel.add(new ListBoxModel.Option(message,message));
                return listBoxModel; // Return empty list of project names
            }
        }

        // Provides a list of source encodings from checkmarx server for dynamic drop-down list in configuration page

        /* This method is synchronized to avoid multiple threads performing simultaneous login web service calls.
         * Simultaneous login calls result in all session id, except the most recently generated to be invalid.
         * Using an invalid session id, results in ReConnect error message coming from server. Call to this method
         * is performed concurrently with other doCheckXXX and doFillXXXItems methods.
         */


        public synchronized ListBoxModel doFillSourceEncodingItems(@QueryParameter boolean useOwnServerCredentials,
                                              @QueryParameter String serverUrl,
                                              @QueryParameter String username,
                                              @QueryParameter String password)
        {
            ListBoxModel listBoxModel = new ListBoxModel();
            try {
                prepareLoggedInWebservice(useOwnServerCredentials,serverUrl,username,password);

                List<ConfigurationSet> sourceEncodings = this.cxWebService.getSourceEncodings();
                for(ConfigurationSet cs : sourceEncodings)
                {
                    listBoxModel.add(new ListBoxModel.Option(cs.getConfigSetName(),Long.toString(cs.getID())));
                }

                logger.debug("Source encodings list: " + listBoxModel.size());
                return listBoxModel;

            } catch (Exception e) {
                logger.debug("Source encodings list: empty");
                String message = "Provide Checkmarx server credentials to see source encodings list";
                listBoxModel.add(new ListBoxModel.Option(message,message));
                return listBoxModel; // Return empty list of project names
            }

        };

        public FormValidation doCheckHighThreshold(@QueryParameter int value)
        {
            if (value >= 0)
            {
                return FormValidation.ok();
            } else {
                return FormValidation.error("Number must be non-negative");
            }
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "Execute Checkmarx Scan";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            // To persist global configuration information,
            // set that to properties and call save().

            // ^Can also use req.bindJSON(this, formData);
            //  (easier when there are many fields; need set* methods for this, like setUseFrench)

            req.bindJSON(this, formData.getJSONObject("checkmarx"));
            save();
            return super.configure(req,formData);
        }


    }
}
