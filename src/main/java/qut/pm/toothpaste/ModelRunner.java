package qut.pm.toothpaste;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.deckfour.xes.classification.XEventClassifier;
import org.deckfour.xes.classification.XEventNameClassifier;
import org.deckfour.xes.info.XLogInfoFactory;
import org.deckfour.xes.model.XLog;
import org.processmining.contexts.uitopia.UIPluginContext;
import org.processmining.framework.plugin.PluginContext;
import org.processmining.models.connections.GraphLayoutConnection;
import org.processmining.models.graphbased.directed.petrinet.StochasticNet;
import org.processmining.models.graphbased.directed.petrinet.StochasticNet.ExecutionPolicy;
import org.processmining.models.graphbased.directed.petrinet.StochasticNet.TimeUnit;
import org.processmining.models.semantics.petrinet.Marking;
import org.processmining.plugins.pnml.exporting.StochasticNetToPNMLConverter;
import org.processmining.plugins.pnml.importing.StochasticNetDeserializer;
import org.processmining.plugins.pnml.simple.PNMLRoot;
import org.processmining.xeslite.plugin.OpenLogFileLiteImplPlugin;
import org.simpleframework.xml.Serializer;
import org.simpleframework.xml.core.Persister;

import qut.pm.prom.helpers.ConsoleUIPluginContext;
import qut.pm.prom.helpers.HeadlessDefinitelyNotUIPluginContext;
import qut.pm.prom.helpers.HeadlessUIPluginContext;
import qut.pm.prom.helpers.PetrinetExportUtils;
import qut.pm.spm.AcceptingStochasticNet;
import qut.pm.spm.AcceptingStochasticNetImpl;
import qut.pm.spm.Measure;
import qut.pm.spm.PetrinetSource;
import qut.pm.spm.RunState;
import qut.pm.spm.RunStats;
import qut.pm.spm.SPNQualityCalculator;
import qut.pm.spm.StochasticNetLogMiner;
import qut.pm.spm.TaskStats;
import qut.pm.xes.helpers.XESLogUtils;


/**
 * Main class for model runner. For stochastic petri net discovery with the Rogge-Solti techniques, 
 * lpsolve55 will need to be on the @{code java.library.path} or the system library path. It is included
 * bundled in {@code ldlib/lp_solve_5.5_java} in the enclosing project.
 * 
 * @author burkeat
 *
 */

public class ModelRunner {


	private static enum Classifier{
		DEFAULT_NAME,
		NAME;
	}

	private static final String XES_SUFFIX = ".xes";
	private static final String PNML = "pnml";
	
	private static final String CONFIG_DATA_FILES 	= "mr.data.files";
	private static final String CONFIG_DATA_DIR 	= "mr.data.dir";
	private static final String CONFIG_OUTPUT_DIR 	= "mr.output.dir";
	private static final String CONFIG_EXPORT_DOT 	= "mr.export.dot";
	private static final String CONFIG_KFOLD_LOGS	= "mr.kfold.logs";
	private static final String CONFIG_KFOLD_LOGS_START 
												    = "mr.kfold.logs.start";
	private static final String CONFIG_MINERS 		= "mr.miners";
	private static final String CONFIG_MODEL_DIR 	= "mr.model.dir";
	private static final String CONFIG_MODELS 		= "mr.model.files";
	private static final String CONFIG_CALCULATORS	= "mr.calculators";
	private static final String CONFIG_CLASSIFIER 	= "mr.classifier";

	private static final Logger LOGGER = LogManager.getLogger();
	private static final XEventClassifier NAME_CLASSIFIER = new XEventNameClassifier();
	
	private String dataDir = "";
	private String outputDir = "";
	private String[] dataFiles = new String[0];
	private boolean exportDOT = false;
	private int kfoldLogs = 1;
	private int kfoldLogsStart = 1;
	// private String modelInputFileName = "";
	private List<StochasticNetLogMiner> miners;
	private List<PetrinetSource> models;
	private List<SPNQualityCalculator> calculators;
	private boolean exportRun = true;
	private Classifier classifier = Classifier.NAME;


	private void configure() throws Exception{
		Properties cfg = new Properties();
		cfg.load(new FileInputStream( "config/instance.properties" ));
		dataDir = cfg.getProperty(CONFIG_DATA_DIR,"data");
		outputDir = cfg.getProperty(CONFIG_OUTPUT_DIR,"var");
		String dataFilesStr = cfg.getProperty(CONFIG_DATA_FILES,"");
		if (dataFilesStr.isEmpty()) {
			dataFiles = new File(dataDir).list();
		}else {
			dataFiles = dataFilesStr.split(",");			
		}
		exportDOT = Boolean.valueOf( cfg.getProperty(CONFIG_EXPORT_DOT, "false") );
		kfoldLogs = Integer.valueOf( cfg.getProperty(CONFIG_KFOLD_LOGS, "1") );
		kfoldLogsStart = Integer.valueOf( cfg.getProperty(CONFIG_KFOLD_LOGS_START, "1") );
		LOGGER.info("Using data location {}" , dataDir);
		LOGGER.info("Using data files {}" , Arrays.deepToString(dataFiles));
		String modelConfig = cfg.getProperty(CONFIG_MODELS, "");
		String minerConfig = cfg.getProperty(CONFIG_MINERS, "");
		if (!modelConfig.trim().isEmpty() && !minerConfig.trim().isEmpty()) {
			throw new Exception("Can't have both miners and models configured " + modelConfig 
					+ " " + minerConfig);
		}
		configureMiners(cfg);
		configureModels(cfg);
		configureCalculators(cfg);
		configureClassifier(cfg);
	}

	private void configureClassifier(Properties cfg) {
		classifier = Classifier.valueOf( cfg.getProperty(CONFIG_CLASSIFIER, "NAME") );
		LOGGER.info("Using classifier {}", classifier);
	}

	private void configureModels(Properties cfg) throws Exception{
		models = new ArrayList<>();
		String modelConfig = cfg.getProperty(CONFIG_MODELS,"");
		if (modelConfig.isEmpty())
			return;
		LOGGER.info("Using models {}" , modelConfig);
		String modelPath = cfg.getProperty(CONFIG_MODEL_DIR,"var");
		String[] modelFiles = modelConfig.split(",");
		for (String modelFile: modelFiles) {
			String fname = modelPath + File.separator + modelFile.trim(); 
			LOGGER.info("Loading model from {}",fname);
			AcceptingStochasticNet net = loadNet(new File(fname)); 
			models.add( new PetrinetSource(net,net.getId()) );
		}
	}
	
	private AcceptingStochasticNet loadNet(File file) throws Exception {
		PluginContext context = new HeadlessDefinitelyNotUIPluginContext(new ConsoleUIPluginContext(),
				"modelrunner_loadnet");
		String shortName = file.getName().replace("osmodel_", "");
		shortName = shortName.substring(0, shortName.indexOf("_") );
		LOGGER.info("Short name {}",shortName);
		Serializer serializer = new Persister();
		PNMLRoot pnml = serializer.read(PNMLRoot.class, file);
		StochasticNetDeserializer converter = new StochasticNetDeserializer();
		Object[] objs = converter.convertToNet(context, pnml, file.getName(), true);
		AcceptingStochasticNet apn = new AcceptingStochasticNetImpl(shortName, (StochasticNet) objs[0],
				(Marking) objs[1], Collections.singleton((Marking) objs[2]));
		return apn;
	}
	

	private void configureMiners(Properties cfg) {
		miners = new ArrayList<>();
		String minerConfig = cfg.getProperty(CONFIG_MINERS,"");
		if (minerConfig.isEmpty())
			return;
		for (String minerName: minerConfig.split(",")) {
			try {
				Class<?> minerClass = loadClass(minerName, "qut.pm.spm.miner");
				addMiner((StochasticNetLogMiner) minerClass.getConstructor().newInstance());
			}catch (Exception e) {
				LOGGER.error("Couldn't load miner class {} ",minerName);
			}
		}
	}

	private Class<?> loadClass(String className, String defaultPackage) 
			throws ClassNotFoundException 
	{
		Class<?> targetClass = null;
		if (className.contains(".")) {
			targetClass = Class.forName(className);
		}else {
			targetClass = Class.forName(defaultPackage + "." + className);
		}
		return targetClass;
	}
	
	private void configureCalculators(Properties cfg) {
		calculators = new ArrayList<>();
		String calculatorConfig = cfg.getProperty(CONFIG_CALCULATORS,"");
		for (String calcName: calculatorConfig.split(",")) {
			try {
				Class<?> calcClass = loadClass(calcName, "qut.pm.spm.conformance");
				calculators.add((SPNQualityCalculator) calcClass.getConstructor().newInstance());
			}catch (Exception e) {
				LOGGER.error("Couldn't load calculator class {} ",calcName);
			}			
		}
	}
	
	private void addMiner(StochasticNetLogMiner miner) {
		// This is inefficient but there aren't many miners
		for (StochasticNetLogMiner existingMiner: miners) {
			if (existingMiner.getShortID() == miner.getShortID())
				throw new RuntimeException("New miner " + miner.getClass() + 
						" matches existing miner short id for " + existingMiner.getClass());
		}
		miners.add(miner);
	}
			
	private XEventClassifier classifierFor(XLog log) {
		switch (this.classifier) {
		case DEFAULT_NAME:
			return XESLogUtils.detectNameBasedClassifier(log);
		case NAME:
			return NAME_CLASSIFIER;
		default:
			return NAME_CLASSIFIER;
		}
	}
	
	private void runSingleMiner(StochasticNetLogMiner miner, String inputLogName)
			throws Exception
	{
		if (kfoldLogs == 1) {
			runSingleMinerRun(miner, inputLogName, inputLogName);
		}else {
			for (int i=kfoldLogsStart; i<kfoldLogs+1; i++) {
				String df = logPrefix(inputLogName);
				runSingleMinerRun( miner, df + "_k"  + i + XES_SUFFIX, 
										  df + "_nk" + i + XES_SUFFIX);
			}
		}
	}

	private void runSingleMinerRun(StochasticNetLogMiner miner, String inputLogName, 
								   String comparisonLogName) 
										   throws Exception 
	{
		UIPluginContext uipc = 
				new HeadlessUIPluginContext(new ConsoleUIPluginContext(), "tp_runnerlogparser");	
		final String SEP = " -- ";
		noteRunStart(miner, inputLogName, SEP);
		String inputLogPrefix = logPrefix(inputLogName);
		String outputModelName = nameFile(inputLogPrefix,miner.getShortID(),"osmodel",PNML);
		RunStats runStats = new RunStats(inputLogName,outputModelName, miner.getReadableID());
		TaskStats stats = new TaskStats("spmlogparser");
		stats.markRunning();
		try {
			XLog log = loadLog(uipc, inputLogName, stats);
			uipc = new HeadlessUIPluginContext(new ConsoleUIPluginContext(), "tp_runnermining");
			stats = closeTaskAndMakeNew(runStats, stats, "miner");
			LOGGER.debug(stats.formatStats()); 
			File modelFile = new File(outputModelName);
			stats.markRunning();
			if (miner.isFilesytemLogReader()) {
				miner.setLogFile(dataDir + File.separator + inputLogName);
			}
			miner.run(uipc,log, classifierFor(log), modelFile);
			closeTask(runStats,stats);
			storeModel(modelFile,miner.getStochasticNetDescriptor());
			stats = makeNewTask("visualize");
			exportVisualization(miner,inputLogPrefix);
			stats.markEnd();
			uipc = new HeadlessUIPluginContext(new ConsoleUIPluginContext(), "tp_runnerpostrun");	
			if (miner.isStochasticNetProducer()) {
				TaskStats compStats = new TaskStats("complog"); 
				// note these log count stats in compStats currently unused
				XLog compLog = loadLog(uipc, comparisonLogName, compStats);
				calculatePostStats(uipc,inputLogPrefix,miner,compLog,runStats);
				runStats.markEnd();
			}
		} catch (Exception e) {
			stats.markFailed(e.getMessage());
			runStats.markFailed(e.getMessage());
			LOGGER.error("Run failed for {}", miner.getReadableID(), e);
		}
		runStats.addTask(stats);
		noteRunEnd(miner, inputLogPrefix, outputModelName, runStats);
	}


	
	private XLog loadLog(UIPluginContext uipc, String inputLogName, TaskStats stats) throws Exception {
		String inputLogFileName = dataDir + File.separator + inputLogName;
		XLog log = (XLog) new OpenLogFileLiteImplPlugin().importFile(uipc, inputLogFileName);
		initLogInfo(log);
		stats.setMeasure(Measure.LOG_TRACE_COUNT, log.size());
		stats.setMeasure(Measure.LOG_EVENT_COUNT,
				log.getInfo(classifierFor(log)).getNumberOfEvents());
		return log;
	}

	private void initLogInfo(XLog log) {
		XEventClassifier classifier = classifierFor(log);
		XLogInfoFactory.createLogInfo(log, classifier);
	}


	private String logPrefix(String inputLogName) {
		return inputLogName.substring(0, inputLogName.lastIndexOf(XES_SUFFIX));
	}

	private void storeModel(File modelFile, AcceptingStochasticNet stochasticNetDescriptor)
		throws Exception
	{
		StochasticNet net = stochasticNetDescriptor.getNet(); 
		PNMLRoot root = new StochasticNetToPNMLConverter().convertNet(net,
				stochasticNetDescriptor.getInitialMarking(),
				new GraphLayoutConnection(net));
		net.setExecutionPolicy(ExecutionPolicy.RACE_ENABLING_MEMORY);
		net.setTimeUnit(TimeUnit.HOURS);
		Serializer serializer = new Persister();
		serializer.write(root, modelFile);
	}

	private String nameFile(String inputLogPrefix, String minerId, String outputPrefix, String outputSuffix) {
		return outputDir + File.separator + outputPrefix + "_" + minerId + "_" + inputLogPrefix + "." + outputSuffix;
	}


	private void exportVisualization(StochasticNetLogMiner miner, String inputLogPrefix) throws IOException
	{
		if (!exportDOT)
			return;
		StochasticNet net = miner.getStochasticNetDescriptor().getNet();
		String dot = PetrinetExportUtils.convertPetrinetToDOT(net);
		String outputVizName = nameFile(inputLogPrefix,miner.getShortID(), "oviz","dot");
        BufferedWriter writer = new BufferedWriter(new FileWriter(new File(outputVizName)));
        writer.write(dot);
        writer.flush();
        writer.close();
	}
	
	private void exportRun(String runShortID, String inputLogPrefix, RunStats runStats) 
			throws Exception
	{
		if (!exportRun )
			return;
		String outputDataName = nameFile(inputLogPrefix,runShortID, "mrun","xml");
		Serializer serializer = new Persister();
		File outputData = new File(outputDataName);
		serializer.write(runStats, outputData);
		LOGGER.info("exported run ({}) details to {}", runStats.getState(), outputDataName);
	}



	private TaskStats closeTaskAndMakeNew(RunStats runStats, TaskStats oldTaskStats, 
						String newTaskName) 
	{
		closeTask(runStats, oldTaskStats);
		return makeNewTask(newTaskName);
	}


	private TaskStats makeNewTask(String newTaskName) {
		TaskStats newTaskStats = new TaskStats(newTaskName);
		newTaskStats.markRunning();
		return newTaskStats;
	}


	private void closeTask(RunStats runStats, TaskStats oldTaskStats) {
		oldTaskStats.markEnd();
		runStats.addTask(oldTaskStats);
	}


	private void calculatePostStats(PluginContext context, String inputLogPrefix, 
			StochasticNetLogMiner miner, XLog log, RunStats runStats) 
				throws Exception
	{		
		TaskStats stats = null;
		for (SPNQualityCalculator calc: calculators) {
			stats = makeNewTask("calculate " + calc.getReadableId());
			calc.calculate(context, miner.getStochasticNetDescriptor(), log, 
					classifierFor(log), stats);
			closeTask(runStats,stats);
			// Conservatively export stats after every run
			LOGGER.info(runStats.formatStats());
			exportRun(miner.getShortID(), inputLogPrefix, runStats);
		}
	}


	private void noteRunStart(StochasticNetLogMiner miner, String inputLogName, final String SEP) {
		String traceHeader = "Beginning run" + SEP + miner.getReadableID() + SEP 
				+ inputLogName ;
		LOGGER.info(traceHeader);
	}


	private void noteRunEnd(StochasticNetLogMiner artifactRun, String inputLogPrefix, String outputModelName,
			RunStats stats) throws Exception
	{
		stats.markEnd();
		exportRun(artifactRun.getShortID(), inputLogPrefix, stats);
		LOGGER.info("Run completed for {} -- {} -- {} ", 
				artifactRun.getReadableID(), inputLogPrefix, outputModelName );
		LOGGER.info(stats.formatStats());
		if (RunState.FAILED.equals(stats.getState())){
			throw new Exception("Failed run, giving up");
		}
	}
	
	private TaskStats makeNewTask(RunStats runStats, String newTaskName) {
		TaskStats newTaskStats = new TaskStats(newTaskName);
		newTaskStats.markRunning();
		runStats.addTask(newTaskStats);
		return newTaskStats;
	}

	
	private void calculatePostStats(PluginContext context, String inputLogPrefix, 
			PetrinetSource model, XLog log, RunStats runStats) 
				throws Exception
	{		
		TaskStats stats = null;
		for (SPNQualityCalculator calc: calculators) {
			stats = makeNewTask("calculate " + calc.getReadableId());
			calc.calculate(context, model.getAcceptingPetriNet(), log, 
					classifierFor(log), stats);
			closeTask(runStats,stats);
			// Conservatively export stats after every run
			LOGGER.info(runStats.formatStats());
			exportRun(model.getSourceId(), inputLogPrefix, runStats);
		}
	}

	
	public void runPredefinedModelVsLog(PetrinetSource model, String inputLogName ) throws Exception{
		UIPluginContext uipc = 
				new HeadlessUIPluginContext(new ConsoleUIPluginContext(), "tp_runnermvl");	
		RunStats runStats = new RunStats(inputLogName,model.getNet().getLabel(), model.getSourceId());
		String df = logPrefix(inputLogName);
		// RunStats runStats = initPredefRunStats(model, "predef");
		TaskStats stats = makeNewTask(runStats, "tpmlogparser");
		try {
			XLog log = loadLog(uipc, inputLogName, stats);
			calculatePostStats(uipc, df, model, log, runStats);
		} catch (Exception e) {
			exportRun(model.getSourceId(), df, runStats);
			throw new RuntimeException(e);
		}
	}
	

	
	public void runPredefinedModelCollectionVsLog() throws Exception{
		for (String df: dataFiles) {
			for (PetrinetSource model : models) {
				runPredefinedModelVsLog(model,df);
			}
		}
	}	

	public void runAll() throws Exception{
		for (String df: dataFiles) {
			// Basic sanity checks happen already at config load time
			if (!miners.isEmpty()) {
				runAllMiners(df);
			}
			if (miners.isEmpty() && !models.isEmpty()) {
				runPredefinedModelCollectionVsLog();
			}
		}
	}


	private void runAllMiners(String df) throws Exception {
		for (StochasticNetLogMiner miner: miners) {
			runSingleMiner( miner, df);
		}
	}

	public static void main(String[] args) throws Exception {
		LOGGER.info("SPM model runner initializing");
		LOGGER.debug( "java.library.path=" + System.getProperty("java.library.path") );

		ModelRunner modelRunner = new ModelRunner();
		modelRunner.configure();
		modelRunner.runAll();
			
		LOGGER.info("SPM model runner finished");
		System.exit(0);
	}


}
