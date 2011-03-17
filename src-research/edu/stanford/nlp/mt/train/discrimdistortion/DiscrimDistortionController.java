package edu.stanford.nlp.mt.train.discrimdistortion;

import java.io.File;
import java.io.IOException;
import java.io.PrintStream;

import edu.stanford.nlp.mt.base.IOTools;
import edu.stanford.nlp.io.IOUtils;
import edu.stanford.nlp.optimization.AbstractCachingDiffFunction;
import edu.stanford.nlp.optimization.DiffFunction;
import edu.stanford.nlp.optimization.Minimizer;
import edu.stanford.nlp.optimization.OWLQNMinimizer;
import edu.stanford.nlp.util.HashIndex;
import edu.stanford.nlp.util.Index;

public class DiscrimDistortionController {

  private final File sourceFile;
  private final File targetFile;
  private final File alignFile;

  private boolean VERBOSE = false;
  private Index<DistortionModel.Feature> features;

  private DistortionModel model = null;
  private final String modelFile;

  private int numFeThreads = 1;
  private int numExpectedFeatures = -1;
  private int minWordCount = 40;
  private int numFeatures = 0;
  private float trainingThreshold = Integer.MAX_VALUE;
  private boolean subSample = false;
  private float subSampleRate = 0.0f;
  private boolean OUTBOUND = false;
  private boolean useDelims = false;

  public DiscrimDistortionController(final String sourceFile,
      final String targetFile, final String alignFile, final String modelName) {

    this.sourceFile = new File(sourceFile);
    this.targetFile = new File(targetFile);
    this.alignFile = new File(alignFile);
    modelFile = modelName;
  }

  public void setVerbose(final boolean verbose) {
    VERBOSE = verbose;
  }

  public void setNumThreads(int numThreads) {
    this.numFeThreads = numThreads;
  }

  public void preAllocateMemory(int numExpectedFeatures) {
    this.numExpectedFeatures = numExpectedFeatures;
  }

  public void setMinWordCount(int minWordCount) {
    this.minWordCount = minWordCount;
  }

  public void setTrainingThreshold(float thresh) {
    trainingThreshold = thresh;
  }

  public void subSampleFeatureExtraction(boolean b, float rate) {
    subSample = b;
    subSampleRate = rate;
  }

  public void insertDelimiters(boolean b) {
    if (VERBOSE && b)
      System.err.println("Inserting sentence begin/end delimiters");
    useDelims = b;
  }

  public void trainOutboundModel() {
    OUTBOUND = true;
  }

  public void setFeatureFlags(final boolean use_word, final boolean use_tag,
      final boolean use_position, final boolean use_slen,
      final boolean use_context, final boolean use_arc_tag) {

    features = new HashIndex<DistortionModel.Feature>();

    // WARNING!!! If you change the ordering, be sure that nothing
    // breaks in FeatureExtractor

    // Binary-valued features
    if (use_word)
      features.add(DistortionModel.Feature.Word);
    if (use_tag)
      features.add(DistortionModel.Feature.CurrentTag);
    if (use_slen)
      features.add(DistortionModel.Feature.SourceLen);
    if (use_position)
      features.add(DistortionModel.Feature.RelPosition);
    if (use_context) {
      features.add(DistortionModel.Feature.LeftTag);
      features.add(DistortionModel.Feature.RightTag);
    }
    if (use_arc_tag)
      features.add(DistortionModel.Feature.ArcTag);

    if (VERBOSE)
      System.err.printf("Features:\n %s\n", features.toString());
  }

  private Index<DistortionModel.Class> getClassIndex() {
    Index<DistortionModel.Class> classIndex = new HashIndex<DistortionModel.Class>();
    for (DistortionModel.Class c : DistortionModel.Class.values())
      classIndex.add(c);

    if (VERBOSE)
      System.err.printf("Classes:\n %s\n", classIndex.toString());

    return classIndex;
  }

  public boolean run() {
    if (!sourceFile.exists())
      System.err.printf("%s: %s does not exist!\n", this.getClass().getName(),
          sourceFile.getAbsolutePath());
    else if (!targetFile.exists())
      System.err.printf("%s: %s does not exist!\n", this.getClass().getName(),
          targetFile.getAbsolutePath());
    else if (!alignFile.exists())
      System.err.printf("%s: %s does not exist!\n", this.getClass().getName(),
          alignFile.getAbsolutePath());
    else {
      FeatureExtractor fe = new FeatureExtractor(numFeThreads, sourceFile,
          targetFile, alignFile);
      fe.setVerbose(VERBOSE);
      fe.setMinWordCount(minWordCount);
      fe.setThreshold(trainingThreshold);
      fe.setSubSampling(subSample, subSampleRate);
      if (useDelims)
        fe.insertDelims();
      if (OUTBOUND)
        fe.extractOutbound();

      if (VERBOSE)
        System.err.printf("Model type: %s\n", (OUTBOUND) ? "outbound"
            : "inbound");

      System.out.println("Extracting features...");
      TrainingSet ts = fe.extract(features, getClassIndex(),
          numExpectedFeatures);
      numFeatures = ts.getNumFeatures();
      model = fe.getModel();
      System.out.println("...done extracting features!");

      if (VERBOSE)
        System.out.println(ts.toString());

      // Run QNminimizer with a log conditional objective function
      // (Gaussian prior)
      System.out.println("Running Newton's method minimizer...");
      AbstractCachingDiffFunction logCond = new LogConditionalObjectiveFunction(
          ts);
      // Minimizer<DiffFunction> m = new QNMinimizer(15);
      Minimizer<DiffFunction> m = new OWLQNMinimizer();
      double[] initial = logCond.initial();
      model.weights = m.minimize(logCond, 1e-3, initial);
      System.out.println("done!");

      return true;
    }
    return false;
  }

  public void outputModel() {
    if (VERBOSE)
      System.out.println(model.toString());

    try {
      // For debugging
      if (model.weights != null) {
        PrintStream ps = IOTools.getWriterFromFile("dd.debug.wts.gz");
        for (int i = 0; i < model.weights.length; i++) {
          if (i % numFeatures == 0)
            ps.println();
          ps.println(model.weights[i]);
        }
        ps.close();
      }

      IOUtils.writeObjectToFile(model, modelFile);

    } catch (IOException e) {
      System.err.printf("%s: IO error while serializing model to file\n", this
          .getClass().getName());
      e.printStackTrace();
    }
  }

  public void extractOnly(String extractFile) {
    FeatureExtractor fe = new FeatureExtractor(numFeThreads, sourceFile,
        targetFile, alignFile);
    fe.setVerbose(VERBOSE);
    fe.setMinWordCount(minWordCount);
    fe.setThreshold(trainingThreshold);
    if (useDelims)
      fe.insertDelims();
    if (OUTBOUND)
      fe.extractOutbound();

    System.out.println("Extracting features...");
    TrainingSet ts = fe.extract(features, getClassIndex(), numExpectedFeatures);
    DistortionModel model = fe.getModel();
    System.out.println("...done extracting features!\n");

    if (VERBOSE)
      System.out.println(ts.toString());

    System.out.printf("\nWriting features to %s...\n", extractFile);
    PrintStream ps = IOTools.getWriterFromFile(extractFile);
    for (Datum d : ts) {

      String goldClass = DistortionModel.discretizeDistortion(
          (int) d.getTarget()).toString();
      ps.printf("%s %f", goldClass, d.getTarget());

      int i = 0;
      for (DistortionModel.Feature feat : features) {
        if (feat == DistortionModel.Feature.Word)
          ps.printf(" %s", model.wordIndex.get((int) d.get(i)));
        else if (feat == DistortionModel.Feature.CurrentTag)
          ps.printf(" %s", model.tagIndex.get((int) d.get(i)));
        else if (feat == DistortionModel.Feature.RelPosition)
          ps.printf(" %d", (int) d.get(i));
        else if (feat == DistortionModel.Feature.SourceLen)
          ps.printf(" %d", (int) d.get(i));
        else if (feat == DistortionModel.Feature.LeftTag)
          ps.printf(" %s", model.tagIndex.get((int) d.get(i)));
        else if (feat == DistortionModel.Feature.RightTag)
          ps.printf(" %s", model.tagIndex.get((int) d.get(i)));
        else if (feat == DistortionModel.Feature.ArcTag)
          ps.printf(" %s", model.tagIndex.get((int) d.get(i)));
        i++;
      }
      ps.println();
    }
    ps.close();
    System.out.printf("...done writing %d examples\n", ts.getNumExamples());
  }

}