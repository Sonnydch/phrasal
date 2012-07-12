package edu.stanford.nlp.mt.scripts;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import edu.stanford.nlp.mt.tools.CompareWeights;
import edu.stanford.nlp.stats.Counter;
import edu.stanford.nlp.stats.Counters;
import edu.stanford.nlp.util.StringUtils;

public class PhrasalMert {

  private PhrasalMert() {} // static class

  public static void mergeIntegerIndexedFiles(String outputFile,
                                              String ... inputFiles)
    throws IOException
  {
    List<String> lines = new ArrayList<String>();
    for (String inputFile : inputFiles) {
      // TODO: do we care about input encoding?
      BufferedReader reader = new BufferedReader(new FileReader(inputFile));
      String line;
      while ((line = reader.readLine()) != null) {
        if (line.trim().equals(""))
          continue;
        lines.add(line);
      }
    }
    Collections.sort(lines, new Comparator<String>() {
        @Override
        public int compare(String l1, String l2) {
          String[] pieces1 = l1.trim().split("\\s+");
          String[] pieces2 = l2.trim().split("\\s+");
          return Integer.valueOf(pieces1[0]) - Integer.valueOf(pieces2[0]);
        }
      });
    FileWriter fout = new FileWriter(outputFile);
    BufferedWriter writer = new BufferedWriter(fout);
    for (String line : lines) {
      writer.write(line, 0, line.length());
      writer.newLine();
    }
    writer.flush();
    fout.close();
  }


  /**
   * Takes all of the data available in input and redirects it to output.
   */
  public static void connectStreams(InputStream input,
                                    OutputStream output)
    throws IOException
  {
    BufferedInputStream bufInput = new BufferedInputStream(input);
    BufferedOutputStream bufOutput = new BufferedOutputStream(output);
    byte[] buffer = new byte[1024];
    int bytesRead;
    while ((bytesRead = bufInput.read(buffer)) != -1) {
      bufOutput.write(buffer, 0, bytesRead);
    }
    bufOutput.flush();
  }

  public static class StreamConnectorThread extends Thread {
    public StreamConnectorThread(InputStream input, OutputStream output) {
      super();
      this.input = input;
      this.output = output;
    }

    final InputStream input;
    final OutputStream output;

    @Override
    public void run() {
      try {
        connectStreams(input, output);
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  /**
   * Runs the given command.  For each of the filenames stdin, stdout,
   * and stderr that are specified, pipes that file to or from the
   * process.  A null argument for a filename means to not use that pipe.
   */
  public static void runCommand(List<String> command, String stdinFile,
                                String stdoutFile, String stderrFile,
                                boolean combineErrorStream)
    throws IOException, InterruptedException
  {
    ProcessBuilder procBuilder = new ProcessBuilder(command);
    if (combineErrorStream)
      procBuilder.redirectErrorStream(combineErrorStream);
    Process proc = procBuilder.start();
    //Process proc = Runtime.getRuntime().exec(command);

    OutputStream stdoutStream = ((stdoutFile == null) ? System.out :
                                 new FileOutputStream(stdoutFile));
    Thread stdoutThread = new StreamConnectorThread(proc.getInputStream(),
                                                    stdoutStream);
    stdoutThread.start();

    OutputStream stderrStream = ((stderrFile == null) ? System.err :
                                 new FileOutputStream(stderrFile));
    Thread stderrThread = new StreamConnectorThread(proc.getErrorStream(),
                                                    stderrStream);
    stderrThread.start();

    if (stdinFile != null) {
      OutputStream procStdin = proc.getOutputStream();
      FileInputStream fin = new FileInputStream(stdinFile);
      connectStreams(fin, procStdin);
      procStdin.close();
    }

    proc.waitFor();

    stdoutThread.join();
    stderrThread.join();
    if (stdoutFile != null) {
      stdoutStream.close();
    }
    if (stderrFile != null) {
      stderrStream.close();
    }
  }

  public static void runCommand(String[] command, String stdinFile,
                                String stdoutFile, String stderrFile,
                                boolean combineErrorStream)
    throws IOException, InterruptedException
  {
    runCommand(Arrays.asList(command), stdinFile,
               stdoutFile, stderrFile, combineErrorStream);
  }

  public static final String PHRASAL_CLASS = "edu.stanford.nlp.mt.Phrasal";
  public static final String MERT_CLASS = "edu.stanford.nlp.mt.tune.MERT";

  // if the weights don't change more than this, we'll end the iterations
  public static final double TOL = 0.001;

  public static final String DEFAULT_NBEST_SIZE = "100";
  public static final String NBEST_SECTION = "n-best-list";
  public static final String WEIGHTS_SECTION = "weights-file";

  public static String getBinWeightsName(int iteration) {
    return "phrasal." + iteration + ".binwts";
  }

  public static String getWeightsName(int iteration) {
    return "phrasal." + iteration + ".wts";
  }

  public static String findWeightsFilename(int iteration) {
    String binWeightsName = getBinWeightsName(iteration);
    File binWeights = new File(binWeightsName);
    if (binWeights.exists()) {
      return binWeightsName;
    }

    String textWeightsName = getWeightsName(iteration);
    File textWeights = new File(textWeightsName);
    if (textWeights.exists()) {
      return textWeightsName;
    }

    return null;
  }

  public static String getBaseNBestName(int iteration) {
    return "phrasal." + iteration + ".nbest";
  }

  public static String getCombinedNBestName(int iteration) {
    return "phrasal." + iteration + ".combined.nbest";
  }

  public static String getNBestName(int iteration) {
    if (iteration == 0) {
      return getBaseNBestName(iteration);
    } else {
      return getCombinedNBestName(iteration);
    }
  }

  public static String getMertLogName(int iteration) {
    return "phrasal." + iteration + ".mertlog";
  }

  public static String getTransName(int iteration) {
    return "phrasal." + iteration + ".trans";
  }

  public static String getPhrasalLogName(int iteration) {
    return "phrasal." + iteration + ".dlog";
  }

  public static List<String> buildPhrasalCommand(String memory,
                                                 String libraryPath,
                                                 int iteration) {
    List<String> phrasalCommand = new ArrayList<String>();
    phrasalCommand.add("java");
    phrasalCommand.add("-mx" + memory);
    if (libraryPath != null) {
      phrasalCommand.add("-Djava.library.path=" + libraryPath);
    }
    phrasalCommand.add(PHRASAL_CLASS);
    phrasalCommand.add("-config-file");
    phrasalCommand.add(getConfigName(iteration));
    return phrasalCommand;
  }

  public static String getConfigName(int iteration) {
    return "phrasal." + iteration + ".ini";
  }

  public static void runPhrasalCommand(String inputFilename, String memory,
                                       String libraryPath, int iteration)
    throws IOException, InterruptedException
  {
    String transName = getTransName(iteration);
    String dlogName = getPhrasalLogName(iteration);
    List<String> phrasalCommand = buildPhrasalCommand(memory, libraryPath,
                                                      iteration);
    System.out.println("Running Phrasal command: " + phrasalCommand);
    runCommand(phrasalCommand, inputFilename, transName, dlogName, false);
  }

  public static List<String> buildMertCommand(String referenceFile,
                                              String memory, String metric,
                                              String libraryPath,
                                              int iteration) {
    List<String> mertCommand = new ArrayList<String>();
    mertCommand.add("java");
    mertCommand.add("-mx" + memory);
    if (libraryPath != null) {
      mertCommand.add("-Djava.library.path=" + libraryPath);
    }
    mertCommand.add(MERT_CLASS);
    // no idea what this actually is, just go with it
    mertCommand.addAll(Arrays.asList("-N -o cer -t 1 -p 5 -s".split(" ")));
    StringBuilder wtsString = new StringBuilder();
    for (int j = iteration; j >= 0; --j) {
      wtsString.append(getWeightsName(j));
      if (j > 0) {
        wtsString.append(",");
      }
    }
    mertCommand.add(wtsString.toString());
    mertCommand.add(metric);
    // TODO: gzip the nbest list?
    mertCommand.add(getNBestName(iteration));
    mertCommand.add(getNBestName(iteration));
    mertCommand.add(wtsString.toString());
    mertCommand.add(referenceFile);
    mertCommand.add(getWeightsName(iteration + 1));
    return mertCommand;
  }

  public static void runMertCommand(String referenceFile,
                                    String memory, String metric,
                                    String libraryPath, int iteration)
    throws IOException, InterruptedException
  {
    // MERT command: java  -Xmx4g -cp ../scripts/../phrasal.jar:../scripts/../lib/fastutil.jar:../scripts/../lib/mtj.jar -Djava.library.path=../scripts/../cpp edu.stanford.nlp.mt.tune.MERT -N -o cer -t 1 -p 5 -s /u/horatio/stanford-phrasal-2010-08-24/work/phrasal-mert/phrasal.2.wts,/u/horatio/stanford-phrasal-2010-08-24/work/phrasal-mert/phrasal.1.wts,/u/horatio/stanford-phrasal-2010-08-24/work/phrasal-mert/phrasal.0.wts bleu /u/horatio/stanford-phrasal-2010-08-24/work/phrasal-mert/phrasal.2.combined.nbest.gz /u/horatio/stanford-phrasal-2010-08-24/work/phrasal-mert/phrasal.2.nbest.gz /u/horatio/stanford-phrasal-2010-08-24/work/phrasal-mert/phrasal.2.wts,/u/horatio/stanford-phrasal-2010-08-24/work/phrasal-mert/phrasal.1.wts,/u/horatio/stanford-phrasal-2010-08-24/work/phrasal-mert/phrasal.0.wts data/dev/nc-dev2007.tok.en /u/horatio/stanford-phrasal-2010-08-24/work/phrasal-mert/phrasal.3.wts > /u/horatio/stanford-phrasal-2010-08-24/work/phrasal-mert/jmert.2.log 2>&1
    List<String> mertCommand =
      buildMertCommand(referenceFile, memory, metric, libraryPath, iteration);

    System.out.println("Running MERT command: " + mertCommand);

    runCommand(mertCommand, null, getMertLogName(iteration), null, true);
  }

  // Implements the most basic form of PhrasalMert
  // goal: reproduce the effects of
  // ../scripts/phrasal-mert.pl 4g data/dev/nc-dev2007.tok.fr
  //   data/dev/nc-dev2007.tok.en bleu phrasal.conf
  public static void main(String[] args)
    throws IOException, InterruptedException, ClassNotFoundException
  {
    Properties props = StringUtils.argsToProperties(args);
    String missing =
      StringUtils.checkRequiredProperties(props, "memory", "inputFile",
                                          "referenceFile", "metric",
                                          "phrasalConfigFile");
    if (missing != null) {
      System.err.println("Required property " + missing + " missing");
      System.err.println("Expected properties are: memory, inputFile, " +
                         " referenceFile, metric, phrasalConfigFile");
      System.err.println("Optional properties are: libraryPath");
      System.exit(2);
    }
    String memory = props.getProperty("memory");
    String inputFilename = props.getProperty("inputFile");
    String referenceFile = props.getProperty("referenceFile");
    String metric = props.getProperty("metric");
    String phrasalConfigFilename = props.getProperty("phrasalConfigFile");
    String libraryPath = props.getProperty("libraryPath");
    if (args.length == 6) {
      libraryPath = args[5];
    }
    ConfigFile configFile = ConfigFile.readConfigFile(phrasalConfigFilename);

    String nbestSize = DEFAULT_NBEST_SIZE;
    List<String> nbestDescription = configFile.getSection(NBEST_SECTION);
    if (nbestDescription != null) {
      for (String line : nbestDescription) {
        if (line.trim().matches("[0-9]+")) {
          nbestSize = line.trim();
        }
      }
    }

    int iteration = 0;
    while (true) {
      // update the
      String configName = getConfigName(iteration);
      String weightsName = findWeightsFilename(iteration);
      configFile.updateSection(NBEST_SECTION, getBaseNBestName(iteration),
                               nbestSize);
      if (weightsName == null) {
        if (iteration == 0) {
          throw new IllegalArgumentException("Initial weights file " +
                                             getWeightsName(iteration) +
                                             " expected");
        } else {
          throw new AssertionError("Expected mert to produce weights " +
                                   getWeightsName(iteration) + " or " +
                                   getBinWeightsName(iteration));
        }
      } else {
        configFile.updateSection(WEIGHTS_SECTION, weightsName);
      }
      configFile.outputFile(configName);

      runPhrasalCommand(inputFilename, memory, libraryPath, iteration);

      // TODO: build combined nbest list here
      if (iteration > 0) {
        mergeIntegerIndexedFiles(getNBestName(iteration),
                                 getNBestName(iteration - 1),
                                 getBaseNBestName(iteration));
      }

      runMertCommand(referenceFile, memory, metric, libraryPath, iteration);

      Counter<String> oldWeights =
        CompareWeights.readWeights(findWeightsFilename(iteration));
      Counter<String> newWeights =
        CompareWeights.readWeights(findWeightsFilename(iteration + 1));
      Counter<String> difference =
        Counters.absoluteDifference(oldWeights, newWeights);
      double maxDiff = Counters.max(difference);
      if (maxDiff < TOL)
        break;

      ++iteration;
    }
    System.out.println("Done after " + (iteration + 1) + " iterations.");
  }
}
