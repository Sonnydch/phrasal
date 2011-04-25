package edu.stanford.nlp.mt.parser;

import java.io.IOException;
import java.io.Serializable;
import java.util.*;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import edu.stanford.nlp.classify.Dataset;
import edu.stanford.nlp.classify.GeneralDataset;
import edu.stanford.nlp.classify.LinearClassifier;
import edu.stanford.nlp.classify.LinearClassifierFactory;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.ling.BasicDatum;
import edu.stanford.nlp.ling.Datum;
import edu.stanford.nlp.ling.HasWord;
import edu.stanford.nlp.mt.parser.Actions.Action;
import edu.stanford.nlp.mt.parser.Actions.ActionType;
import edu.stanford.nlp.parser.Parser;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.OpenAddressCounter;
import edu.stanford.nlp.trees.DependencyScoring;
import edu.stanford.nlp.trees.TypedDependency;
import edu.stanford.nlp.trees.DependencyScoring.Score;
import edu.stanford.nlp.trees.semgraph.SemanticGraph;
import edu.stanford.nlp.util.HashIndex;
import edu.stanford.nlp.util.Index;
import edu.stanford.nlp.util.StringUtils;

public class DepDAGParser implements Parser, Serializable {

  private static final long serialVersionUID = 2943413369292452706L;

  private LinearClassifier<Action,List<String>> classifier;
  private static final boolean VERBOSE = false;
  
//to reduce the total number of features for training, remove features appear less than 3 times
  private static final boolean REDUCE_FEATURES = true;

  @Override
  public boolean parse(List<? extends HasWord> sentence) {
    return true;  // accept everything for now.
  }

  public static DepDAGParser trainModel(
      List<Structure> rawTrainData) {
    DepDAGParser parser = new DepDAGParser();
    
    // to reduce the total number of features for training, remove features appear less than 3 times
    Counter<List<String>> featureCounter = null;
    if(REDUCE_FEATURES) featureCounter = countFeatures(rawTrainData);

    GeneralDataset<Action, List<String>> extTrainData = extractTrainingData(rawTrainData, featureCounter);

    LinearClassifierFactory<Action,List<String>> factory = new LinearClassifierFactory<Action,List<String>>();
    // TODO: check options

    featureCounter = null;
    
    // Build a classifier
    parser.classifier = factory.trainClassifier(extTrainData);
    if(VERBOSE) parser.classifier.dump();
    
    return parser;
  }


  private static Counter<List<String>> countFeatures(List<Structure> rawTrainData) {
    Counter<List<String>> counter = new OpenAddressCounter<List<String>>();
    
    for(Structure struc : rawTrainData) {
      List<Action> actions = struc.getActionTrace();
      struc.resetIndex();
      for(Action act : actions) {
        Datum<Action, List<String>> datum = extractFeature(act, struc, null);
        for(List<String> feature : datum.asFeatures()) {
          counter.incrementCount(feature);
        }
        Actions.doAction(act, struc);
      }
    }
    return counter;
  }

  private static GeneralDataset<Action, List<String>> extractTrainingData(List<Structure> rawTrainData, Counter<List<String>> featureCounter) {
    GeneralDataset<Action, List<String>> extracted = new Dataset<Action, List<String>>();
    for(Structure struc : rawTrainData) {
      List<Action> actions = struc.getActionTrace();
      struc.resetIndex();
      for(Action act : actions) {
        Datum<Action, List<String>> datum = extractFeature(act, struc, featureCounter);
        if(datum.asFeatures().size() > 0) {
          extracted.add(datum);
        }
        Actions.doAction(act, struc);
      }
    }
    return extracted;
  }

  private static Datum<Action, List<String>> extractFeature(Action act, Structure s, Counter<List<String>> featureCounter){
    // if act == null, test data
    if(s.getCurrentInputIndex() >= s.getInput().size()) return null;  // end of sentence
    List<List<String>> features = DAGFeatureExtractor.extractFeatures(s);
    if(featureCounter!=null) {
      Set<List<String>> rareFeatures = new HashSet<List<String>>(); 
      for(List<String> feature : features) {
        if(featureCounter.getCount(feature) < 3) rareFeatures.add(feature);
      }
      features.removeAll(rareFeatures);
    }
    return new BasicDatum<Action, List<String>>(features, act);
  }
  
  // for extracting features from test data (no gold Action given)
  private static Datum<Action, List<String>> extractFeature(Structure s){
    return extractFeature(null, s, null);
  }
  
  public SemanticGraph getDependencyGraph(Structure s){    
    Datum<Action, List<String>> d;
    while((d=extractFeature(s))!=null){
      Action nextAction;
      if(s.getStack().size()==0) nextAction = new Action(ActionType.SHIFT); 
      else nextAction = classifier.classOf(d);
      Actions.doAction(nextAction, s);
    }
    return s.dependencies;    
  }
  
  public static void main(String[] args) throws IOException, ClassNotFoundException{
    boolean doTrain = true;
    boolean doTest = false;
    boolean storeTrainedModel = true;
    
    // temporary code for scorer test
    boolean testScorer = false;
    if(testScorer) {
      testScorer();
      return;
    }
    
    Properties props = StringUtils.argsToProperties(args);
    
    // set logger
    
    String timeStamp = Calendar.getInstance().getTime().toString().replaceAll("\\s", "-");
    Logger logger = Logger.getLogger(DepDAGParser.class.getName());
    
    FileHandler fh;
    try {
      String logFileName = props.getProperty("log", "log.txt");
      logFileName.replace(".txt", "_"+ timeStamp+".txt");
      fh = new FileHandler(logFileName, false);
      logger.addHandler(fh);
      logger.setLevel(Level.FINE);
      fh.setFormatter(new SimpleFormatter());
    } catch (SecurityException e) { 
      System.err.println("ERROR: cannot initialize logger!");
      throw e;
    } catch (IOException e) { 
      System.err.println("ERROR: cannot initialize logger!");
      throw e;
    }
    
    if(props.containsKey("train")) doTrain = true;
    if(props.containsKey("test")) doTest = true;
    
    if(REDUCE_FEATURES) logger.fine("REDUCE_FEATURES on");
    else logger.fine("REDUCE_FEATURES off");

    // temporary for debug

//    String tempTrain = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/tb3-trunk-dev-2011-01-13.conll";
//    String tempTest = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/small_train.conll";
//    props.put("train", tempTrain);
//    props.put("test", tempTest);
//    String tempTest = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/temp2.conll";
//    String tempTrain = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/temp.conll";
//    props.put("train", tempTrain);
//    props.put("test", tempTest);

    if(doTrain) {
      String trainingFile = props.getProperty("train", "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/tb3-trunk-train-2011-01-13.conll");

      logger.info("read training data from "+trainingFile + " ...");
      List<Structure> trainData = ActionRecoverer.readTrainingData(trainingFile);

      logger.info("train model...");
      DAGFeatureExtractor.printFeatureFlags(logger);
      DepDAGParser parser = trainModel(trainData);
      
      if(storeTrainedModel) {
        String defaultStore = "/scr/heeyoung/mtdata/DAGparserModel.ser";
        if(!props.containsKey("storeModel")) logger.info("no option -storeModel : trained model will be stored at "+defaultStore); 
        String trainedModelFile = props.getProperty("storeModel", defaultStore);
        IOUtils.writeObjectToFile(parser, trainedModelFile);
      }
      
      logger.info("training is done");
    }
    
    if(doTest) {
      String testFile = props.getProperty("test", "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/tb3-trunk-dev-2011-01-13.conll");
      String defaultLoadModel = "/scr/heeyoung/mtdata/DAGparserModel.reducedFeat_mem5_dataset.ser";
      if(!props.containsKey("loadModel")) logger.info("no option -loadModel : trained model will be loaded from "+defaultLoadModel); 
      String trainedModelFile = props.getProperty("loadModel", defaultLoadModel);
      logger.info("load trained model...");
      
      Date s1 = new Date();
      DepDAGParser parser = IOUtils.readObjectFromFile(trainedModelFile);
      logger.info((((new Date()).getTime() - s1.getTime())/ 1000F) + "seconds\n");
      
      logger.info("read test data from "+testFile + " ...");
      List<Structure> testData = ActionRecoverer.readTrainingData(testFile);
      
      List<Collection<TypedDependency>> goldDeps = new ArrayList<Collection<TypedDependency>>();
      List<Collection<TypedDependency>> systemDeps = new ArrayList<Collection<TypedDependency>>();
      
      logger.info("testing...");
      int count = 0;
      long elapsedTime = 0;
      for(Structure s : testData){
        count++;
        goldDeps.add(s.getDependencyGraph().typedDependencies());
        s.resetIndex();
        Date startTime = new Date();
        SemanticGraph graph = parser.getDependencyGraph(s);
        elapsedTime += (new Date()).getTime() - startTime.getTime();
        systemDeps.add(graph.typedDependencies());
      }
      System.out.println("The number of sentences = "+count);
      System.out.printf("avg time per sentence: %.3f seconds\n", (elapsedTime / (count*1000F)));
      System.out.printf("Total elapsed time: %.3f seconds\n", (elapsedTime / 1000F));
      
      logger.info("scoring...");
      DependencyScoring goldScorer = DependencyScoring.newInstanceStringEquality(goldDeps);
      Score score = goldScorer.score(DependencyScoring.convertStringEquality(systemDeps));
      logger.info(score.toString(false));
      logger.info("done");
    }
  }

  public static void testScorer() throws IOException {
    String trainingFile = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/tb3-trunk-dev-2011-01-13.conll";
    String devFile = "/scr/heeyoung/corpus/dependencies/Stanford-11Feb2011/tb3-trunk-dev-2011-01-13.conll";
    List<Structure> devData = ActionRecoverer.readTrainingData(devFile);
//    List<Structure> trainData = ActionRecoverer.readTrainingData(trainingFile);

    List<Collection<TypedDependency>> goldDeps = new ArrayList<Collection<TypedDependency>>();
    List<Collection<TypedDependency>> systemDeps = new ArrayList<Collection<TypedDependency>>();
    Collection<TypedDependency> temp = new ArrayList<TypedDependency>();

    for(Structure s : devData){
      temp = s.getDependencyGraph().typedDependencies();
      goldDeps.add(s.getDependencyGraph().typedDependencies());
//      systemDeps.add(temp);
      temp = s.getDependencyGraph().typedDependencies();
      systemDeps.add(temp);
    }

    DependencyScoring goldScorer = DependencyScoring.newInstanceStringEquality(goldDeps);
    Score score = goldScorer.score(DependencyScoring.convertStringEquality(systemDeps));
    System.out.println(score.toString(true));
  }
  
}