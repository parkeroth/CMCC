package com.bloomcyclecare.cmcc.logic.chart;

import com.bloomcyclecare.cmcc.data.models.instructions.AbstractInstruction;
import com.bloomcyclecare.cmcc.data.models.stickering.Sticker;
import com.bloomcyclecare.cmcc.utils.DecisionTree;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class StickerSelector {

  private static final ImmutableMap<Sticker, DecisionTree.Node> LEAF_NODES = ImmutableMap.<Sticker, DecisionTree.Node>builder()
      .put(Sticker.RED, new DecisionTree.LeafNode(Sticker.RED))
      .put(Sticker.GREEN, new DecisionTree.LeafNode(Sticker.GREEN))
      .put(Sticker.GREEN_BABY, new DecisionTree.LeafNode(Sticker.GREEN_BABY))
      .put(Sticker.YELLOW, new DecisionTree.LeafNode(Sticker.YELLOW))
      .put(Sticker.YELLOW_BABY, new DecisionTree.LeafNode(Sticker.YELLOW_BABY))
      .put(Sticker.WHITE_BABY, new DecisionTree.LeafNode(Sticker.WHITE_BABY))
      .put(Sticker.GREY, new DecisionTree.LeafNode(Sticker.GREY))
      .build();

  static final String BLEEDING_POSITIVE_EXPLANATION = "Observation had either H, M, L, VL, B or R";
  static final String BLEEDING_NEGATIVE_EXPLANATION = "No signs of bleeding present in observation (i.e., H, M, L, VL, B or R)";

  static final String MUCUS_POSITIVE_EXPLANATION = "Observation had either 6, 8 or 10";
  static final String MUCUS_NEGATIVE_EXPLANATION = "No signs of mucus present in observation (i.e., 6, 8 or 10)";

  static final String FERTILE_POSITIVE_EXPLANATION = "Active fertility instructions: ";
  static final String FERTILE_NEGATIVE_EXPLANATION = "No fertility instructions (D.1 - D.6) are active";

  static final String INFERTILE_POSITIVE_EXPLANATION = "Active special instructions: ";
  static final String INFERTILE_NEGATIVE_EXPLANATION = "No special instructions apply to warrant yellow stamps";

  private static final DecisionTree.Node TREE = new DecisionTree.ParentNode(
      new DecisionTree.And(
          new DecisionTree.Criteria(
              c -> c.hasObservation,
              c -> "has observation",
              c -> "doesn't have observation",
              c -> "",
              c -> ""
          ),
          new DecisionTree.Criteria(
              c -> c.hasInstructions,
              c -> "has instructions",
              c -> "doesn't have instructions",
              c -> "",
              c -> ""
          )
      ),
      false,
      new DecisionTree.ParentNode(
          new DecisionTree.Criteria(
              c -> c.hasBleeding,
              c -> "has bleeding",
              c -> "doesn't have bleeding",
              c -> BLEEDING_POSITIVE_EXPLANATION,
              c -> BLEEDING_NEGATIVE_EXPLANATION
          ),
          true,
          LEAF_NODES.get(Sticker.RED),
          new DecisionTree.ParentNode(
              new DecisionTree.Criteria(
                  c -> c.hasMucus,
                  c -> "has mucus",
                  c -> "doesn't have mucus",
                  c -> MUCUS_POSITIVE_EXPLANATION,
                  c -> MUCUS_NEGATIVE_EXPLANATION
              ),
              true,
              new DecisionTree.ParentNode(
                  new DecisionTree.Criteria(
                      c -> !c.infertilityReasons.isEmpty(),
                      c -> "has active special instructions",
                      c -> "doesn't have active special instructions",
                      c -> INFERTILE_POSITIVE_EXPLANATION + Joiner.on(", ").join(c.infertilityReasons.stream().map(AbstractInstruction::description).collect(Collectors.toList())),
                      c -> INFERTILE_NEGATIVE_EXPLANATION
                  ),
                  true,
                  new DecisionTree.ParentNode(
                      new DecisionTree.Criteria(
                          c -> !c.fertilityReasons.isEmpty(),
                          c -> "is fertile",
                          c -> "isn't fertile",
                          c -> FERTILE_POSITIVE_EXPLANATION + Joiner.on(", ").join(c.fertilityReasons.stream().map(AbstractInstruction::description).collect(Collectors.toList())),
                          c -> FERTILE_NEGATIVE_EXPLANATION
                      ),
                      true,
                      LEAF_NODES.get(Sticker.YELLOW_BABY),
                      LEAF_NODES.get(Sticker.YELLOW)
                  ),
                  LEAF_NODES.get(Sticker.WHITE_BABY)
              ),
              new DecisionTree.ParentNode(
                  new DecisionTree.Criteria(
                      c -> !c.fertilityReasons.isEmpty(),
                      c -> "is fertile",
                      c -> "isn't fertile",
                      c -> FERTILE_POSITIVE_EXPLANATION + Joiner.on(", ").join(c.fertilityReasons.stream().map(AbstractInstruction::description).collect(Collectors.toList())),
                      c -> FERTILE_NEGATIVE_EXPLANATION
                  ),
                  true,
                  LEAF_NODES.get(Sticker.GREEN_BABY),
                  LEAF_NODES.get(Sticker.GREEN)
              )
          )
      ),
      LEAF_NODES.get(Sticker.GREY)
  );

  public static SelectResult select(CycleRenderer.StickerSelectionContext context) {
    SelectResult result = new SelectResult();
    result.matchedCriteria = new ArrayList<>();
    result.sticker = TREE.select(context, result.matchedCriteria);
    return result;
  }

  public static class SelectResult {
    public Sticker sticker;
    public List<String> matchedCriteria;
  }

  public static CheckResult check(Sticker selection, CycleRenderer.StickerSelectionContext context) {
    SelectResult expectedResult = select(context);
    if (selection.equals(expectedResult.sticker)) {
      return CheckResult.ok(expectedResult.sticker);
    }

    Set<DecisionTree.Node> ancestors = DecisionTree.ancestors(LEAF_NODES.get(expectedResult.sticker));
    DecisionTree.Node currentNode = LEAF_NODES.get(selection);

    DecisionTree.ParentNode parentNode = null;
    boolean pathDir = false;
    while (!ancestors.contains(currentNode) && currentNode.parent().isPresent()) {
      parentNode = currentNode.parent().get();
      if (currentNode == parentNode.branchTrue) {
        pathDir = true;
      } else if (currentNode == parentNode.branchFalse) {
        pathDir = false;
      } else {
        throw new IllegalStateException(
            String.format("Node %s is not a child of %s",
                currentNode.description(), parentNode.description()));
      }
      currentNode = currentNode.parent().get();
    }

    return CheckResult.incorrect(
        expectedResult.sticker,
        String.format("Can't be %s, today %s %s",
            selection.name(),
            parentNode.critera.getReason(!pathDir, context),
            parentNode.description()),
        parentNode.explanation(context));
  }

  public static class CheckResult {
    Sticker expected;
    Optional<String> errorMessage = Optional.empty();
    Optional<String> errorExplanation = Optional.empty();

    static CheckResult ok(Sticker expected) {
      CheckResult result = new CheckResult();
      result.expected = expected;
      return result;
    }

    static CheckResult incorrect(Sticker expected, String errorMessage, String errorExplanation) {
      CheckResult result = new CheckResult();
      result.expected = expected;
      result.errorMessage = Optional.of(errorMessage);
      result.errorExplanation = Optional.of(errorExplanation);
      return result;
    }

    public boolean ok() {
      return !errorMessage.isPresent();
    }
  }
}
