package hudson.plugins.perforce;

import hudson.plugins.perforce.config.DepotType;
import com.tek42.perforce.Depot;
import com.tek42.perforce.PerforceException;
import com.tek42.perforce.model.Changelist;
import com.tek42.perforce.model.Counter;
import com.tek42.perforce.model.Label;
import com.tek42.perforce.model.Workspace;
import com.tek42.perforce.parse.Counters;
import com.tek42.perforce.parse.Workspaces;
import com.tek42.perforce.model.Changelist.FileEntry;

import hudson.AbortException;
import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Util;
import hudson.Launcher;
import static hudson.Util.fixNull;
import hudson.matrix.MatrixBuild;
import hudson.matrix.MatrixRun;
import hudson.model.*;
import hudson.model.listeners.ItemListener;
import hudson.plugins.perforce.config.CleanTypeConfig;
import hudson.plugins.perforce.config.MaskViewConfig;
import hudson.plugins.perforce.config.WorkspaceCleanupConfig;
import hudson.plugins.perforce.utils.MacroStringHelper;
import static hudson.plugins.perforce.utils.MacroStringHelper.substituteParameters;

import hudson.plugins.perforce.utils.ParameterSubstitutionException;
import hudson.scm.ChangeLogParser;
import hudson.scm.PollingResult;
import hudson.scm.SCM;
import hudson.scm.SCMDescriptor;
import hudson.scm.SCMRevisionState;
import hudson.tasks.Messages;
import hudson.util.FormValidation;
import hudson.util.LogTaskListener;

import hudson.util.StreamTaskListener;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.servlet.ServletException;
import java.io.File;
import java.io.FileFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.Serializable;
import java.io.StringWriter;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

/**
 * Extends {@link SCM} to provide integration with Perforce SCM repositories.
 *
 * @author Mike Wille
 * @author Brian Westrich
 * @author Victor Szoltysek
 * public class PerforceSCM <T extends SCM> {
 */
public class PerforceSCM extends SCM {

    private Long configVersion;

    String p4User;
    String p4Passwd;
    String p4Port;
    String p4Client;
    String clientSpec;
    String projectPath;
    String projectOptions;
    String p4Label;
    String p4Counter;
    String p4UpstreamProject;
    String p4Stream;
    String clientOwner;

    /**
     * Transient so that old XML data will be read but not saved.
     * @deprecated Replaced by {@link #p4Tool}
     */
    transient String p4Exe;
    String p4SysDrive = "C:";
    String p4SysRoot = "C:\\WINDOWS";

    PerforceRepositoryBrowser browser;

    private static final Logger LOGGER = Logger.getLogger(PerforceSCM.class.getName());

    private static final int MAX_CHANGESETS_ON_FIRST_BUILD = 50;

    private static final String WORKSPACE_COMBINATOR = System.getProperty(hudson.slaves.WorkspaceList.class.getName(),"@");

    private static final int MAX_BUILD_ENV_VARS_NESTED_CALLS = 4;

    /**
     * Name of the p4 tool installation
     */
    String p4Tool;

    /**
     * Use ClientSpec text file from depot to prepare the workspace view
     */
    boolean useClientSpec = false;
    /**
     * True if stream depot is used, false otherwise
     */
    boolean useStreamDepot = false;
    /**
     * This is being removed, including it as transient to fix exceptions on startup.
     */
    transient int lastChange;
    /**
     * force sync is a one time trigger from the config area to force a sync with the depot.
     * it is reset to false after the first checkout.
     */
    boolean forceSync = false;
    /**
     * Always force sync the workspace when running a build
     */
    boolean alwaysForceSync = false;
    /**
     * Don't update the 'have' database on the server when syncing.
     */
    boolean dontUpdateServer = false;
    /**
     * Disable Workspace pre-build automatic sync and changelog retrieval
     * This should be renamed if we can implement upgrade logic to handle old configs
     */
    @Deprecated
    boolean disableAutoSync = false;
    /**
     * Disable ChangeLog retrieval
     */
    boolean disableChangeLogOnly = false;
    /**
     * Disable Workspace syncing
     */
    boolean disableSyncOnly = false;
    /**
     * Show integrated changelists
     */
    boolean showIntegChanges = false;
    /**
     * This is to allow the client to use the old naming scheme
     * @deprecated As of 1.0.25, replaced by {@link #clientSuffixType}
     */
    @Deprecated
    boolean useOldClientName = false;
    /**
     * If true, we will create the workspace view within the plugin.  If false, we will not.
     */
    Boolean createWorkspace = true;
    /**
     * If true, we will manage the workspace view within the plugin.  If false, we will leave the
     * view alone.
     */
    boolean updateView = true;
    /**
     * If false we add the slave hostname to the end of the client name when
     * running on a slave.  Defaulting to true so as not to change the behavior
     * for existing users.
     * @deprecated As of 1.0.25, replaced by {@link #clientSuffixType}
     */
    @Deprecated
    boolean dontRenameClient = true;
    /**
     * If true we update the named counter to the last changelist value after the sync operation.
     * If false the counter will be used as the changelist value to sync to.
     * Defaulting to false since the counter name is not set to begin with.
     */
    boolean updateCounterValue = false;
    /**
     * If true, we will never update the client workspace spec on the perforce server.
     */
    boolean dontUpdateClient = false;
    /**
     * If true the environment value P4PASSWD will be set to the value of p4Passwd.
     */
    boolean exposeP4Passwd = false;

    /**
     * If true, the workspace will be deleted before the checkout commences.
     */
    boolean wipeBeforeBuild = false;

    /**
     * If true, the workspace will be cleaned before the checkout commences.
     */
    boolean quickCleanBeforeBuild = false;

    /**
     * If true, files in the workspace will be scanned for differences and restored during a quick clean
     */
    boolean restoreChangedDeletedFiles = false;

    /**
     * If true, the ,repository will be deleted before the checkout commences in addition to the workspace.
     */
    boolean wipeRepoBeforeBuild = false;

    /**
     * If > 0, then will override the changelist we sync to for the first build.
     */
    int firstChange = -1;

    /**
     * Maximum amount of files that are recorded to a changelist, if < 1 show every file.
     */
    int fileLimit = 0;

    /**
     * P4 user name(s) or regex user pattern to exclude from SCM poll to prevent build trigger.
     * Multiple user names are deliminated by space.
     */
    String excludedUsers;

    /**
     * P4 file(s) or regex file pattern to exclude from SCM poll to prevent build trigger.
     */
    String excludedFiles;

    /**
     * Use Case sensitive matching on excludedFiles.
     */
    Boolean excludedFilesCaseSensitivity;

    /**
     * If a ticket was issued we can use it instead of the password in the environment.
     */
    private String p4Ticket = null;

    /**
     * Determines what to append to the end of the client workspace names on slaves
     * Possible values:
     *  None
     *  Hostname
     *  Hash
     */
    String slaveClientNameFormat = null;

    /**
     * We need to store the changelog file name for the build so that we can expose
     * it to the build environment
     */
    transient private String changelogFilename = null;

    /**
     * The value of the LineEnd field in the perforce Client spec.
     */
    private String lineEndValue = "local";

    /**
     * View mask settings for polling and/or syncing against a subset
     * of files in the client workspace.
     */
    private boolean useViewMask = false;
    private String viewMask = null;
    private boolean useViewMaskForPolling = true;
    private boolean useViewMaskForSyncing = false;
    private boolean useViewMaskForChangeLog = false;

    /**
     * Sync only on master option.
     */
    private boolean pollOnlyOnMaster = false;

    /**
     * charset options
     */
    private String p4Charset = null;
    private String p4CommandCharset = null;

    /**
     * SCM constructor, (only?) used when a job configuration is saved.
     * This constructor uses data classes from {@link hudson.plugins.perforce.config}
     * to allow proper handling of hierarchical data in Stapler. In the current
     * state, these classes are not being used outside this constructor.
     */
    // TODO: move data to configuration classes during the refactoring
    @DataBoundConstructor
    public PerforceSCM(
            String p4User,
            String p4Passwd,
            String p4Client,
            String p4Port,
            String projectOptions,
            String p4Tool,
            String p4SysRoot,
            String p4SysDrive,
            String p4Label,
            String p4Counter,
            String p4UpstreamProject,
            String lineEndValue,
            String p4Charset,
            String p4CommandCharset,
            String clientOwner,
            boolean updateCounterValue,
            boolean forceSync,
            boolean dontUpdateServer,
            boolean alwaysForceSync,
            boolean createWorkspace,
            boolean updateView,
            boolean disableChangeLogOnly,
            boolean disableSyncOnly,
            boolean showIntegChanges,
            boolean dontUpdateClient,
            boolean exposeP4Passwd,
            boolean pollOnlyOnMaster,
            String slaveClientNameFormat,
            int firstChange,
            int fileLimit,
            PerforceRepositoryBrowser browser,
            String excludedUsers,
            String excludedFiles,
            boolean excludedFilesCaseSensitivity,
            DepotType depotType,
            WorkspaceCleanupConfig cleanWorkspace,
            MaskViewConfig useViewMask
            ) {

        this.configVersion = 2L;

        this.p4User = Util.fixEmptyAndTrim(p4User);
        this.setP4Passwd(p4Passwd);
        this.setExposeP4Passwd(exposeP4Passwd);
        this.p4Client = p4Client;
        this.p4Port = p4Port;
        this.p4Tool = p4Tool;
        this.pollOnlyOnMaster = pollOnlyOnMaster;
        this.projectOptions = (projectOptions != null)
                ? projectOptions
                : "noallwrite clobber nocompress unlocked nomodtime rmdir";

        if (this.p4Label != null && p4Label != null) {
            Logger.getLogger(PerforceSCM.class.getName()).warning(
                    "Label found in views and in label field.  Using: "
                    + p4Label);
        }
        this.p4Label = Util.fixEmptyAndTrim(p4Label);

        this.p4Counter = Util.fixEmptyAndTrim(p4Counter);
        this.updateCounterValue = updateCounterValue;

        this.p4UpstreamProject = Util.fixEmptyAndTrim(p4UpstreamProject);

        //TODO: move optional entries to external classes
        // Get data from the depot type
        if (depotType != null) {
            this.p4Stream = depotType.getP4Stream();
            this.clientSpec = depotType.getClientSpec();
            this.projectPath = Util.fixEmptyAndTrim(depotType.getProjectPath());
            this.useStreamDepot = depotType.useP4Stream();
            this.useClientSpec = depotType.useClientSpec();
            this.useViewMask = depotType.useProjectPath();
        }

        // Get data from workspace cleanup settings
        if (cleanWorkspace != null) {
            setWipeRepoBeforeBuild(cleanWorkspace.isWipeRepoBeforeBuild());

            CleanTypeConfig cleanType = cleanWorkspace.getCleanType();
            if (cleanType != null) {
                setWipeBeforeBuild(cleanType.isWipe());
                setQuickCleanBeforeBuild(cleanType.isQuick());
                setRestoreChangedDeletedFiles(cleanType.isRestoreChangedDeletedFiles());
            }
        } else {
            setWipeRepoBeforeBuild(false);
        }

        // Setup view mask
        if (useViewMask != null) {
            setUseViewMask(true);
            setViewMask(hudson.Util.fixEmptyAndTrim(useViewMask.getViewMask()));
            setUseViewMaskForPolling(useViewMask.isUseViewMaskForPolling());
            setUseViewMaskForSyncing(useViewMask.isUseViewMaskForSyncing());
            setUseViewMaskForChangeLog(useViewMask.isUseViewMaskForChangeLog());

        } else {
            setUseViewMask(false);
        }

        this.clientOwner = Util.fixEmptyAndTrim(clientOwner);

        if (p4SysRoot != null) {
            this.p4SysRoot = p4SysRoot.trim();
        }
        if (p4SysDrive != null) {
            this.p4SysDrive = p4SysDrive.trim();
        }

        this.lineEndValue = lineEndValue;
        this.forceSync = forceSync;
        this.dontUpdateServer = dontUpdateServer;
        this.alwaysForceSync = alwaysForceSync;
        this.disableChangeLogOnly = disableChangeLogOnly;
        this.disableSyncOnly = disableSyncOnly;
        this.showIntegChanges = showIntegChanges;
        this.browser = browser;
        this.createWorkspace = Boolean.valueOf(createWorkspace);
        this.updateView = updateView;
        this.dontUpdateClient = dontUpdateClient;
        this.slaveClientNameFormat = slaveClientNameFormat;
        this.firstChange = firstChange;
        this.fileLimit = fileLimit;
        this.dontRenameClient = false;
        this.useOldClientName = false;
        this.p4Charset = Util.fixEmptyAndTrim(p4Charset);
        this.p4CommandCharset = Util.fixEmptyAndTrim(p4CommandCharset);
        this.excludedUsers = Util.fixEmptyAndTrim(excludedUsers);
        this.excludedFiles = Util.fixEmptyAndTrim(excludedFiles);
        this.excludedFilesCaseSensitivity = excludedFilesCaseSensitivity;
    }

    /**
     * Gets instance of the PerforceSCM
     * @return Instance of the PerforceSCM
     * @since 1.4.0
     */
    public static PerforceSCMDescriptor getInstance() {
        String scmName = PerforceSCM.class.getSimpleName();
        return (PerforceSCMDescriptor)Hudson.getInstance().getScm(scmName);
    }

    /**
     * Gets the P4 user from local or global configs (as a default).
     * @return Effective password. May be null or empty
     * @since 1.3.31
     */
    public @CheckForNull String getEffectiveP4User() {
        return p4User != null ? p4User : getInstance().getP4DefaultUser();
    }

    /**
     * Gets the password from local or global configs (as a default).
     * @return Effective password. May be null or empty
     * @since 1.3.31
     */
    public @CheckForNull String getEffectiveP4Password() {
        return p4User != null ? p4Passwd : getInstance().getP4DefaultPassword();
    }

    /**
     * This only exists because we need to do initialization after we have been brought
     * back to life.  I'm not quite clear on stapler and how all that works.
     * At any rate, it doesn't look like we have an init() method for setting up our Depot
     * after all of the setters have been called.  Someone correct me if I'm wrong...
     *
     * UPDATE: With the addition of PerforceMailResolver, we now have need to share the depot object.  I'm making
     * this protected to enable that.
     *
     * Always create a new Depot to reflect any changes to the machines that
     * P4 actions will be performed on.
     *
     * @param node the value of node
     * @exception ParameterSubstitutionException
     */
    @Nonnull
    protected Depot getDepot(@Nonnull Launcher launcher, @Nonnull FilePath workspace,
            @CheckForNull AbstractProject project,
            @CheckForNull AbstractBuild build, @CheckForNull Node node)
            throws ParameterSubstitutionException, InterruptedException {
        HudsonP4ExecutorFactory p4Factory = new HudsonP4ExecutorFactory(launcher,workspace);

        Depot depot = new Depot(p4Factory);

        depot.setClient(MacroStringHelper.substituteParameters(p4Client, this, build, project, node, null));
        depot.setUser(MacroStringHelper.substituteParameters(getEffectiveP4User(), this, build, project, node, null));
        depot.setPort(MacroStringHelper.substituteParameters(p4Port, this, build, project, node, null));

        if (build != null) { // We can retrieve all parameters from the build's environment
            depot.setPassword(getDecryptedP4Passwd(build));
        } else { // project can be null
            depot.setPassword(project != null ? getDecryptedP4Passwd(project, node) : getDecryptedP4Passwd());
        }

        if (p4Ticket != null && !p4Ticket.equals(""))
            depot.setP4Ticket(p4Ticket);

        if (node == null)
            depot.setExecutable(getP4Executable(p4Tool));
        else
            depot.setExecutable(getP4Executable(p4Tool,node,TaskListener.NULL));

        // Get systemDrive,systemRoot computer environment variables from
        // the current machine.
        // The current machine is the machine about to do something (run a
        // build, poll the server) according to whatever called getDepot
        String systemDrive = Util.fixEmptyAndTrim(p4SysDrive);
        String systemRoot = Util.fixEmptyAndTrim(p4SysRoot);
        try {
            Computer currentComputer = Computer.currentComputer();
            // A master with no executors seems to throw an NPE here, so
            // we need to check for null.
            if (currentComputer != null) {
                EnvVars envVars = currentComputer.getEnvironment();
                if (systemDrive == null && envVars.containsKey("SystemDrive")) {
                    systemDrive = envVars.get("SystemDrive");
                }
                if (systemRoot == null && envVars.containsKey("SystemRoot")) {
                    systemRoot = envVars.get("SystemRoot");
                }
            }
        } catch (Exception ex) {
            LOGGER.log(Level.WARNING, ex.getMessage(), ex);
        }
        depot.setSystemDrive(systemDrive);
        depot.setSystemRoot(systemRoot);

        depot.setCharset(p4Charset);
        depot.setCommandCharset(p4CommandCharset);

        return depot;
    }

    /**
     * Override of SCM.buildEnvVars() in order to setup the last change we have
     * sync'd to as a Hudson
     * environment variable: P4_CHANGELIST
     *
     * @param build
     * @param env
     */
    @Override	
    public void buildEnvVars(@Nonnull AbstractBuild<?, ?> build, @Nonnull Map<String, String> env) {
        super.buildEnvVars(build, env);

        // Check nested calls
        int nestedCallsCount = 0;
        for (StackTraceElement ste : (new Throwable()).getStackTrace()) { // Inspect the stacktrace to avoid the infinite recursion
            if (ste.getMethodName().equals("buildEnvVars") && ste.getClassName().equals(PerforceSCM.class.getName())) {
                if ( ++nestedCallsCount > MAX_BUILD_ENV_VARS_NESTED_CALLS) {
                    return;
                }
            }
        }

        try {
            env.put("P4PORT", MacroStringHelper.substituteParameters(p4Port, this, build, env));
            env.put("P4USER", MacroStringHelper.substituteParameters(getEffectiveP4User(), this, build, env));

            // if we want to allow p4 commands in script steps this helps
            if (isExposeP4Passwd()) {
                PerforcePasswordEncryptor encryptor = new PerforcePasswordEncryptor();
                env.put("P4PASSWD", encryptor.decryptString(getEffectiveP4Password()));
            }
            // this may help when tickets are used since we are
            // not storing the ticket on the client during login
            if (p4Ticket != null) {
                env.put("P4TICKET", p4Ticket);
            }
            // If we are running concurrent builds, the Jenkins workspace path is different
            // for each concurrent build. Append Perforce workspace name with Jenkins
            // workspace identifier suffix. But, only if we are syncing or allowing Jenkins to
            // manage workspaces.
            String effectiveP4Client = getEffectiveClientName(build, env);
            if (!this.disableSyncOnly || this.createWorkspace) {
                effectiveP4Client = getConcurrentClientName(build.getWorkspace(), effectiveP4Client);
            }
            env.put("P4CLIENT", effectiveP4Client);
        } catch (ParameterSubstitutionException ex) {
            if (nestedCallsCount < 1) { // substitution failure is acceptable for a nested call
                LOGGER.log(MacroStringHelper.SUBSTITUTION_ERROR_LEVEL, "Cannot build environent variables due to unresolved macros", ex);
            }
            //TODO: exit?
        } catch (InterruptedException ex) {
            LOGGER.log(MacroStringHelper.SUBSTITUTION_ERROR_LEVEL, "Cannot build environment vars. The method has been interrupted");
        }

        PerforceTagAction pta = build.getAction(PerforceTagAction.class);
        if (pta != null) {
            if (pta.getChangeNumber() > 0) {
                int lastChange = pta.getChangeNumber();
                env.put("P4_CHANGELIST", Integer.toString(lastChange));
            } else if (pta.getTag() != null) {
                String label = pta.getTag();
                env.put("P4_LABEL", label);
            }
        }

        if (changelogFilename != null) {
            env.put("HUDSON_CHANGELOG_FILE", changelogFilename);
        }
    }

    /**
     * Get the path to p4 executable from a Perforce tool installation.
     *
     * @param tool the p4 tool installation name
     * @return path to p4 tool path or an empty string if none is found
     */
    @Nonnull
    public String getP4Executable(@CheckForNull String tool) {
        PerforceToolInstallation toolInstallation = getP4Tool(tool);
        if (toolInstallation == null)
            return "p4";
        return toolInstallation.getP4Exe();
    }

    @Nonnull
    public String getP4Executable(@CheckForNull String tool,
            @Nonnull Node node, @Nonnull TaskListener listener) {
        PerforceToolInstallation toolInstallation = getP4Tool(tool);
        if (toolInstallation == null)
            return "p4";
        String p4Exe="p4";
        try {
            p4Exe = toolInstallation.forNode(node, listener).getP4Exe();
        } catch (IOException e) {
            listener.getLogger().println(e);
        } catch (InterruptedException e) {
            listener.getLogger().println(e);
        }
        return p4Exe;
    }

    /**
     * Get the path to p4 executable from a Perforce tool installation.
     *
     * @param tool the p4 tool installation name
     * @return path to p4 tool installation or null
     */
    @CheckForNull
    public PerforceToolInstallation getP4Tool(@CheckForNull String tool) {
        PerforceToolInstallation[] installations = ((hudson.plugins.perforce.PerforceToolInstallation.DescriptorImpl)Hudson.getInstance().
                getDescriptorByType(PerforceToolInstallation.DescriptorImpl.class)).getInstallations();
        for (PerforceToolInstallation i : installations) {
            if (i.getName().equals(tool)) {
                return i;
            }
        }
        return null;
    }

    /**
     * Use the old job configuration data. This method is called after the object is read by XStream.
     * We want to create tool installations for each individual "p4Exe" path as field "p4Exe" has been removed.
     *
     * @return the new object which is an instance of PerforceSCM
     */
    @SuppressWarnings( "deprecation" )
    public Object readResolve() {
        if (createWorkspace == null) {
            createWorkspace = Boolean.TRUE;
        }

        if (p4Exe != null) {
            PerforceToolInstallation.migrateOldData(p4Exe);
            p4Tool = p4Exe;
        }

        if (excludedFilesCaseSensitivity == null) {
            excludedFilesCaseSensitivity = Boolean.TRUE;
        }

        if (clientOwner == null) {
            clientOwner = "";
        }

        if (configVersion == null) {
            configVersion = 0L;
        }

        if (configVersion == 0L) {
            this.disableSyncOnly = this.disableAutoSync;
            this.disableChangeLogOnly = this.disableAutoSync;

            configVersion = 1L;
        }

        if (configVersion == 1L) {
            this.useViewMaskForChangeLog = this.useViewMaskForSyncing;

            configVersion = 2L;
        }

        return this;
    }

    @Nonnull
    private String getEffectiveProjectPath(
            @CheckForNull AbstractBuild build,
            @Nonnull AbstractProject project,
            @CheckForNull Node node,
            @Nonnull PrintStream log,
            @Nonnull Depot depot)
            throws PerforceException, ParameterSubstitutionException, InterruptedException {
        String effectiveProjectPath = useClientSpec
                ? getEffectiveProjectPathFromFile(build, project, node, log, depot)
                : MacroStringHelper.substituteParameters(this.projectPath, this, build, project, node, null);
        return effectiveProjectPath;
    }

    @Nonnull
    private String getEffectiveProjectPathFromFile(
            @CheckForNull AbstractBuild build,
            @CheckForNull AbstractProject project,
            @CheckForNull Node node,
            @Nonnull PrintStream log, @Nonnull Depot depot)
            throws PerforceException, ParameterSubstitutionException, InterruptedException {
        String effectiveClientSpec =
                MacroStringHelper.substituteParameters(this.clientSpec, this, build, project, node, null);
        log.println("Read ClientSpec from: " + effectiveClientSpec);
        com.tek42.perforce.parse.File f = depot.getFile(effectiveClientSpec);
        String effectiveProjectPath =
                MacroStringHelper.substituteParameters(f.read(), this, build, project, node, null);

        return effectiveProjectPath;
    }

    private int getLastBuildChangeset(@Nonnull AbstractProject project) {
        Run lastBuild = project.getLastBuild();
        return getLastChange(lastBuild);
    }

    /**
     * Perform some manipulation on the workspace URI to get a valid local path
     * <p>
     * Is there an issue doing this?  What about remote workspaces?  does that happen?
     *
     * @param path
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    @Nonnull
    private String getLocalPathName(@Nonnull FilePath path, boolean isUnix)
            throws IOException, InterruptedException {
        return processPathName(path.getRemote(), isUnix);
    }

    @Nonnull
    public static String processPathName(@Nonnull String path, boolean isUnix) {
        String pathName = path;
        pathName = pathName.replaceAll("/\\./", "/");
        pathName = pathName.replaceAll("\\\\\\.\\\\", "\\\\");
        pathName = pathName.replaceAll("/+", "/");
        boolean isRemoteUNC = pathName.startsWith("\\\\");
        pathName = pathName.replaceAll("\\\\+", "\\\\");
        if (isRemoteUNC) {
            pathName = "\\" + pathName;
        }
        if (isUnix) {
            pathName = pathName.replaceAll("\\\\", "/");
        } else {
            pathName = pathName.replaceAll("/", "\\\\");
        }
        return pathName;
    }

    private static void retrieveUserInformation(@Nonnull Depot depot,
            @Nonnull List<Changelist> changes) throws PerforceException {
        // uniqify in order to reduce number of calls to P4.
        HashSet<String> users = new HashSet<String>();
        for (Changelist change : changes) {
            users.add(change.getUser());
        }
        for (String user : users) {
            com.tek42.perforce.model.User pu;
            try {
                 pu = depot.getUsers().getUser(user);
            } catch (Exception e) {
                throw new PerforceException("Problem getting user information for " + user,e);
            }
            //If there is no such user in perforce, then ignore and keep going.
            if (pu == null) {
                LOGGER.warning("Perforce User ("+user+") does not exist.");
                continue;
            }

            User author = User.get(user);
            // Need to store the actual perforce user id for later retrieval
            // because Jenkins does not support all the same characters that
            // perforce does in the userID.
            PerforceUserProperty puprop = author.getProperty(PerforceUserProperty.class);
            if (puprop == null || puprop.getPerforceId() == null || puprop.getPerforceId().equals("")) {
                puprop = new PerforceUserProperty();
                try {
                    author.addProperty(puprop);
                } catch (IOException ex) {
                    LOGGER.log(Level.SEVERE, null, ex);
                }
            }
            puprop.setPerforceEmail(pu.getEmail());
            puprop.setPerforceId(user);
        }
    }

    public static boolean isFileInView(String filename, String projectPath, boolean caseSensitive) {
        List<String> view = parseProjectPath(projectPath, "workspace");
        boolean inView = false;
        for (int i = 0; i < view.size(); i += 2) {
            String viewline = view.get(i);
            if (viewline.startsWith("-")) {
                if (doesFilenameMatchP4Pattern(filename, viewline.substring(1), caseSensitive)) {
                    inView = false;
                }
            } else if (viewline.startsWith("+")) {
                if (doesFilenameMatchP4Pattern(filename, viewline.substring(1), caseSensitive)) {
                    inView = true;
                }
            } else {
                if (doesFilenameMatchP4Pattern(filename, viewline, caseSensitive)) {
                    inView = true;
                }
            }
        }
        return inView;
    }

    private static class WipeWorkspaceExcludeFilter implements FileFilter, Serializable {

        private final List<String> excluded = new ArrayList<String>();

        public WipeWorkspaceExcludeFilter(String... args) {
            excluded.addAll(Arrays.asList(args));
        }

        public void exclude(String arg) {
            excluded.add(arg);
        }

        public boolean accept(File arg0) {
            for (String exclude : excluded) {
                if (arg0.getName().equals(exclude)) {
                    return false;
                }
            }
            return true;
        }
    }

    private static boolean overrideWithBooleanParameter(String paramName,
            @Nonnull AbstractBuild build, boolean dflt) {
        if (build.getBuildVariables() != null) {
            Object param = build.getBuildVariables().get(paramName);
            if (param != null) {
                String paramString = param.toString();
                return paramString.toUpperCase().equals("TRUE") || paramString.equals("1");
            }
        }
        return dflt;
    }

    /*
     * @see hudson.scm.SCM#checkout(hudson.model.AbstractBuild, hudson.Launcher, hudson.FilePath, hudson.model.BuildListener, java.io.File)
     */
    @Override
    public boolean checkout(AbstractBuild build, Launcher launcher,
            FilePath workspace, BuildListener listener, File changelogFile) throws IOException, InterruptedException {

        PrintStream log = listener.getLogger();
        changelogFilename = changelogFile.getAbsolutePath();
        // HACK: Force build env vars to initialize
        MacroStringHelper.substituteParameters("", this, build, null);

        // Use local variables so that substitutions are not saved
        String p4Label = MacroStringHelper.substituteParameters(this.p4Label, this, build, null);
        String viewMask = MacroStringHelper.substituteParameters(this.viewMask, this, build, null);
        Depot depot = getDepot(launcher,workspace, build.getProject(), build, build.getBuiltOn());
        String p4Stream = MacroStringHelper.substituteParameters(this.p4Stream, this, build, null);

        // Pull from optional named parameters
        boolean wipeBeforeBuild = overrideWithBooleanParameter(
                "P4CLEANWORKSPACE", build, this.wipeBeforeBuild);
        boolean quickCleanBeforeBuild = overrideWithBooleanParameter(
                "P4QUICKCLEANWORKSPACE", build, this.quickCleanBeforeBuild);
        boolean wipeRepoBeforeBuild = overrideWithBooleanParameter(
                "P4CLEANREPOINWORKSPACE", build, this.wipeRepoBeforeBuild);
        boolean forceSync = overrideWithBooleanParameter(
                "P4FORCESYNC", build, this.forceSync);
        boolean disableChangeLogOnly = overrideWithBooleanParameter(
                "P4DISABLECHANGELOG", build, this.disableChangeLogOnly);
        boolean disableSyncOnly = overrideWithBooleanParameter(
                "P4DISABLESYNCONLY", build, this.disableSyncOnly);
        disableSyncOnly = overrideWithBooleanParameter(
                "P4DISABLESYNC", build, this.disableSyncOnly);
        boolean oneChangelistOnly = overrideWithBooleanParameter(
                "P4ONECHANGELIST", build, false);

        // If we're doing a matrix build, we should always force sync.
        if ((Object)build instanceof MatrixBuild || (Object)build instanceof MatrixRun) {
            if (!alwaysForceSync && !wipeBeforeBuild)
                log.println("This is a matrix build; It is HIGHLY recommended that you enable the " +
                            "'Always Force Sync' or 'Clean Workspace' options. " +
                            "Failing to do so will likely result in child builds not being synced properly.");
        }

        try {
            // keep projectPath local so any modifications for slaves don't get saved
            String effectiveProjectPath= getEffectiveProjectPath(build,
                    build.getProject(), build.getBuiltOn(), log, depot);

            Workspace p4workspace = getPerforceWorkspace(build.getProject(), effectiveProjectPath, depot, build.getBuiltOn(), build, launcher, workspace, listener, false);

            boolean dirtyWorkspace = p4workspace.isDirty();
            saveWorkspaceIfDirty(depot, p4workspace, log);

            //Wipe/clean workspace
            String p4config;
            WipeWorkspaceExcludeFilter wipeFilter;
            try {
                p4config = MacroStringHelper.substituteParameters("${P4CONFIG}", this, build, null);
                wipeFilter = new WipeWorkspaceExcludeFilter(".p4config",p4config);
            } catch (ParameterSubstitutionException ex) {
                wipeFilter = new WipeWorkspaceExcludeFilter();
            }

            if (wipeBeforeBuild || quickCleanBeforeBuild) {
                long cleanStartTime = System.currentTimeMillis();
                if (wipeRepoBeforeBuild) {
                    log.println("Clear workspace includes .repository ...");
                } else {
                    log.println("Note: .repository directory in workspace (if exists) is skipped during clean.");
                    wipeFilter.exclude(".repository");
                }
                if (wipeBeforeBuild) {
                    log.println("Wiping workspace...");
                    List<FilePath> workspaceDirs = workspace.list(wipeFilter);
                    for (FilePath dir : workspaceDirs) {
                        dir.deleteRecursive();
                    }
                    log.println("Wiped workspace.");
                    forceSync = true;
                }
                if (quickCleanBeforeBuild) {
                    QuickCleaner quickCleaner = new QuickCleaner(depot.getExecutable(), depot.getP4Ticket(), launcher, depot, workspace, wipeFilter);
                    log.println("Quickly cleaning workspace...");
                    quickCleaner.doClean();
                    log.println("Workspace is clean.");
                    if (restoreChangedDeletedFiles) {
                        log.println("Restoring changed and deleted files...");
                        quickCleaner.doRestore();
                        log.println("Files restored.");
                    }
                }
                long cleanEndTime = System.currentTimeMillis();
                long cleanDuration = cleanEndTime - cleanStartTime;

                log.println("Clean complete, took " + cleanDuration + " ms");
            }

            // In case of a stream depot, we want Perforce to handle the client views. So let's re-initialize
            // the p4workspace object if it was changed since the last build. Also, populate projectPath with
            // the current view from Perforce. We need it for labeling.
            if (useStreamDepot) {
                if (dirtyWorkspace) {
                    // Support for concurrent builds
                    String p4Client = getConcurrentClientName(workspace, getEffectiveClientName(build, null));
                    p4workspace = depot.getWorkspaces().getWorkspace(p4Client, p4Stream);
                }
                effectiveProjectPath = p4workspace.getTrimmedViewsAsString();
            }
            // If we're not managing the view, populate the projectPath with the current view from perforce
            // This is both for convenience, and so the labeling mechanism can operate correctly
            if (!updateView) {
                effectiveProjectPath = p4workspace.getTrimmedViewsAsString();
            }

            String p4WorkspacePath = "//" + p4workspace.getName() + "/...";
            int lastChange = getLastChange((Run)build.getPreviousBuild());
            log.println("Last build changeset: " + lastChange);

            // Determine changeset number
            int newestChange = lastChange;

            List<Changelist> changes;
            if (p4Label != null && !p4Label.trim().isEmpty()) {
                newestChange = depot.getChanges().getHighestLabelChangeNumber(p4workspace, p4Label.trim(), p4WorkspacePath);
            } else {
                if (p4UpstreamProject != null && p4UpstreamProject.length() > 0) {
                    log.println("Using last successful or unstable build of upstream project " + p4UpstreamProject);
                    Job job = Hudson.getInstance().getItemByFullName(p4UpstreamProject, Job.class);
                    if (job == null) {
                        throw new AbortException(
                                "Configured upstream job does not exist anymore: " + p4UpstreamProject + ". Please update your job configuration.");
                    }
                    Run upStreamRun = job.getLastSuccessfulBuild();
                    int lastUpStreamChange = getLastChangeNoFirstChange(upStreamRun);
                    if (lastUpStreamChange > 0) {
                        log.println("Using P4 revision " + lastUpStreamChange + " from upstream project " + p4UpstreamProject);
                        newestChange = lastUpStreamChange;
                    } else {
                        log.println("No P4 revision found in upstream project " + p4UpstreamProject);
                        throw new AbortException(
                                "Configured upstream job has not been run yet: " + p4UpstreamProject + ". Please run it once befor launching a new build.");
                    }
                } else
                if (p4Counter != null && !updateCounterValue) {
                    //use a counter
                    String counterName;
                    counterName = MacroStringHelper.substituteParameters(this.p4Counter, this, build, null);
                    Counter counter = depot.getCounters().getCounter(counterName);
                    newestChange = counter.getValue();
                } else {
                    //use the latest submitted change from workspace, or depot
                    try {
                        List<Integer> workspaceChanges = depot.getChanges().getChangeNumbers(p4WorkspacePath, 0, 1);
                        if (workspaceChanges != null && !workspaceChanges.isEmpty()) {
                            newestChange = workspaceChanges.get(0);
                        } else {
                            List<Integer> depotChanges = depot.getChanges().getChangeNumbers("//...", 0, 1);
                            if (depotChanges != null && !depotChanges.isEmpty()) {
                                newestChange = depotChanges.get(0);
                            }
                        }
                    } catch (PerforceException e) {
                        //fall back onto 'change' counter value
                        log.println("Failed to get last submitted changeset in the view, falling back to change counter. Error was: " + e.getMessage());
                        Counter counter = depot.getCounters().getCounter("change");
                        newestChange = counter.getValue();
                    }
                }
            }

            // Set newestChange down to the next available changeset if we're building one change at a time
            if (oneChangelistOnly && build.getPreviousBuild() != null
                    && lastChange > 0 && newestChange > lastChange) {
                List<Integer> workspaceChanges = depot.getChanges().getChangeNumbersInRange(
                        p4workspace, lastChange+1, newestChange, viewMask, showIntegChanges);
                for (int i = workspaceChanges.size()-1; i >= 0; --i) {
                    int changeNumber = workspaceChanges.get(i);
                    Changelist changelist = depot.getChanges().getChangelist(changeNumber, fileLimit);
                    if (!isChangelistExcluded(changelist, build.getProject(), build.getBuiltOn(), p4workspace.getViewsAsString(), log)) {
                        newestChange = changeNumber;
                        break;
                    }
                    log.println("Changelist "+changeNumber+" is composed of file(s) and/or user(s) that are excluded.");
                }
                log.println("Remaining changes: " + workspaceChanges);
                log.println("Building next changeset in sequence: " + newestChange);
            }

            if (build instanceof MatrixRun) {
                newestChange = getOrSetMatrixChangeSet(build, depot, newestChange, effectiveProjectPath, log);
            }

            if (lastChange <= 0) {
                lastChange = newestChange - MAX_CHANGESETS_ON_FIRST_BUILD;
                if (lastChange < 0) {
                    lastChange = 0;
                }
            }

            // Get ChangeLog
            if (!disableChangeLogOnly) {
                int lastChangeToDisplay = lastChange+1;
                if (lastChange > newestChange) {
                    // If we're building an older change, display it anyway
                    // TODO: This can be considered inconsistent behavior
                    lastChangeToDisplay = newestChange;
                }

                List<Integer> changeNumbersTo;
                if (useViewMaskForChangeLog && useViewMask) {
                    changeNumbersTo = depot.getChanges().getChangeNumbersInRange(p4workspace, lastChangeToDisplay, newestChange, viewMask, showIntegChanges);
                } else {
                    changeNumbersTo = depot.getChanges().getChangeNumbersInRange(p4workspace, lastChangeToDisplay, newestChange, showIntegChanges);
                }
                changes = depot.getChanges().getChangelistsFromNumbers(changeNumbersTo, fileLimit);

                if (!changes.isEmpty()) {
                    // Save the changes we discovered.
                    PerforceChangeLogSet.saveToChangeLog(
                            new FileOutputStream(changelogFile), changes);
                    newestChange = changes.get(0).getChangeNumber();
                    // Get and store information about committers
                    retrieveUserInformation(depot, changes);
                } else {
                    // No new changes discovered (though the definition of the workspace or label may have changed).
                    createEmptyChangeLog(changelogFile, listener, "changelog");
                }
            }

            // Sync workspace
            if (!disableSyncOnly) {
                // Now we can actually do the sync process...
                StringBuilder sbMessage = new StringBuilder("Sync'ing workspace to ");
                StringBuilder sbSyncPath = new StringBuilder(p4WorkspacePath);
                StringBuilder sbSyncPathSuffix = new StringBuilder();
                sbSyncPathSuffix.append("@");

                if (p4Label != null && !p4Label.trim().isEmpty()) {
                    sbMessage.append("label ");
                    sbMessage.append(p4Label);
                    sbSyncPathSuffix.append(p4Label);
                } else {
                    sbMessage.append("changelist ");
                    sbMessage.append(newestChange);
                    sbSyncPathSuffix.append(newestChange);
                }

                sbSyncPath.append(sbSyncPathSuffix);

                if (forceSync || alwaysForceSync)
                    sbMessage.append(" (forcing sync of unchanged files).");
                else
                    sbMessage.append(".");

                log.println(sbMessage.toString());
                String syncPath = sbSyncPath.toString();

                long startTime = System.currentTimeMillis();

                if (useViewMaskForSyncing && useViewMask) {
                    for (String path : viewMask.replaceAll("\r", "").split("\n")) {
                        StringBuilder sbMaskPath = new StringBuilder(path);
                        sbMaskPath.append(sbSyncPathSuffix);
                        String maskPath = sbMaskPath.toString();
                        depot.getWorkspaces().syncTo(maskPath, forceSync || alwaysForceSync, dontUpdateServer);
                    }
                } else {
                    depot.getWorkspaces().syncTo(syncPath, forceSync || alwaysForceSync, dontUpdateServer);
                }
                long endTime = System.currentTimeMillis();
                long duration = endTime - startTime;

                log.println("Sync complete, took " + duration + " ms");
            }

            boolean doSaveProject = false;
            // reset one time use variables...
            if (this.forceSync == true || this.firstChange != -1) {
                this.forceSync = false;
                this.firstChange = -1;
                // save the one-time use variables...
                doSaveProject = true;
            }
            // If we aren't managing the client views, update the current ones
            // with those from perforce, and save them if they have changed.
            if (!this.updateView && !effectiveProjectPath.equals(this.projectPath)) {
                this.projectPath = effectiveProjectPath;
                doSaveProject = true;
            }
            if (doSaveProject) {
                build.getParent().save();
            }

            // Add tagging action that enables the user to create a label
            // for this build.
            build.addAction(new PerforceTagAction(
                build, depot, newestChange, effectiveProjectPath, MacroStringHelper.substituteParameters(getEffectiveP4User(), this, build, null)));

            build.addAction(new PerforceSCMRevisionState(newestChange));

            if (p4Counter != null && updateCounterValue) {
                // Set or create a counter to mark this change
                Counter counter = new Counter();
                String counterName = MacroStringHelper.substituteParameters(this.p4Counter, this, build, null);
                counter.setName(counterName);
                counter.setValue(newestChange);
                log.println("Updating counter " + counterName + " to " + newestChange);
                depot.getCounters().saveCounter(counter);
            }

            // remember the p4Ticket if we were issued one
			// otherwise keep the last one issued
			if (depot.getP4Ticket() != null)
	            p4Ticket = depot.getP4Ticket();

            return true;

        } catch (PerforceException e) {
            log.print("Caught exception communicating with perforce. " + e.getMessage());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw, true);
            e.printStackTrace(pw);
            pw.flush();
            log.print(sw.toString());
            throw new AbortException(
                    "Unable to communicate with perforce. " + e.getMessage());

        } catch (InterruptedException e) {
            throw new IOException(
                    "Unable to get hostname from slave. " + e.getMessage());
        } catch (NullPointerException e) {
			log.print("Caught exception in perforce-plugin. " + e.getMessage());
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw, true);
            e.printStackTrace(pw);
            pw.flush();
            log.print(sw.toString());
            throw new AbortException(
                    "Caught exception in perfoce-plugin. " + e.getMessage());
		}


    }

    private synchronized int getOrSetMatrixChangeSet(
            @Nonnull AbstractBuild build,
            @Nonnull Depot depot, int newestChange, String projectPath,
            @Nonnull PrintStream log)
            throws ParameterSubstitutionException, InterruptedException
    {
        int matrixLastChange = 0;
        // special consideration for matrix builds
        if (build instanceof MatrixRun) {
            log.println("This is a matrix run, trying to use change number from parent/siblings...");
            AbstractBuild parentBuild = ((MatrixRun) build).getParentBuild();
            if (parentBuild != null) {
                int parentChange = getLastChange(parentBuild);
                if (parentChange > 0) {
                    // use existing changeset from parent
                    log.println("Latest change from parent is: "+Integer.toString(parentChange));
                    matrixLastChange = parentChange;
                } else {
                    // no changeset on parent, set it for other
                    // matrixruns to use
                    log.println("No change number has been set by parent/siblings. Using latest.");
                    parentBuild.addAction(new PerforceTagAction(build, depot, newestChange, projectPath,
                            MacroStringHelper.substituteParameters(getEffectiveP4User(), this, build, null)));
                }
            }
        }
        return matrixLastChange;
    }

    /**
     * compute the path(s) that we search on to detect whether the project
     * has any unsynched changes
     *
     * @param p4workspace the workspace
     * @return a string of path(s), e.g. //mymodule1/... //mymodule2/...
     */
    private String getChangesPaths(@Nonnull Workspace p4workspace) {
        return PerforceSCMHelper.computePathFromViews(p4workspace.getViews());
    }

    @Override
    public PerforceRepositoryBrowser getBrowser() {
       return browser;
    }

    /*
     * @see hudson.scm.SCM#createChangeLogParser()
     */
    @Override
    public ChangeLogParser createChangeLogParser() {
        return new PerforceChangeLogParser();
    }

    /**
     * Part of the new polling routines. This determines the state of the build specified.
     * @param ab
     * @param lnchr
     * @param tl
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    public SCMRevisionState calcRevisionsFromBuild(AbstractBuild<?, ?> ab, Launcher lnchr, TaskListener tl) throws IOException, InterruptedException {
        //This shouldn't be getting called, but in case it is, let's calculate the revision anyways.
        PerforceTagAction action = (PerforceTagAction)ab.getAction(PerforceTagAction.class);
        if (action==null) {
            //something went wrong...
            return null;
        }
        return new PerforceSCMRevisionState(action.getChangeNumber());
    }

    /**
     * Part of the new polling routines. This compares the specified revision state with the repository,
     * and returns a polling result.
     * @param scmrs
     * @return
     * @throws IOException
     * @throws InterruptedException
     */
    @Override
    protected PollingResult compareRemoteRevisionWith(AbstractProject<?, ?> project, Launcher launcher, FilePath workspace, TaskListener listener, SCMRevisionState scmrs) throws IOException, InterruptedException {
        PrintStream logger = listener.getLogger();
        logger.println("Looking for changes...");
        final PerforceSCMRevisionState baseline;

        if (scmrs instanceof PerforceSCMRevisionState) {
            baseline = (PerforceSCMRevisionState)scmrs;
        } else if (project.getLastBuild() != null) {
            baseline = (PerforceSCMRevisionState)calcRevisionsFromBuild(project.getLastBuild(), launcher, listener);
        } else {
            baseline = new PerforceSCMRevisionState(-1);
        }

        if (project.getLastBuild() == null || baseline == null) {
            listener.getLogger().println("No previous builds to use for comparison.");
            return PollingResult.BUILD_NOW;
        }

        try {
            Node buildNode = getPollingNode(project);
            Depot depot;
            if (buildNode == null) {
                depot = getDepot(launcher,workspace,project,null,buildNode);
                logger.println("Using master");
            } else {
                depot = getDepot(buildNode.createLauncher(listener),buildNode.getRootPath(),project,null, buildNode);
                logger.println("Using node: " + buildNode.getDisplayName());
            }

            Workspace p4workspace = getPerforceWorkspace(project, getEffectiveProjectPath(null, project, buildNode, logger, depot), depot, buildNode, null, launcher, workspace, listener, true);
            saveWorkspaceIfDirty(depot, p4workspace, logger);

            int lastChangeNumber = baseline.getRevision();
            SCMRevisionState repositoryState = getCurrentDepotRevisionState(p4workspace, project, buildNode, depot, logger, lastChangeNumber);

            PollingResult.Change change;
            if (repositoryState.equals(baseline)) {
                change = PollingResult.Change.NONE;
            } else {
                change = PollingResult.Change.SIGNIFICANT;
            }

            return new PollingResult(baseline, repositoryState, change);

        } catch (PerforceException e) {
            System.out.println("Problem: " + e.getMessage());
            logger.println("Caught Exception communicating with perforce." + e.getMessage());
            throw new IOException("Unable to communicate with perforce.  Check log file for: " + e.getMessage());
        }
    }

    @CheckForNull
    private Node getPollingNode(@Nonnull AbstractProject project) {
        Node buildNode = project.getLastBuiltOn();
        if (pollOnlyOnMaster) {
            buildNode = null;
        } else {
            // try to get an active node that the project is configured to use
            if (!isNodeOnline(buildNode)) {
                buildNode = null;
            }
            if (buildNode == null && !pollOnlyOnMaster) {
                buildNode = getOnlineConfiguredNode(project);
            }
            if (pollOnlyOnMaster) {
                buildNode = null;
            }
        }
        return buildNode;
    }

    @CheckForNull
    private Node getOnlineConfiguredNode(@Nonnull AbstractProject project) {
        Node buildNode = null;
        for (Node node : Hudson.getInstance().getNodes()) {
            hudson.model.Label l = project.getAssignedLabel();
            if (l != null && !l.contains(node)) {
                continue;
            }
            if (l == null && node.getMode() == hudson.model.Node.Mode.EXCLUSIVE) {
                continue;
            }
            if (!isNodeOnline(node)) {
                continue;
            }
            buildNode = node;
            break;
        }
        return buildNode;
    }

    private boolean isNodeOnline(@CheckForNull Node node) {
        return node != null && node.toComputer() != null && node.toComputer().isOnline();
    }

    private SCMRevisionState getCurrentDepotRevisionState(
            @Nonnull Workspace p4workspace,
            @CheckForNull AbstractProject project,
            @CheckForNull Node node, @Nonnull Depot depot,
        PrintStream logger, int lastChangeNumber) throws IOException, InterruptedException, PerforceException {

        int highestSelectedChangeNumber;
        List<Integer> changeNumbers;

        if (p4Counter != null && !updateCounterValue) {

            // If this is a downstream build that triggers by polling the set counter
            // use the counter as the value for the newest change instead of the workspace view

            Counter counter = depot.getCounters().getCounter(p4Counter);
            highestSelectedChangeNumber = counter.getValue();
            logger.println("Latest submitted change selected by named counter is " + highestSelectedChangeNumber);
            String root = "//" + p4workspace.getName() + "/...";
            changeNumbers = depot.getChanges().getChangeNumbersInRange(p4workspace, lastChangeNumber+1, highestSelectedChangeNumber, root, false);
        } else {
            // General Case

            // Has any new change been submitted since then (that is selected
            // by this workspace).

            Integer newestChange;
            String effectiveP4Label = MacroStringHelper.substituteParameters(
                    this.p4Label, this, project, node, null);
            if (effectiveP4Label != null && !effectiveP4Label.trim().isEmpty()) {
                //In case where we are using a rolling label.
                String root = "//" + p4workspace.getName() + "/...";
                newestChange = depot.getChanges().getHighestLabelChangeNumber(p4workspace, effectiveP4Label.trim(), root);
            } else {
                Counter counter = depot.getCounters().getCounter("change");
                newestChange = counter.getValue();
            }

            if (useViewMaskForPolling && useViewMask) {
                changeNumbers = depot.getChanges().getChangeNumbersInRange(p4workspace, lastChangeNumber+1, newestChange,
                        MacroStringHelper.substituteParameters(viewMask, this, project, node, null), false);
            } else {
                String root = "//" + p4workspace.getName() + "/...";
                changeNumbers = depot.getChanges().getChangeNumbersInRange(p4workspace, lastChangeNumber+1, newestChange, root, false);
            }
            if (changeNumbers.isEmpty()) {
                // Wierd, this shouldn't be!  I suppose it could happen if the
                // view selects no files (e.g. //depot/non-existent-branch/...).
                // This can also happen when using view masks with polling.
                logger.println("No changes found.");
                return new PerforceSCMRevisionState(lastChangeNumber);
            } else {
                highestSelectedChangeNumber = changeNumbers.get(0).intValue();
                logger.println("Latest submitted change selected by workspace is " + highestSelectedChangeNumber);
            }
        }

        if (lastChangeNumber >= highestSelectedChangeNumber) {
            // Note, can't determine with currently saved info
            // whether the workspace definition has changed.
            logger.println("Assuming that the workspace definition has not changed.");
            return new PerforceSCMRevisionState(lastChangeNumber);
        }
        else {
            for (int changeNumber : changeNumbers) {
                if (isChangelistExcluded(depot.getChanges().getChangelist(changeNumber, fileLimit),
                        project, node, p4workspace.getViewsAsString(), logger)) {
                    logger.println("Changelist "+changeNumber+" is composed of file(s) and/or user(s) that are excluded.");
                } else {
                    return new PerforceSCMRevisionState(changeNumber);
                }
            }
            return new PerforceSCMRevisionState(lastChangeNumber);
        }
    }

    /**
     * Determines whether or not P4 changelist should be excluded and ignored by the polling trigger.
     * Exclusions include files, regex patterns of files, and/or changelists submitted by a specific user(s).
     *
     * @param changelist the p4 changelist
     * @return  True if changelist only contains user(s) and/or file(s) that are denoted to be excluded
     */
    private boolean isChangelistExcluded(Changelist changelist,
            AbstractProject project, Node node, String view, PrintStream logger)
            throws ParameterSubstitutionException, InterruptedException
    {
        if (changelist == null) {
            return false;
        }

        if (excludedUsers != null && !excludedUsers.trim().equals("")) {
            List<String> users = Arrays.asList(
                    MacroStringHelper.substituteParameters(excludedUsers,this, project, node, null).split("\n"));

            if (users.contains(changelist.getUser())) {
                logger.println("Excluded User [" + changelist.getUser() + "] found in changelist.");
                return true;
            }

            // no literal match, try regex
            for (String regex : users) {
                try {
                    Matcher matcher = Pattern.compile(regex).matcher(changelist.getUser());
                    if (matcher.find()) {
                        logger.println("Excluded User ["+changelist.getUser()+"] found in changelist.");
                        return true;
                    }
                } catch (PatternSyntaxException pse) {
                    break;  // should never occur since we validate regex input before hand, but just be safe
                }
            }
        }

        if (excludedFiles != null && !excludedFiles.trim().equals("")) {
            List<String> files = Arrays.asList(
                    MacroStringHelper.substituteParameters(excludedFiles, this, project, node, null).split("\n"));

            if (!files.isEmpty() && !changelist.getFiles().isEmpty()) {
                StringBuilder buff = new StringBuilder("Exclude file(s) found:\n");
                for (FileEntry f : changelist.getFiles()) {
                    if (!doesFilenameMatchAnyP4Pattern(f.getFilename(),files,excludedFilesCaseSensitivity) &&
                            isFileInView(f.getFilename(), view, excludedFilesCaseSensitivity)) {
                        return false;
                    }

                    buff.append("\t").append(f.getFilename());
                }

                logger.println(buff.toString());
                return true;    // get here means changelist contains only file(s) to exclude
            }
        }

        return false;
    }

    private static boolean doesFilenameMatchAnyP4Pattern(String filename,
            @Nonnull List<String> patternStrings, boolean caseSensitive) {
        for (String patternString : patternStrings) {
            if (patternString.trim().equals("")) continue;
            if (doesFilenameMatchP4Pattern(filename, patternString, caseSensitive)) {
                return true;
            }
        }
        return false;
    }

    public static boolean doesFilenameMatchP4Pattern(String filename, String patternString,
            boolean caseSensitive) throws PatternSyntaxException {
        patternString = patternString.trim();
        filename = filename.trim();
        patternString = patternString.replaceAll("\\*", "[^/]*");
        patternString = patternString.replaceAll("\\.\\.\\.", ".*");
        patternString = patternString.replaceAll("%%[0-9]", "[^/]*");
        patternString = patternString.replaceAll("^\"", "");
        patternString = patternString.replaceAll("\"$", "");
        filename = filename.replaceAll("^\"", "");
        filename = filename.replaceAll("\"$", "");
        Pattern pattern = Pattern.compile(patternString, !caseSensitive ? Pattern.CASE_INSENSITIVE : 0);
        Matcher matcher = pattern.matcher(filename);
        return matcher.matches();
    }

    private void flushWorkspaceTo0(Depot depot, Workspace p4workspace, PrintStream log) throws PerforceException {
        saveWorkspaceIfDirty(depot, p4workspace, log);
        depot.getWorkspaces().flushTo("//" + p4workspace.getName() + "/...#0");
    }

    // TODO Handle the case where p4Label is set.
    private boolean wouldSyncChangeWorkspace(AbstractProject project, Depot depot,
            PrintStream logger) throws IOException, InterruptedException, PerforceException {

        Workspaces workspaces = depot.getWorkspaces();
        String result = workspaces.syncDryRun().toString();

        if (result.startsWith("File(s) up-to-date.")) {
            logger.println("Workspace up-to-date.");
            return false;
        } else {
            logger.println("Workspace not up-to-date.");
            return true;
        }
    }

    public int getLastChange(@CheckForNull Run build) {
    	// If we are starting a new hudson project on existing work and want to skip the prior history...
    	if (firstChange > 0)
    		return firstChange;

    	return getLastChangeNoFirstChange(build);
    }

    private static int getLastChangeNoFirstChange(@CheckForNull Run build) {

        // If we can't find a PerforceTagAction, we will default to 0.

        PerforceTagAction action = getMostRecentTagAction(build);
        if (action == null)
            return 0;

        //log.println("Found last change: " + action.getChangeNumber());
        return action.getChangeNumber();
    }

    @CheckForNull
    private static PerforceTagAction getMostRecentTagAction(@CheckForNull Run build) {
        if (build == null)
            return null;

        PerforceTagAction action = build.getAction(PerforceTagAction.class);
        if (action != null)
            return action;

        // if build had no actions, keep going back until we find one that does.
        return getMostRecentTagAction(build.getPreviousBuild());
    }

    private com.tek42.perforce.model.Workspace getPerforceWorkspace(AbstractProject project, String projectPath,
            Depot depot, Node buildNode, AbstractBuild build,
            Launcher launcher, FilePath workspace, TaskListener listener, boolean dontChangeRoot)
        throws java.io.IOException, java.lang.InterruptedException, com.tek42.perforce.PerforceException
    {
        return getPerforceWorkspace(project, projectPath, depot, buildNode, build, launcher, workspace, listener, dontChangeRoot, updateView);
    }

    private Workspace getPerforceWorkspace(AbstractProject project, String projectPath, Depot depot, Node buildNode, AbstractBuild build, Launcher launcher, FilePath workspace, TaskListener listener, boolean dontChangeRoot, boolean updateView) throws IOException, InterruptedException, PerforceException {
        PrintStream log = listener.getLogger();

        // If we are building on a slave node, and each node is supposed to have
        // its own unique client, then adjust the client name accordingly.
        // make sure each slave has a unique client name by adding it's
        // hostname to the end of the client spec

        String effectiveP4Client = build != null
                ? getEffectiveClientName(build, null)
                : getEffectiveClientName(project, buildNode);

        // If we are running concurrent builds, the Jenkins workspace path is different
        // for each concurrent build. Append Perforce workspace name with Jenkins
        // workspace identifier suffix. But, only do this if we are syncing or allowing
        // Jenkins to manage workspaces.
        if(!this.disableSyncOnly || this.createWorkspace) {
            effectiveP4Client = getConcurrentClientName(workspace, effectiveP4Client);
        }

        if (!nodeIsRemote(buildNode)) {
            log.print("Using master perforce client: ");
            log.println(effectiveP4Client);
        }
        else if (dontRenameClient) {
            log.print("Using shared perforce client: ");
            log.println(effectiveP4Client);
        }
        else {
            log.println("Using remote perforce client: " + effectiveP4Client);
        }

        depot.setClient(effectiveP4Client);
        String effectiveP4Stream = MacroStringHelper.substituteParameters(this.p4Stream, this, build, project, buildNode, null);

        // Get the clientspec (workspace) from perforce
        Workspace p4workspace = depot.getWorkspaces().getWorkspace(effectiveP4Client, effectiveP4Stream);
        assert p4workspace != null;
        boolean creatingNewWorkspace = p4workspace.isNew();

        // If the client workspace doesn't exist, and we're not managing the clients,
        // Then terminate the build with an error
        if (!createWorkspace && creatingNewWorkspace) {
            log.println("*** Perforce client workspace '" + effectiveP4Client +"' doesn't exist.");
            log.println("*** Please create it, or allow Jenkins to manage clients on it's own.");
            log.println("*** If the client name mentioned above is not what you expected, ");
            log.println("*** check your 'Client name format for slaves' advanced config option.");
            throw new AbortException("Error accessing perforce workspace.");
        }

        // Ensure that the clientspec (workspace) name is set correctly
        // TODO Examine why this would be necessary.

        p4workspace.setName(effectiveP4Client);

        // Set the workspace options according to the configuration
        if (projectOptions != null)
            p4workspace.setOptions(projectOptions);

        // Set the line ending option according to the configuration
        if (lineEndValue != null && getAllLineEndChoices().contains(lineEndValue)) {
            p4workspace.setLineEnd(lineEndValue);
        }

        if (clientOwner != null && !clientOwner.trim().isEmpty()) {
            p4workspace.setOwner(clientOwner);
        }

        // Ensure that the root is appropriate (it might be wrong if the user
        // created it, or if we previously built on another node).

        // Both launcher and workspace can be null if requiresWorkspaceForPolling returns true
        // So provide 'reasonable' default values.
        boolean isunix = true;
        if (launcher != null)
            isunix = launcher.isUnix();

        String localPath = unescapeP4String(p4workspace.getRoot());

        if (workspace != null)
            localPath = getLocalPathName(workspace, isunix);
        else if (localPath.trim().equals(""))
            localPath = project.getRootDir().getAbsolutePath();
        localPath = escapeP4String(localPath);

        if (!localPath.equals(p4workspace.getRoot()) && !dontChangeRoot && !dontUpdateClient) {
            log.println("Changing P4 Client Root to: " + localPath);
            forceSync = true;
            p4workspace.setRoot(localPath);
        }

        if (updateView || creatingNewWorkspace) {
            // Switch to another stream view if necessary
            if (useStreamDepot) {
                p4workspace.setStream(effectiveP4Stream);
            }
            // If necessary, rewrite the views field in the clientspec. Also, clear the stream.
            // TODO If dontRenameClient==false, and updateView==false, user
            // has a lot of work to do to maintain the clientspecs.  Seems like
            // we could copy from a master clientspec to the slaves.
            else {
                p4workspace.setStream("");
                if (useClientSpec) {
                    projectPath = getEffectiveProjectPathFromFile(build, project, buildNode, log, depot);
                }
                List<String> mappingPairs = parseProjectPath(projectPath, effectiveP4Client, log);
                if (!equalsProjectPath(mappingPairs, p4workspace.getViews())) {
                    log.println("Changing P4 Client View from:\n" + p4workspace.getViewsAsString());
                    log.println("Changing P4 Client View to: ");
                    p4workspace.clearViews();
                    for (int i = 0; i < mappingPairs.size(); ) {
                        String depotPath = mappingPairs.get(i++);
                        String clientPath = mappingPairs.get(i++);
                        p4workspace.addView(" " + depotPath + " " + clientPath);
                        log.println("  " + depotPath + " " + clientPath);
                    }
                }
            }
        }
        // Clean host field so the client can be used on other slaves
        // such as those operating with the workspace on a network share
        p4workspace.setHost("");

        // NOTE: The workspace is not saved.
        return p4workspace;
    }

    private String getEffectiveClientName(@Nonnull AbstractBuild build, @CheckForNull Map<String,String> env)
            throws ParameterSubstitutionException, InterruptedException {
        final Node buildNode = build.getBuiltOn();
        final Map<String, String> extraVars = new TreeMap<String,String>();
        if (env != null) {
            extraVars.putAll(env);
        }

        String effectiveP4Client;
        if (buildNode!=null && nodeIsRemote(buildNode)) {
            final String effectiveSlaveClientNameFormat = getSlaveClientNameFormat();
            extraVars.put("basename", substituteParameters(this.p4Client, this, build, extraVars));
            effectiveP4Client = substituteParameters(effectiveSlaveClientNameFormat, this, build, extraVars);
        } else { // don't use node formats on master
            effectiveP4Client = substituteParameters(this.p4Client, this, build, extraVars);
        }

        // eliminate spaces, just in case
        effectiveP4Client = effectiveP4Client.replaceAll(" ", "_");
        return effectiveP4Client;
    }

    private String getEffectiveClientName(@CheckForNull AbstractProject project, @CheckForNull Node buildNode)
            throws IOException, InterruptedException {

        String effectiveP4Client;
        if (buildNode!=null && nodeIsRemote(buildNode)) {
            final String effectiveRemoteClientNameFormat = getSlaveClientNameFormat();
            Map<String, String> extraVars = new TreeMap<String,String>();
            extraVars.put("basename", substituteParameters(this.p4Client, this, project, buildNode, null));
            effectiveP4Client = substituteParameters (effectiveRemoteClientNameFormat, this, project, buildNode, extraVars);
        } else {
            effectiveP4Client = substituteParameters(this.p4Client, this, project, buildNode, null);
        }

        // eliminate spaces, just in case
        effectiveP4Client = effectiveP4Client.replaceAll(" ", "_");
        return effectiveP4Client;
    }

    /**
     * Gets the client format for remote nodes.
     * @return Client format for remote nodes.
     *      Null and empty values will be replaced by a default format
     */
    public @Nonnull String getSlaveClientNameFormat() {
        @CheckForNull String effectiveSlaveClientNameFormat = Util.fixEmpty(this.slaveClientNameFormat);
        if (effectiveSlaveClientNameFormat != null) {
            return effectiveSlaveClientNameFormat;
        }

        // Modify the field and return the correct value
        if (this.dontRenameClient) {
            effectiveSlaveClientNameFormat = "${basename}";
        } else if (this.useOldClientName) {
            effectiveSlaveClientNameFormat = "${basename}-${hostname}";
        } else {
            //Hash should be the new default
            effectiveSlaveClientNameFormat = "${basename}-${hash}";
        }
        return effectiveSlaveClientNameFormat;
    }

    private boolean nodeIsRemote(@CheckForNull Node buildNode) {
        return buildNode != null && buildNode.getNodeName().length() != 0;
    }

    private void saveWorkspaceIfDirty(Depot depot, Workspace p4workspace, PrintStream log) throws PerforceException {
        if (dontUpdateClient) {
            log.println("'Don't update client' is set. Not saving the client changes.");
            return;
        }
        if (p4workspace.isNew()) {
            log.println("Saving new client " + p4workspace.getName());
            depot.getWorkspaces().saveWorkspace(p4workspace);
        } else if (p4workspace.isDirty()) {
            log.println("Saving modified client " + p4workspace.getName());
            depot.getWorkspaces().saveWorkspace(p4workspace);
        }
    }

    public static String escapeP4String(String string) {
        if (string == null) return null;
        String result = new String(string);
        result = result.replace("%","%25");
        result = result.replace("@","%40");
        result = result.replace("#","%23");
        result = result.replace("*","%2A");
        return result;
    }

    public static String unescapeP4String(String string) {
        if (string == null) return null;
        String result = new String(string);
        result = result.replace("%40","@");
        result = result.replace("%23","#");
        result = result.replace("%2A","*");
        result = result.replace("%25","%");
        return result;
    }

    /**
     * Append Perforce workspace name with a Jenkins workspace identifier, if this
     * is a concurrent build job.
     *
     * @param workspace Workspace of the current build
     * @param p4Client User defined client name
     * @return The new client name. If this is a concurrent build with, append the
     * client name with a Jenkins workspace identifier.
     */
    private String getConcurrentClientName(FilePath workspace, String p4Client) {
        if (workspace != null) {
            // Match @ followed by an integer at the end of the workspace path
            Pattern p = Pattern.compile(".*" + Pattern.quote(WORKSPACE_COMBINATOR) + "(\\d+)$");
            Matcher matcher = p.matcher(workspace.getRemote());
            if (matcher.find()) {
                p4Client += "_" + matcher.group(1);
            }
        }
        return p4Client;
    }

    @Extension
    public static final class PerforceSCMDescriptor extends SCMDescriptor<PerforceSCM> {
        private String p4ClientPattern;
        /**
         * Defines timeout for BufferedReader::readLine() operations (in seconds).
         * Zero value means "infinite";
         */
        private Integer p4ReadlineTimeout;
        /**DIsables expose of Perforce password to the build environment*/
        private boolean passwordExposeDisabled;

        private @CheckForNull String p4DefaultUser;
        private @CheckForNull String p4DefaultPassword;

        private final static int P4_INFINITE_TIMEOUT_SEC = 0;
        private final static int P4_MINIMAL_TIMEOUT_SEC = 30;

        public PerforceSCMDescriptor() {
            super(PerforceSCM.class, PerforceRepositoryBrowser.class);
            load();
        }

        public String getDisplayName() {
            return "Perforce";
        }

        @Override
        public SCM newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return (PerforceSCM)super.newInstance(req, formData);
        }

        /**Generates a random key for p4.config.instanceID*/
        private static final AtomicLong P4_INSTANCE_COUNTER = new AtomicLong();
        public String generateP4InstanceID() {
            // There's no problem even if the counter reaches overflow
            return Long.toString(P4_INSTANCE_COUNTER.incrementAndGet());
        }

        /**
         * List available tool installations.
         *
         * @return list of available p4 tool installations
         */
        public List<PerforceToolInstallation> getP4Tools() {
            PerforceToolInstallation[] p4ToolInstallations = Hudson.getInstance().getDescriptorByType(PerforceToolInstallation.DescriptorImpl.class).getInstallations();
            return Arrays.asList(p4ToolInstallations);
        }

        /**
         * Gets client workspace name pattern
         */
        public String getP4ClientPattern() {
            if (p4ClientPattern == null) {
                return ".*";
            } else {
                return p4ClientPattern;
            }
        }

        /**
         * Gets ReadLine timeout.
         * @since 1.4.0
         */
        public int getP4ReadLineTimeout() {
            if (p4ReadlineTimeout == null) {
                return P4_INFINITE_TIMEOUT_SEC;
            } else {
                return p4ReadlineTimeout;
            }
        }

        public String getP4ReadLineTimeoutStr() {
            return hasP4ReadlineTimeout() ? p4ReadlineTimeout.toString() : "";
        }

        public boolean isPasswordExposeDisabled() {
            return passwordExposeDisabled;
        }

        /**
         * @since 1.3.31
         */
        private void setDefaultP4Passwd(@CheckForNull String passwd) {
            if (passwd == null) {
                p4DefaultPassword = null;
                return;
            }

            PerforcePasswordEncryptor encryptor = new PerforcePasswordEncryptor();
            if (encryptor.appearsToBeAnEncryptedPassword(passwd)) {
                p4DefaultPassword = passwd;
            } else {
                p4DefaultPassword = encryptor.encryptString(passwd);
            }
        }

        /**
         * Get the default password
         * @return Password or empty string if it does not set
         * @since 1.3.31
         */
        public @Nonnull String getP4DefaultPassword() {
            return p4DefaultPassword != null ? p4DefaultPassword : "";
        }

        /**
         * Decrypts the default password.
         * @return Password or empty string if it does not set
         * @since 1.3.31
         */
        public @Nonnull String getDecryptedP4DefaultPassword() {
            PerforcePasswordEncryptor encryptor = new PerforcePasswordEncryptor();
            return encryptor.decryptString(p4DefaultPassword);
        }

        /**
         * Gets the default user id.
         * @return null if the user is not specified
         * @since 1.3.31
         */
        public @CheckForNull String getP4DefaultUser() {
            return p4DefaultUser;
        }

        /**
         * Checks if plugin has ReadLine timeout.
         * @since 1.4.0
         */
        public boolean hasP4ReadlineTimeout() {
            return getP4ReadLineTimeout() != P4_INFINITE_TIMEOUT_SEC;
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
            p4ClientPattern = Util.fixEmpty(req.getParameter("p4.clientPattern").trim());
            p4DefaultUser = Util.fixEmptyAndTrim(req.getParameter("p4.defaultUser"));
            setDefaultP4Passwd(Util.fixEmptyAndTrim(req.getParameter("p4.defaultPassword")));

            passwordExposeDisabled = json.getBoolean("passwordExposeDisabled");

            // ReadLine timeout
            String p4timeoutStr = Util.fixEmpty(req.getParameter("p4.readLineTimeout").trim());
            p4ReadlineTimeout = P4_INFINITE_TIMEOUT_SEC;
            if (p4timeoutStr != null)
            {
                try {
                    int val = Integer.parseInt(p4timeoutStr);
                    p4ReadlineTimeout = val<P4_MINIMAL_TIMEOUT_SEC ? P4_INFINITE_TIMEOUT_SEC : val;
                } catch (NumberFormatException ex) {
                    //Do nothing - just ignore user's value
                }
            }

            save();
            return true;
        }

        public FormValidation doValidateNamePattern(StaplerRequest req) {
            String namePattern = Util.fixEmptyAndTrim(req.getParameter("value"));
            if (namePattern != null) {
                try {
                    Pattern.compile(namePattern);
                } catch (PatternSyntaxException exception) {
                    return FormValidation.error("Pattern format error:\n"+exception.getMessage());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doValidateP4ReadLineTimeout(StaplerRequest req) {
            String valueStr = Util.fixEmptyAndTrim(req.getParameter("value"));
              if (valueStr != null) {
                try {
                    int val = Integer.parseInt(valueStr);
                    if (val < P4_MINIMAL_TIMEOUT_SEC) {
                        return FormValidation.error("P4 ReadLine timeout should exceed "+P4_MINIMAL_TIMEOUT_SEC+" seconds. Value will be ignored");
                    }
                } catch (NumberFormatException ex) {
                    return FormValidation.error("Number format error: "+ex.getMessage());
                }
            }
            return FormValidation.ok();
        }

        public String isValidProjectPath(String path) {
            if (!path.startsWith("//")) {
                return "Path must start with '//' (Example: //depot/ProjectName/...)";
            }
            if (!path.endsWith("/...")) {
                if (!path.contains("@")) {
                    return "Path must end with Perforce wildcard: '/...'  (Example: //depot/ProjectName/...)";
                }
            }
            return null;
        }

        protected Depot getDepotFromRequest(StaplerRequest request) {
            String port = fixNull(request.getParameter("port")).trim();
            String tool = fixNull(request.getParameter("tool")).trim();

            // Credentials
            String user = fixNull(request.getParameter("user")).trim();
            String pass = fixNull(request.getParameter("pass")).trim();
            if (user.isEmpty()) {
                user = getP4DefaultUser();
                pass = getDecryptedP4DefaultPassword();
            }

            if (port.length() == 0 || tool.length() == 0) { // Not enough entered yet
                return null;
            }
            Depot depot = new Depot();
            depot.setUser(user);
            PerforcePasswordEncryptor encryptor = new PerforcePasswordEncryptor();
            if (encryptor.appearsToBeAnEncryptedPassword(pass)) {
                depot.setPassword(encryptor.decryptString(pass));
            } else {
                depot.setPassword(pass);
            }
            depot.setPort(port);

            String exe = "";
            PerforceToolInstallation[] installations = ((hudson.plugins.perforce.PerforceToolInstallation.DescriptorImpl)Hudson.getInstance().
                    getDescriptorByType(PerforceToolInstallation.DescriptorImpl.class)).getInstallations();
            for (PerforceToolInstallation i : installations) {
                if (i.getName().equals(tool)) {
                    exe = i.getP4Exe();
                }
            }
            depot.setExecutable(exe);

            try {
                Counter counter = depot.getCounters().getCounter("change");
                if (counter != null)
                    return depot;
            } catch (PerforceException e) {
            }

            return null;
        }

        public FormValidation doValidatePerforceUsername(StaplerRequest req) {
            String username = Util.fixEmptyAndTrim(req.getParameter("user"));
            if (username == null) {
                return FormValidation.warning("No user specified. A default user '"+getInstance().getP4DefaultUser()+"' will be used");
            }
            return FormValidation.ok();
        }

        /**
         * Checks if the perforce login credentials are good.
         */
        public FormValidation doValidatePerforceLogin(StaplerRequest req) {
            Depot depot = getDepotFromRequest(req);
            if (depot != null) {
                try {
                    depot.getStatus().isValid();
                } catch (PerforceException e) {
                    return FormValidation.error(e.getMessage());
                }
            }
            return FormValidation.ok();
        }

        /**
         * Checks to see if the specified workspace is valid.
         * The method also checks forbidden variables in the client name.
         * (see <a href="https://wiki.jenkins-ci.org/display/JENKINS/Perforce+Plugin">
         * Perforce Plugin Wiki page</a>)
         * to get the clarification of forbidden variables.
         * An improper usage of the variable may corrupt Perforce workspaces in project builds.
         */
        public FormValidation doValidateP4Client(StaplerRequest req) {
            String workspace = Util.fixEmptyAndTrim(req.getParameter("client"));
            if (workspace == null) {
                return FormValidation.error("You must enter a workspaces name");
            }
            try {
                // Check P4 client pattern first, because workspace check fails on valid client names with variables
                if (!workspace.matches(getP4ClientPattern())) {
                    return FormValidation.error("Client name doesn't meet global pattern: "+getP4ClientPattern());
                }

                // Check forbidden variables
                for (String variableName : P4CLIENT_FORBIDDEN_VARIABLES) {
                    if (MacroStringHelper.containsVariable(workspace, variableName)) {
                        return FormValidation.error(hudson.plugins.perforce.Messages.
                                PerforceSCM_doValidateP4Client_forbiddenVariableError(variableName));
                    }
                }

                // Then, check depot
                Depot depot = getDepotFromRequest(req);
                if (depot == null) {
                    return FormValidation.error(
                            "Unable to check workspace against depot");
                }

                // Then, check workspace
                Workspace p4Workspace =
                    depot.getWorkspaces().getWorkspace(workspace, "");
                if (p4Workspace.getAccess() == null ||
                        p4Workspace.getAccess().equals(""))
                    return FormValidation.warning("Workspace does not exist. " +
                            "If \"Let Hudson/Jenkins Manage Workspace View\" is check" +
                            " the workspace will be automatically created.");
            } catch (PerforceException e) {
                return FormValidation.error(
                        "Error accessing perforce while checking workspace");
            }

            return FormValidation.ok();
        }

        /**
         * Performs syntactical check on the P4Label
         */
        public FormValidation doValidateP4Label(StaplerRequest req, @QueryParameter String label) throws IOException, ServletException {
            label = Util.fixEmptyAndTrim(label);
            if (label == null)
                return FormValidation.ok();

            Depot depot = getDepotFromRequest(req);
            if (depot != null) {
                try {
                    Label p4Label = depot.getLabels().getLabel(label);
                    if (p4Label.getAccess() == null || p4Label.getAccess().equals(""))
                        return FormValidation.error("Label does not exist");
                } catch (PerforceException e) {
                    return FormValidation.error(
                            "Error accessing perforce while checking label");
                }
            }
            return FormValidation.ok();
        }

        /**
         * Performs syntactical and permissions check on the P4Counter
         */
        public FormValidation doValidateP4Counter(StaplerRequest req, @QueryParameter String counter) {
            counter= Util.fixEmptyAndTrim(counter);
            if (counter == null)
                return FormValidation.ok();

            Depot depot = getDepotFromRequest(req);
            if (depot != null) {
                try {
                    Counters counters = depot.getCounters();
                    Counter p4Counter = counters.getCounter(counter);
                    // try setting the counter back to the same value to verify permissions
                    counters.saveCounter(p4Counter);
                } catch (PerforceException e) {
                    return FormValidation.error(
                            "Error accessing perforce while checking counter: " + e.getLocalizedMessage());
                }
            }
            return FormValidation.ok();
        }

        /**
         * Checks to see if the specified project exists and has p4 info.
         */
        public FormValidation doValidateP4UpstreamProject(StaplerRequest req, @QueryParameter String project) throws IOException, ServletException {
            project = Util.fixEmptyAndTrim(project);
            if (project == null) {
                // well, it is not really OK, but it means it will not be used, so no error
                return FormValidation.ok();
            }

            Job job = Hudson.getInstance().getItemByFullName(project, Job.class);
            if (job == null) {
                return FormValidation.error(Messages.BuildTrigger_NoSuchProject(project, AbstractProject.findNearest(project).getName()));
            }

            Run upStreamRun = job.getLastSuccessfulBuild();
            int lastUpStreamChange = getLastChangeNoFirstChange(upStreamRun);
            if (lastUpStreamChange < 1) {
                FormValidation.warning("No Perforce change found in this project");
            }

            return FormValidation.ok();
        }

        /**
         * Checks to see if the specified ClientSpec is valid.
         */
        public FormValidation doValidateClientSpec(StaplerRequest req) throws IOException, ServletException {
            Depot depot = getDepotFromRequest(req);
            if (depot == null) {
                return FormValidation.error(
                        "Unable to check ClientSpec against depot");
            }

            String clientspec = Util.fixEmptyAndTrim(req.getParameter("clientSpec"));
            if (clientspec == null) {
                return FormValidation.error("You must enter a path to a ClientSpec file");
            }

            if (!DEPOT_ONLY.matcher(clientspec).matches() &&
                !DEPOT_ONLY_QUOTED.matcher(clientspec).matches()) {
                return FormValidation.error("Invalid depot path:" + clientspec);
            }

            String workspace = Util.fixEmptyAndTrim(req.getParameter("client"));
            try {
                if (!depot.getStatus().exists(clientspec)) {
                    return FormValidation.error("ClientSpec does not exist");
                }

                Workspace p4Workspace = depot.getWorkspaces().getWorkspace(workspace, "");
                // Warn if workspace exists and is associated with a stream
                if (p4Workspace.getAccess() != null && !p4Workspace.getAccess().equals("") &&
                        p4Workspace.getStream() != null && !p4Workspace.getStream().equals("")) {
                    return FormValidation.warning("Workspace '" + workspace + "' already exists and is associated with a stream. " +
                        "If Jenkins is allowed to manage the workspace view, this workspace will be switched to a local workspace.");
                }
            } catch (PerforceException e) {
                return FormValidation.error(
                        "Error accessing perforce while checking ClientSpec: " + e.getLocalizedMessage());
            }

            return FormValidation.ok();
        }

        /**
         * Checks if the specified stream is valid.
         */
        public FormValidation doValidateStream(StaplerRequest req) throws IOException, ServletException {
            Depot depot = getDepotFromRequest(req);
            if (depot == null) {
                return FormValidation.error(
                        "Unable to check stream against depot");
            }

            String stream = Util.fixEmptyAndTrim(req.getParameter("stream"));
            if (stream == null) {
                return FormValidation.error("You must enter a stream");
            }
            if (!stream.endsWith("/...")) {
                stream += "/...";
            }

            if (!DEPOT_ONLY.matcher(stream).matches() &&
                !DEPOT_ONLY_QUOTED.matcher(stream).matches()) {
                return FormValidation.error("Invalid depot path:" + stream);
            }

            String workspace = Util.fixEmptyAndTrim(req.getParameter("client"));
            try {
                if (!depot.getStatus().exists(stream)) {
                    return FormValidation.error("Stream does not exist");
                }

                Workspace p4Workspace = depot.getWorkspaces().getWorkspace(workspace, "");
                // Warn if workspace exists and is not associated with a stream
                if (p4Workspace.getAccess() != null && !p4Workspace.getAccess().equals("") &&
                        (p4Workspace.getStream() == null || p4Workspace.getStream().equals(""))) {
                    return FormValidation.warning("Workspace '" + workspace + "' already exists and is not associated with a stream. " +
                        "If Jenkins is allowed to manage the workspace view, this workspace will be switched to a stream workspace.");
                }
            } catch (PerforceException e) {
                return FormValidation.error(
                        "Error accessing perforce while checking stream: " + e.getLocalizedMessage());
            }

            return FormValidation.ok();
        }

        public FormValidation doCheckViewMask(StaplerRequest req) {
            String view = Util.fixEmptyAndTrim(req.getParameter("viewMask"));
            if (view != null) {
                for (String path : view.replace("\r","").split("\n")) {
                    if (path.startsWith("-") || path.startsWith("\"-"))
                        return FormValidation.error("'-' not yet supported in view mask:" + path);
                    if (!DEPOT_ONLY.matcher(path).matches() &&
                        !DEPOT_ONLY_QUOTED.matcher(path).matches())
                        return FormValidation.error("Invalid depot path:" + path);
                }
            }
            return FormValidation.ok();
        }

        /**
         * Checks if the change list entered exists
         */
        public FormValidation doCheckChangeList(StaplerRequest req) {
            Depot depot = getDepotFromRequest(req);
            String change = fixNull(req.getParameter("change")).trim();

            if (change.length() == 0) { // nothing entered yet
                return FormValidation.ok();
            }
            if (depot != null) {
                try {
                    int number = Integer.parseInt(change);
                    Changelist changelist = depot.getChanges().getChangelist(number, -1);
                    if (changelist.getChangeNumber() != number)
                        throw new PerforceException("broken");
                } catch (Exception e) {
                    return FormValidation.error("Changelist: " + change + " does not exist.");
                }
            }
            return FormValidation.ok();
        }

        /**
         * Checks if the value is a valid user name/regex pattern.
         */
        public FormValidation doValidateExcludedUsers(StaplerRequest req) {
            String excludedUsers = fixNull(req.getParameter("excludedUsers")).trim();
            List<String> users = Arrays.asList(excludedUsers.split("\n"));

            for (String regex : users) {
                regex = regex.trim();
                if (regex.equals("")) continue;

                try {
                    regex = regex.replaceAll("\\$\\{[^\\}]*\\}","SOMEVARIABLE");
                    Pattern.compile(regex);
                }
                catch (PatternSyntaxException pse) {
                    return FormValidation.error("Invalid regular express ["+regex+"]: " + pse.getMessage());
                }
            }
            return FormValidation.ok();
        }

        /**
         * Checks if the value is a valid file path/regex file pattern.
         */
        public FormValidation doValidateExcludedFiles(StaplerRequest req) {
            String excludedFiles = fixNull(req.getParameter("excludedFiles")).trim();
            Boolean excludedFilesCaseSensitivity = Boolean.valueOf(fixNull(req.getParameter("excludedFilesCaseSensitivity")).trim());
            List<String> files = Arrays.asList(excludedFiles.split("\n"));
            for (String file : files) {
                // splitting with \n can still leave \r on some OS/browsers
                // trimming should eliminate it.
                file = file.trim();
                // empty line? lets ignore it.
                if (file.equals("")) continue;
                // check to make sure it's a valid file spec
                if ( !DEPOT_ONLY.matcher(file).matches() && !DEPOT_ONLY_QUOTED.matcher(file).matches() ) {
                    return FormValidation.error("Invalid file spec ["+file+"]: Not a perforce file spec.");
                }
                // check to make sure the globbing regex will work
                // (ie, in case there are special characters that the user hasn't escaped properly)
                try {
                    file = file.replaceAll("\\$\\{[^\\}]*\\}","SOMEVARIABLE");
                    doesFilenameMatchP4Pattern("somefile", file, excludedFilesCaseSensitivity);
                }
                catch (PatternSyntaxException pse) {
                    return FormValidation.error("Invalid file spec ["+file+"]: " + pse.getMessage());
                }
            }
            return FormValidation.ok();
        }

        public FormValidation doValidateForceSync(StaplerRequest req) {
            Boolean forceSync = Boolean.valueOf(fixNull(req.getParameter("forceSync")).trim());
            Boolean alwaysForceSync = Boolean.valueOf(fixNull(req.getParameter("alwaysForceSync")).trim());
            Boolean dontUpdateServer = Boolean.valueOf(fixNull(req.getParameter("dontUpdateServer")).trim());

            if ((forceSync || alwaysForceSync) && dontUpdateServer) {
                return FormValidation.error("Don't Update Server Database (-p) option is incompatible with force syncing! Either disable -p, or disable force syncing.");
            }
            return FormValidation.ok();
        }

        public List<String> getAllLineEndChoices() {
            List<String> allChoices = Arrays.asList(
                "local",
                "unix",
                "mac",
                "win",
                "share"
            );
            ArrayList<String> choices = new ArrayList<String>();
            // Order choices so that the current one is first in the list
            // This is required in order for tests to work, unfortunately
            // choices.add(lineEndValue);
            for (String choice : allChoices) {
                //if (!choice.equals(lineEndValue)) {
                    choices.add(choice);
                //}
            }
            return choices;
        }

        public String getAppName() {
            return Hudson.getInstance().getDisplayName();
        }

        @Extension
        public static class ItemListenerImpl extends ItemListener {
            @Override
            public void onRenamed(Item item, String oldName, String newName) {
                for( Project<?,?> p : Hudson.getInstance().getProjects() ) {
                    SCM scm = p.getScm();
                    if (scm instanceof PerforceSCM) {
                        PerforceSCM p4scm = (PerforceSCM) scm;
                        if (oldName.equals(p4scm.p4UpstreamProject)) {
                            p4scm.p4UpstreamProject = newName;
                            try {
                                p.save();
                            } catch (IOException e) {
                                LOGGER.log(Level.WARNING,
                                        "Failed to persist project setting during rename from "
                                                + oldName + " to " + newName, e);
                            }
                        }
                    }
                }
            }
        }

    }

    /* Regular expressions for parsing view mappings.
     */
    private static final Pattern COMMENT = Pattern.compile("^\\s*$|^#.*$");
    private static final Pattern DEPOT_ONLY = Pattern.compile("^\\s*[+-]?//\\S+?(/\\S+)\\s*$");
    private static final Pattern DEPOT_ONLY_QUOTED = Pattern.compile("^\\s*\"[+-]?//\\S+?(/[^\"]+)\"\\s*$");
    private static final Pattern DEPOT_AND_WORKSPACE =
            Pattern.compile("^\\s*([+-]?//\\S+?/\\S+)\\s+//\\S+?(/\\S+)\\s*$");
    private static final Pattern DEPOT_AND_WORKSPACE_QUOTED =
            Pattern.compile("^\\s*\"([+-]?//\\S+?/[^\"]+)\"\\s+\"//\\S+?(/[^\"]+)\"\\s*$");
    private static final Pattern DEPOT_AND_QUOTED_WORKSPACE =
            Pattern.compile("^\\s*([+-]?//\\S+?/\\S+)\\s+\"//\\S+?(/[^\"]+)\"\\s*$");
    private static final Pattern QUOTED_DEPOT_AND_WORKSPACE =
            Pattern.compile("^\\s*\"([+-]?//\\S+?/[^\"]+)\"\\s+//\\S+?(/\\S+)$\\s*");
    private static final String[] P4CLIENT_FORBIDDEN_VARIABLES=
            {"EXECUTOR_NUMBER"};


    /**
     * Parses the projectPath into a list of pairs of strings representing the depot and client
     * paths. Even items are depot and odd items are client.
     * <p>
     * This parser can handle quoted or non-quoted mappings, normal two-part mappings, or one-part
     * mappings with an implied right part. It can also deal with +// or -// mapping forms.
     */
    public static List<String> parseProjectPath(String projectPath, String p4Client) {
        PrintStream log = (new LogTaskListener(LOGGER, Level.WARNING)).getLogger();
        return parseProjectPath(projectPath, p4Client, log);
    }

    public static List<String> parseProjectPath(String projectPath, String p4Client, PrintStream log) {
        List<String> parsed = new ArrayList<String>();
        for (String line : projectPath.split("\n")) {
            Matcher depotOnly = DEPOT_ONLY.matcher(line);
            if (depotOnly.find()) {
                // add the trimmed depot path, plus a manufactured client path
                parsed.add(line.trim());
                parsed.add("//" + p4Client + depotOnly.group(1));
            } else {
                Matcher depotOnlyQuoted = DEPOT_ONLY_QUOTED.matcher(line);
                if (depotOnlyQuoted.find()) {
                    // add the trimmed quoted depot path, plus a manufactured quoted client path
                    parsed.add(line.trim());
                    parsed.add("\"//" + p4Client + depotOnlyQuoted.group(1) + "\"");
                } else {
                    Matcher depotAndWorkspace = DEPOT_AND_WORKSPACE.matcher(line);
                    if (depotAndWorkspace.find()) {
                        // add the found depot path and the clientname-tweaked client path
                        parsed.add(depotAndWorkspace.group(1));
                        parsed.add("//" + p4Client + depotAndWorkspace.group(2));
                    } else {
                        Matcher depotAndWorkspaceQuoted = DEPOT_AND_WORKSPACE_QUOTED.matcher(line);
                        if (depotAndWorkspaceQuoted.find()) {
                           // add the found depot path and the clientname-tweaked client path
                            parsed.add("\"" + depotAndWorkspaceQuoted.group(1) + "\"");
                            parsed.add("\"//" + p4Client + depotAndWorkspaceQuoted.group(2) + "\"");
                        } else {
                            Matcher depotAndQuotedWorkspace = DEPOT_AND_QUOTED_WORKSPACE.matcher(line);
                            if (depotAndQuotedWorkspace.find()) {
                                // add the found depot path and the clientname-tweaked client path
                                parsed.add(depotAndQuotedWorkspace.group(1));
                                parsed.add("\"//" + p4Client + depotAndQuotedWorkspace.group(2) + "\"");
                            } else {
                                Matcher quotedDepotAndWorkspace = QUOTED_DEPOT_AND_WORKSPACE.matcher(line);
                                if (quotedDepotAndWorkspace.find()) {
                                    parsed.add("\"" + quotedDepotAndWorkspace.group(1) + "\"");
                                    parsed.add("//" + p4Client + quotedDepotAndWorkspace.group(2));
                                } else {
                                    // Assume anything else is a comment and ignore it
                                    if (line.trim().length() > 0 && !line.startsWith("#")) {
                                        // Throw a warning only if the line is not blank and not '#' prefixed.
                                        log.println("Warning: Client Spec line invalid, ignoring. ("+line+")");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return parsed;
    }

    /**
     * Compares a parsed project path pair list against a list of view
     * mapping lines from a client spec.
     */
     static boolean equalsProjectPath(List<String> pairs, List<String> lines) {
        Iterator<String> pi = pairs.iterator();
        for (String line : lines) {
            if (!pi.hasNext())
                return false;
            String p1 = pi.next();
            String p2 = pi.next();  // assuming an even number of pair items
            if (!line.trim().equals(p1.trim() + " " + p2.trim()))
                return false;
        }
        return !pi.hasNext(); // equals iff there are no more pairs
    }

    /**
     * @return the path to the ClientSpec
     */
    public String getClientSpec() {
        return clientSpec;
    }

    /**
     * @param clientSpec the path to the ClientSpec
     */
    public void setClientSpec(String clientSpec) {
        this.clientSpec = clientSpec;
    }

    /**
     * @return True if we are using a ClientSpec file to setup the workspace view
     */
    public boolean isUseClientSpec() {
        return useClientSpec;
    }

    /**
     * @param useClientSpec True if a ClientSpec file should be used to setup workspace view, False otherwise
     */
    public void setUseClientSpec(boolean useClientSpec) {
        this.useClientSpec = useClientSpec;
    }

    /**
     * Check if we are using a stream depot type or a classic depot type.
     *
     * @return True if we are using a stream depot type, False otherwise
     */
    public boolean isUseStreamDepot() {
        return useStreamDepot;
    }

    /**
     * Control the usage of stream depot.
     *
     * @param useStreamDepot True if stream depot is used, False otherwise
     */
    public void setUseStreamDepot(boolean useStreamDepot) {
        this.useStreamDepot = useStreamDepot;
    }

    /**
     * Get the stream name.
     *
     * @return the p4Stream
     */
    public String getP4Stream() {
        return p4Stream;
    }

    /**
     * Set the stream name.
     *
     * @param stream the stream name
     */
    public void setP4Stream(String stream) {
        p4Stream = stream;
    }

    /**
     * @return the projectPath
     */
    public String getProjectPath() {
        return projectPath;
    }

    /**
     * @param projectPath the projectPath to set
     */
    public void setProjectPath(String projectPath) {
        // Make it backwards compatible with the old way of specifying a label
        Matcher m = Pattern.compile("(@\\S+)\\s*").matcher(projectPath);
        if (m.find()) {
            p4Label = m.group(1);
            projectPath = projectPath.substring(0,m.start(1))
                + projectPath.substring(m.end(1));
        }
        this.projectPath = projectPath;
    }

    /**
     * @deprecated use {@link #getEffectiveP4User()} to get a support of default options
     * @return the p4User
     */
    public @CheckForNull String getP4User() {
        return p4User;
    }

    /**
     * @param user the p4User to set
     */
    public void setP4User(@CheckForNull String user) {
        p4User = user;
    }

    /**
     * @return the p4Passwd
     */
    public String getP4Passwd() {
        return p4Passwd;
    }

    public String getDecryptedP4Passwd() {
        PerforcePasswordEncryptor encryptor = new PerforcePasswordEncryptor();
        return encryptor.decryptString(getEffectiveP4Password());
    }

    public String getDecryptedP4Passwd(AbstractBuild build)
            throws ParameterSubstitutionException, InterruptedException {
        return MacroStringHelper.substituteParameters(getDecryptedP4Passwd(), this, build, null);
    }

    /**
     * @deprecated Use {@link #getDecryptedP4Passwd(hudson.model.AbstractProject, hudson.model.Node)} instead.
     */
    public String getDecryptedP4Passwd(AbstractProject project) throws InterruptedException {
        try {
            return getDecryptedP4Passwd(project, null);
        } catch (ParameterSubstitutionException ex) {
            throw new IllegalArgumentException("Cannot substitute all variables in P4Passwd");
        }
    }

    public String getDecryptedP4Passwd(@CheckForNull AbstractProject project, @CheckForNull Node node)
            throws ParameterSubstitutionException, InterruptedException {
        return MacroStringHelper.substituteParameters(getDecryptedP4Passwd(),
                this, project, node, null);
    }

    /**
     * @param passwd the p4Passwd to set
     */
    public void setP4Passwd(String passwd) {
        PerforcePasswordEncryptor encryptor = new PerforcePasswordEncryptor();
        if (encryptor.appearsToBeAnEncryptedPassword(passwd))
            p4Passwd = passwd;
        else
            p4Passwd = encryptor.encryptString(passwd);
    }

    /**
     * @return the p4Port
     */
    public String getP4Port() {
        return p4Port;
    }

    /**
     * @param port the p4Port to set
     */
    public void setP4Port(String port) {
        p4Port = port;
    }

    /**
     * @return the p4Client
     */
    public String getP4Client() {
        return p4Client;
    }

    /**
     * @param client the p4Client to set
     */
    public void setP4Client(String client) {
        p4Client = client;
    }

    /**
     * @return the p4SysDrive
     */
    public String getP4SysDrive() {
        return p4SysDrive;
    }

    /**
     * @param sysDrive the p4SysDrive to set
     */
    public void setP4SysDrive(String sysDrive) {
        p4SysDrive = sysDrive;
    }

    /**
     * @return the p4SysRoot
     */
    public String getP4SysRoot() {
        return p4SysRoot;
    }

    /**
     * @param sysRoot the p4SysRoot to set
     */
    public void setP4SysRoot(String sysRoot) {
        p4SysRoot = sysRoot;
    }

    /**
     * @deprecated Replaced by {@link #getP4Tool()}
     */
    public String getP4Exe() {
        return p4Exe;
    }

    /**
     * @deprecated Replaced by {@link #setP4Tool(String)}
     */
    public void setP4Exe(String exe) {
        p4Exe = exe;
    }

    /**
     * @return the p4Tool
     */
    public String getP4Tool() {
        return p4Tool;
    }

    /**
     * @param tool the p4 tool installation to set
     */
    public void setP4Tool(String tool) {
        p4Tool = tool;
    }

    /**
     * @return the p4Label
     */
    public String getP4Label() {
        return p4Label;
    }

    /**
     * @param label the p4Label to set
     */
    public void setP4Label(String label) {
        p4Label = label;
    }

    /**
     * @return the p4Counter
     */
    public String getP4Counter() {
        return p4Counter;
    }

    /**
     * @param counter the p4Counter to set
     */
    public void setP4Counter(String counter) {
        p4Counter = counter;
    }

    /**
     * @return the p4UpstreamProject
     */
    public String getP4UpstreamProject() {
        return p4UpstreamProject;
    }

    /**
     * @param project the p4UpstreamProject to set
     */
    public void setP4UpstreamProject(String project) {
        p4UpstreamProject = project;
    }

    /**
     * @return True if the plugin should update the counter to the last change
     */
    public boolean isUpdateCounterValue() {
        return updateCounterValue;
    }

    /**
     * @param updateCounterValue True if the plugin should update the counter to the last change
     */
    public void setUpdateCounterValue(boolean updateCounterValue) {
        this.updateCounterValue = updateCounterValue;
    }

    /**
     * @return True if the P4PASSWD value must be set in the environment
     */
    public boolean isExposeP4Passwd() {
        return getInstance().isPasswordExposeDisabled() ? false : exposeP4Passwd;
    }

    /**
     * @param exposeP4Passwd True if the P4PASSWD value must be set in the environment
     */
    public void setExposeP4Passwd(boolean exposeP4Passwd) {
        this.exposeP4Passwd =  getInstance().isPasswordExposeDisabled() ? false : exposeP4Passwd;
    }

    /**
     * The current perforce option set for the view.
     * @return current perforce view options
     */
    public String getProjectOptions() {
        return projectOptions;
    }

    /**
     * Set the perforce options for view creation.
     * @param projectOptions the effective perforce options.
     */
    public void setProjectOptions(String projectOptions) {
        this.projectOptions = projectOptions;
    }

    /**
     * @param createWorkspace    True to let the plugin create the workspace, false to let the user manage it
     */
    public void setCreateWorkspace(boolean createWorkspace) {
        this.createWorkspace = Boolean.valueOf(createWorkspace);
    }

    /**
     * @return  True if the plugin manages the view, false if the user does.
     */
    public boolean isCreateWorkspace() {
        return createWorkspace.booleanValue();
    }

    /**
     * @param update    True to let the plugin manage the view, false to let the user manage it
     */
    public void setUpdateView(boolean update) {
        this.updateView = update;
    }

    /**
     * @return  True if the plugin manages the view, false if the user does.
     */
    public boolean isUpdateView() {
        return updateView;
    }

    /**
     * @return  True if we are performing a one-time force sync
     */
    public boolean isForceSync() {
        return forceSync;
    }

    /**
     * @return  True if we are performing a one-time force sync
     */
    public boolean isAlwaysForceSync() {
        return alwaysForceSync;
    }

    /**
     * @return True if auto sync is disabled
     */
    public boolean isDisableAutoSync() {
        return disableAutoSync;
    }

    /**
     * @return True if we are using the old style client names
     */
    public boolean isUseOldClientName() {
        return this.useOldClientName;
    }

    /**
     * @param force True to perform a one time force sync, false to perform normal sync
     */
    public void setForceSync(boolean force) {
        this.forceSync = force;
    }

    /**
     * @param force True to perform a one time force sync, false to perform normal sync
     */
    public void setAlwaysForceSync(boolean force) {
        this.alwaysForceSync = force;
    }

    /**
     * @param disable True to disable the pre-build sync, false to perform pre-build sync
     */
    public void setDisableAutoSync(boolean disable) {
        this.disableAutoSync = disable;
    }

    /**
     * @param use True to use the old style client names, false to use the new style
     */
    public void setUseOldClientName(boolean use) {
        this.useOldClientName = use;
    }

    /**
     * @return  True if we are using a label
     */
    public boolean isUseLabel() {
        return p4Label != null;
    }

    /**
     * @param dontRenameClient  False if the client will rename the client spec for each
     * slave
     */
    public void setDontRenameClient(boolean dontRenameClient) {
        this.dontRenameClient = dontRenameClient;
    }

    /**
     * @return  True if the client will rename the client spec for each slave
     */
    public boolean isDontRenameClient() {
        return dontRenameClient;
    }

    /**
     * @return True if the plugin is to delete the workpsace files before building.
     */
    public boolean isWipeBeforeBuild() {
        return wipeBeforeBuild;
    }

    /**
     * @return True if the plugin is to clean the workspace using any method before building.
     */
    public boolean isCleanWorkspaceBeforeBuild() {
        return wipeBeforeBuild || quickCleanBeforeBuild;
    }

    /**
     * @return True if the plugin is to delete the workpsace including the.repository files before building.
     */
    public boolean isWipeRepoBeforeBuild() {
        return wipeRepoBeforeBuild;
    }

    /**
     * @param clientFormat A string defining the format of the client name for agent workspaces.
     */
    public void setSlaveClientNameFormat(String clientFormat) {
        this.slaveClientNameFormat = clientFormat;
    }

    /**
     * @param wipeBeforeBuild True if the client is to delete the workspace files before building.
     */
    public void setWipeBeforeBuild(boolean wipeBeforeBuild) {
        this.wipeBeforeBuild = wipeBeforeBuild;
    }

    public void setQuickCleanBeforeBuild(boolean quickCleanBeforeBuild) {
        this.quickCleanBeforeBuild = quickCleanBeforeBuild;
    }

    public boolean isDontUpdateClient() {
        return dontUpdateClient;
    }

    public void setDontUpdateClient(boolean dontUpdateClient) {
        this.dontUpdateClient = dontUpdateClient;
    }

    public boolean isUseViewMaskForPolling() {
        return useViewMaskForPolling;
    }

    public void setUseViewMaskForPolling(boolean useViewMaskForPolling) {
        this.useViewMaskForPolling = useViewMaskForPolling;
    }

    public boolean isUseViewMaskForSyncing() {
        return useViewMaskForSyncing;
    }

    public void setUseViewMaskForSyncing(boolean useViewMaskForSyncing) {
        this.useViewMaskForSyncing = useViewMaskForSyncing;
    }

    public boolean isUseViewMaskForChangeLog() {
        return useViewMaskForChangeLog;
    }

    public void setUseViewMaskForChangeLog(boolean useViewMaskForChangeLog) {
        this.useViewMaskForChangeLog = useViewMaskForChangeLog;
    }

    public String getViewMask() {
        return viewMask;
    }

    public void setViewMask(String viewMask) {
        this.viewMask = viewMask;
    }

    public boolean isUseViewMask() {
        return useViewMask;
    }

    public void setUseViewMask(boolean useViewMask) {
        this.useViewMask = useViewMask;
    }

    public String getP4Charset() {
        return p4Charset;
    }

    public void setP4Charset(String p4Charset) {
        this.p4Charset = p4Charset;
    }

    public String getP4CommandCharset() {
        return p4CommandCharset;
    }

    public void setP4CommandCharset(String p4CommandCharset) {
        this.p4CommandCharset = p4CommandCharset;
    }

    public String getLineEndValue() {
        return lineEndValue;
    }

    public void setLineEndValue(String lineEndValue) {
        this.lineEndValue = lineEndValue;
    }

    public boolean isShowIntegChanges() {
        return showIntegChanges;
    }

    public void setShowIntegChanges(boolean showIntegChanges) {
        this.showIntegChanges = showIntegChanges;
    }

    public boolean isDisableChangeLogOnly() {
        return disableChangeLogOnly;
    }

    public void setDisableChangeLogOnly(boolean disableChangeLogOnly) {
        this.disableChangeLogOnly = disableChangeLogOnly;
    }

    public boolean isDisableSyncOnly() {
        return disableSyncOnly;
    }

    public void setDisableSyncOnly(boolean disableSyncOnly) {
        this.disableSyncOnly = disableSyncOnly;
    }

    public String getExcludedUsers() {
        return excludedUsers;
    }

    public void setExcludedUsers(String users) {
        excludedUsers = users;
    }

    public String getExcludedFiles() {
        return excludedFiles;
    }

    public void setExcludedFiles(String files) {
        excludedFiles = files;
    }

    public boolean isPollOnlyOnMaster() {
        return pollOnlyOnMaster;
    }

    public void setPollOnlyOnMaster(boolean pollOnlyOnMaster) {
        this.pollOnlyOnMaster = pollOnlyOnMaster;
    }

    public boolean isDontUpdateServer() {
        return dontUpdateServer;
    }

    public void setDontUpdateServer(boolean dontUpdateServer) {
        this.dontUpdateServer = dontUpdateServer;
    }

    public boolean getExcludedFilesCaseSensitivity() {
        return excludedFilesCaseSensitivity;
    }

    public void setExcludedFilesCaseSensitivity(boolean excludedFilesCaseSensitivity) {
        this.excludedFilesCaseSensitivity = excludedFilesCaseSensitivity;
    }

    public void setWipeRepoBeforeBuild(boolean wipeRepoBeforeBuild) {
        this.wipeRepoBeforeBuild = wipeRepoBeforeBuild;
    }

    public boolean isQuickCleanBeforeBuild() {
        return quickCleanBeforeBuild;
    }

    public boolean isRestoreChangedDeletedFiles() {
        return restoreChangedDeletedFiles;
    }

    public void setRestoreChangedDeletedFiles(boolean restoreChangedDeletedFiles) {
        this.restoreChangedDeletedFiles = restoreChangedDeletedFiles;
    }

    public List<String> getAllLineEndChoices() {
        List<String> allChoices = ((PerforceSCMDescriptor)this.getDescriptor()).getAllLineEndChoices();
        ArrayList<String> choices = new ArrayList<String>();
        // Order choices so that the current one is first in the list
        // This is required in order for tests to work, unfortunately
        choices.add(lineEndValue);
        for (String choice : allChoices) {
            if (!choice.equals(lineEndValue)) {
                choices.add(choice);
            }
        }
        return choices;
    }

    /**
     * This is only for the config screen.  Also, it returns a string and not an int.
     * This is because we want to show an empty value in the config option if it is not being
     * used.  The default value of -1 is not exactly empty.  So if we are set to default of
     * -1, we return an empty string.  Anything else and we return the actual change number.
     *
     * @return  The one time use variable, firstChange.
     */
    public String getFirstChange() {
        if (firstChange <= 0)
            return "";
        return String.valueOf(firstChange);
    }

    /**
     * This is only for the config screen.  Also, it returns a string and not an int.
     * This is because we want to show an empty value in the config option if it is not being
     * used.  The default value of -1 is not exactly empty.  So if we are set to default of
     * -1, we return an empty string.  Anything else and we return the actual change number.
     *
     * @return  fileLimit
     */
    public String getFileLimit() {
        if (fileLimit <= 0)
            return "";
        return String.valueOf(fileLimit);
    }

    public void setFileLimit(int fileLimit) {
        this.fileLimit = fileLimit;
    }

    /**
     * With Perforce the server keeps track of files in the workspace.  We never
     * want files deleted without the knowledge of the server so we disable the
     * cleanup process.
     *
     * @param project
     *      The project that owns this {@link SCM}. This is always the same
     *      object for a particular instanceof {@link SCM}. Just passed in here
     *      so that {@link SCM} itself doesn't have to remember the value.
     * @param workspace
     *      The workspace which is about to be deleted. Never null. This can be
     *      a remote file path.
     * @param node
     *      The node that hosts the workspace. SCM can use this information to
     *      determine the course of action.
     *
     * @return
     *      true if {@link SCM} is OK to let Hudson proceed with deleting the
     *      workspace.
     *      False to veto the workspace deletion.
     */
    @Override
    public boolean processWorkspaceBeforeDeletion(AbstractProject<?,?> project, FilePath workspace, Node node) {
        Logger perforceLogger = Logger.getLogger(PerforceSCM.class.getName());
        perforceLogger.info(
            "Workspace '"+workspace.getRemote()+"' is being deleted; flushing workspace to revision 0.");
        TaskListener loglistener = new LogTaskListener(perforceLogger,Level.INFO);
        PrintStream log = loglistener.getLogger();
        TaskListener listener = new StreamTaskListener(log);
        Launcher launcher = node.createLauncher(listener);

        try {
            Depot depot = getDepot(launcher, workspace, project, null, node);
            final String effectiveProjectPath = MacroStringHelper.substituteParameters(
                    projectPath, this, project, node, null);
            Workspace p4workspace = getPerforceWorkspace(
                project,
                effectiveProjectPath,
                depot,
                node,
                null,
                null,
                workspace,
                listener,
                true,
                false);
            flushWorkspaceTo0(depot, p4workspace, log);
        } catch (Exception ex) {
            Logger.getLogger(PerforceSCM.class.getName()).log(Level.SEVERE, null, ex);
            return false;
        }
        return true;
    }

    @Override public boolean requiresWorkspaceForPolling() {
        // nodes are allocated and used in the pollChanges() function if available,
        // so we'll just tell jenkins to provide the master's launcher.
        return false;
    }

    public boolean isSlaveClientNameStatic() throws  ParameterSubstitutionException {
        Map<String,String> testSub1 = new Hashtable<String,String>();
        testSub1.put("hostname", "HOSTNAME1");
        testSub1.put("nodename", "NODENAME1");
        testSub1.put("hash", "HASH1");
        testSub1.put("basename", this.p4Client);
        String result1 = MacroStringHelper.substituteParameters(getSlaveClientNameFormat(), testSub1);

        Map<String,String> testSub2 = new Hashtable<String,String>();
        testSub2.put("hostname", "HOSTNAME2");
        testSub2.put("nodename", "NODENAME2");
        testSub2.put("hash", "HASH2");
        testSub2.put("basename", this.p4Client);
        String result2 = MacroStringHelper.substituteParameters(getSlaveClientNameFormat(), testSub2);

        return result1.equals(result2);
    }

    @Override
    public boolean supportsPolling() {
        return true;
    }

}
