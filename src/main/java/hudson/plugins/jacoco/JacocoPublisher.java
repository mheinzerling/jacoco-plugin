package hudson.plugins.jacoco;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.plugins.jacoco.model.ModuleInfo;
import hudson.plugins.jacoco.report.CoverageReport;
import hudson.plugins.jacoco.report.ReportFactory;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Publisher;
import hudson.tasks.Recorder;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.logging.Logger;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;


/**
 * {@link Publisher} that captures jacoco coverage reports.
 *
 * @author Kohsuke Kawaguchi
 */
public class JacocoPublisher extends Recorder {
    /**
     * Relative path to the jacoco XML file inside the workspace.
     */
    public String includes;
    
    /**
     * Contains the information of classDir, srcDir, execFile for every module.
     */
    private final ArrayList<ConfigRow> configRows;
	
    /**
     * Rule to be enforced. Can be null.
     *
     * TODO: define a configuration mechanism.
     */
    public Rule rule;

    /**
     * {@link hudson.model.HealthReport} thresholds to apply.
     */
    public JacocoHealthReportThresholds healthReports = new JacocoHealthReportThresholds();
    
    private int moduleNum;
    
    @DataBoundConstructor
    public JacocoPublisher(ArrayList<ConfigRow> configRows) {
    	this.configRows = configRows != null ? new ArrayList<ConfigRow>(configRows) : new ArrayList<ConfigRow>();
	}

    public ArrayList<ConfigRow> getConfigRows() {
		return configRows;
	}
    
	
	protected static void saveCoverageReports(FilePath folder, FilePath sourceFolder) throws IOException, InterruptedException {
		folder.mkdirs();
		sourceFolder.copyRecursiveTo(folder);
	}
	
	
	@Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws InterruptedException, IOException {
	
		final PrintStream logger = listener.getLogger();
		logger.println("[JaCoCo plugin] Collecting JaCoCo coverage data...");
		
		
		EnvVars env = build.getEnvironment(listener);
        env.overrideAll(build.getBuildVariables());
        
       /* try {
			ReportFactory reportFactory = new ReportFactory(new File(build.getWorkspace().getRemote()), listener); // FIXME probably doesn't work with jenkins remote build slaves
			reportFactory.createReport();
			logger.println("ReportFactory lunched!");
			
		} catch (IOException e) {
			logger.println("ReportFactory failed! WorkspaceDir: "+ build.getWorkspace().getRemote()+ e.getMessage());
		}*/
        
        ArrayList<ModuleInfo> reports = new ArrayList<ModuleInfo>(configRows.size());
        
        if (0 == configRows.size()) {
            if(build.getResult().isWorseThan(Result.UNSTABLE))
                return true;
            
            logger.println("[JaCoCo plugin] ERROR: Missing configuration!");
            build.setResult(Result.FAILURE);
            return true;
        } else {
        	logger.println("[JaCoCo plugin] Saving "+ configRows.size()+ " module information.");
        	for (ConfigRow row: configRows) {
        		logger.println("[JaCoCo plugin] " + row + " locations are configured");
        	}
        }
        
        
        FilePath actualBuildDirRoot = new FilePath(getJacocoReport(build));
        for (int i=0;i<configRows.size();++i) {
        	ModuleInfo moduleInfo = new ModuleInfo();
        	moduleInfo.setName("module"+i);
        	FilePath actualBuildModuleDir = new FilePath(actualBuildDirRoot, "module" + i);
	        FilePath actualDestination = new FilePath(actualBuildModuleDir, "classes");
	        moduleInfo.setClassDir(actualDestination);
	        saveCoverageReports(actualDestination, new FilePath(new File(build.getWorkspace().getRemote(), configRows.get(i).getClassDir())));

	        actualDestination = new FilePath(actualBuildModuleDir, "src");
	        moduleInfo.setSrcDir(actualDestination);
	        saveCoverageReports(actualDestination, new FilePath(new File(build.getWorkspace().getRemote(), configRows.get(i).getSrcDir())));
	       
	        
	        FilePath execfile = new FilePath(new File(build.getWorkspace().getRemote(), configRows.get(i).getExecFile()));
	        FilePath seged = actualBuildModuleDir.child("jacoco.exec");
	        moduleInfo.setExecFile(seged);
	        execfile.copyTo(seged);
	        
	        actualDestination = new FilePath(actualBuildModuleDir, "jenkins-jacoco");
	        saveCoverageReports(actualDestination, new FilePath(new File(build.getWorkspace().getRemote(), "\\target\\jenkins-jacoco")));
	        moduleInfo.setGeneratedHTMLsDir(actualDestination);
	        reports.add(moduleInfo);
        }
        
        final JacocoBuildAction action = JacocoBuildAction.load(build, rule, healthReports, listener, reports);
        action.setReports(reports);
        //logger.println("JaCoCo: " + action.getBuildHealth().getDescription());

        build.getActions().add(action);

        final CoverageReport result = action.getResult();
        if (result == null) {
            logger.println("JaCoCo: Could not parse coverage results. Setting Build to failure.");
            build.setResult(Result.FAILURE);
        }
        /*} else if (result.isFailed()) {
            logger.println("JaCoCo: code coverage enforcement failed. Setting Build to unstable.");
            build.setResult(Result.UNSTABLE);
        }*/
      //  logger.log(Level.WARNING, "ReportFactory failed!");
        return true;
    }

    @Override
    public Action getProjectAction(AbstractProject<?, ?> project) {
        return new JacocoProjectAction(project);
    }

	@Override
    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.BUILD;
    }

    /**
     * Gets the directory to store report files
     */
    static File getJacocoReport(AbstractBuild<?,?> build) {
        return new File(build.getRootDir(), "jacoco");
    }

    @Override
    public BuildStepDescriptor<Publisher> getDescriptor() {
        return DESCRIPTOR;
    }

    @Extension
    public static final BuildStepDescriptor<Publisher> DESCRIPTOR = new DescriptorImpl();

    public static class DescriptorImpl extends BuildStepDescriptor<Publisher> {
        public DescriptorImpl() {
            super(JacocoPublisher.class);
        }

		@Override
        public String getDisplayName() {
            return Messages.JacocoPublisher_DisplayName();
        }

		@Override
        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

    }
    private static final Logger logger = Logger.getLogger(JacocoPublisher.class.getName());
}
