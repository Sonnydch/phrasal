package mt.translationtreebank;

import edu.stanford.nlp.util.*;
import edu.stanford.nlp.trees.*;
import edu.stanford.nlp.trees.tregex.*;
import edu.stanford.nlp.stats.*;
import java.io.*;
import java.util.*;

class FullInformationWangExperiment {
  public static void main(String args[]) throws IOException {
    List<TreePair> treepairs = ExperimentUtils.readAnnotatedTreePairs();

    TwoDimensionalCounter<String,String> cc = new TwoDimensionalCounter<String,String>();
    ClassicCounter<String> deTypeCounter = new ClassicCounter<String>();

    TwoDimensionalCounter<String, String> confusionMatrix = new TwoDimensionalCounter<String,String>();

    for(TreePair validSent : treepairs) {
      for(int deIdxInSent : validSent.NPwithDEs_deIdx_set) {
        Pair<Integer, Integer> chNPrange = validSent.NPwithDEs_deIdx.get(deIdxInSent);
        String np = validSent.oracleChNPwithDE(deIdxInSent);
        np = np.trim();
        String label = validSent.NPwithDEs_categories.get(deIdxInSent);
        String predictedType = "";

        Tree chTree = validSent.chTrees.get(0);
        Tree chNPTree = TranslationAlignment.getTreeWithEdges(chTree,chNPrange.first, chNPrange.second+1);

        if (label.equals("no B") || label.equals("other") || label.equals("multi-DEs")) {
          continue;
        }

        boolean hasDEC = ExperimentUtils.hasDEC(chNPTree);
        boolean hasDEG = ExperimentUtils.hasDEG(chNPTree);
        if (hasDEC && hasDEG) {
          System.err.println("error: both");
        } else if (hasDEC && !hasDEG) {
          predictedType = "swapped";
        } else if (hasDEG && !hasDEC) {
          if (ExperimentUtils.hasADJPpattern(chNPTree) || 
              ExperimentUtils.hasNPPNpattern(chNPTree) || 
              ExperimentUtils.hasQPpattern(chNPTree))
            predictedType = "ordered";
          else 
            predictedType = "swapped";
        } else {
          System.err.println("error: none");
        }
      
        if (label.startsWith("B") || label.equals("relative clause")) {
          label = "swapped";
        } else if (label.startsWith("A")) {
          label = "ordered";
        } else {
        }
        confusionMatrix.incrementCount(label, predictedType);
      }
    }
    System.out.println(confusionMatrix);

    ExperimentUtils.resultSummary(confusionMatrix);
  }
}
