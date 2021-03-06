package com.bloomcyclecare.cmcc.logic.goals;

import com.google.common.base.Joiner;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.bloomcyclecare.cmcc.logic.goals.GoalModel.Instances;
import static com.bloomcyclecare.cmcc.logic.goals.GoalModel.Period;
import static com.bloomcyclecare.cmcc.logic.goals.GoalModel.RelativeChange;
import static com.bloomcyclecare.cmcc.logic.goals.GoalModel.SpecialInstances;
import static com.bloomcyclecare.cmcc.logic.goals.GoalModel.Type;

/**
 * Created by parkeroth on 2/20/18.
 */

public class GoalModelFactory {

  private static final ImmutableSet<GoalModel> REGISTERED_GOAL_MODEL_TEMPLATE = ImmutableSet.<GoalModel>builder()
      .add(GoalModel.builder("drink", Type.DRINK).withRelativeChange(RelativeChange.MORE).withObject("water").build())
      .add(GoalModel.builder("drink", Type.DRINK).withRelativeChange(RelativeChange.LESS).withObject("alcohol").build())
      .add(GoalModel.builder("drink", Type.DRINK).withRelativeChange(RelativeChange.LESS).withObject("caffeine").build())
      .add(GoalModel.builder("eat", Type.EAT).withRelativeChange(RelativeChange.MORE).withObject("fiber").build())
      .add(GoalModel.builder("eat", Type.EAT).withRelativeChange(RelativeChange.LESS).withObject("gluten").build())
      .add(GoalModel.builder("eat", Type.EAT).withRelativeChange(RelativeChange.LESS).withObject("dairy").build())
      .add(GoalModel.builder("eat", Type.EAT).withRelativeChange(RelativeChange.LESS).withObject("sugar").build())
      .add(GoalModel.builder("journal", Type.GENERAL).build())
      .add(GoalModel.builder("meditate", Type.GENERAL).build())
      .build();

  private final ImmutableSet<GoalModel> mRegisteredGoalModels;
  private final Map<String, Set<GoalModel>> mActionIndex;

  public static GoalModelFactory withDefaultTemplates() {
    return new GoalModelFactory(REGISTERED_GOAL_MODEL_TEMPLATE);
  }

  public GoalModelFactory(ImmutableSet<GoalModel> registeredTemplates) {
    mRegisteredGoalModels = registeredTemplates;
    mActionIndex = new HashMap<>();
    for (GoalModel template : registeredTemplates) {
      if (!mActionIndex.containsKey(template.action)) {
        mActionIndex.put(template.action, new HashSet<GoalModel>());
      }
      mActionIndex.get(template.action).add(template);
    }
  }

  public List<GoalModel> fromInput(String input, int numInstanceSuggestions) {
    // Check if there's actually input
    if (input.isEmpty()) {
      return forEmptyInput();
    }

    String[] parts = input.split(" ");

    Set<String> actions = getActions(parts);
    if (actions.size() > 1) {
      return forSeveralActions(actions);
    }

    // Make sure matched action is not a partial match
    String firstPart = parts[0];
    String action = Iterables.getOnlyElement(actions);
    boolean isPartial = !action.equals(firstPart);
    boolean actionComplete = parts.length > 1;
    if (actionComplete && isPartial) {
      action = firstPart;
    }

    return forSingleAction(action, parts, numInstanceSuggestions);
  }

  private List<GoalModel> forEmptyInput() {
    Set<String> actionsSeen = new HashSet<>();
    Set<String> moreActionsSeen = new HashSet<>();
    Set<String> lessActionsSeen = new HashSet<>();
    Set<GoalModel> out = new TreeSet<>();
    for (GoalModel template : mRegisteredGoalModels) {
      Set<String> correctSet = null;
      switch (template.relativeChange) {
        case NA:
          correctSet = actionsSeen;
          break;
        case MORE:
          correctSet = moreActionsSeen;
          break;
        case LESS:
          correctSet = lessActionsSeen;
          break;
        default:
          throw new IllegalStateException("Unsupported change type: " + template.relativeChange.name());
      }
      if (correctSet.add(template.action)) {
        out.add(template.withoutObject());
      }
    }
    return Lists.newArrayList(out);
  }

  private List<GoalModel> forSeveralActions(Set<String> actions) {
    List<GoalModel> out = new ArrayList<>();
    for (String action : actions) {
      if (mActionIndex.containsKey(action)) {
        out.add(Iterables.getLast(mActionIndex.get(action)).withoutObject());
      } else {
        out.add(GoalModel.builder(action, Type.GENERAL).build());
      }
    }
    return out;
  }

  private List<GoalModel> forSingleAction(String action, String[] parts, int numInstanceSuggestions) {
    if (mActionIndex.containsKey(action)) {
      return forRegisteredAction(action, parts, mActionIndex.get(action));
    }
    return forUnknownAction(GoalModel.builder(action, Type.GENERAL), parts, numInstanceSuggestions);
  }

  private List<GoalModel> forRegisteredAction(String action, String[] parts, Set<GoalModel> templates) {
    GoalModel sample = Iterables.getLast(templates);
    GoalModel.Builder builder = GoalModel.builder(action, sample.type);
    if (sample.relativeChange == RelativeChange.NA) {
      return forNonRelativeAction(action, parts, templates);
    }
    return forRelativeAction(builder, parts, templates);
  }

  private List<GoalModel> forNonRelativeAction(String action, String[] parts, Set<GoalModel> templates) {
    GoalModel.Builder builder = GoalModel.builder(action, Iterables.getLast(templates).type);
    return forUnknownAction(builder, parts, 3);
  }

  private List<GoalModel> forRelativeAction(GoalModel.Builder builder, String[] parts, Set<GoalModel> templates) {
    List<GoalModel> out = new ArrayList<>();
    if (parts.length < 2) {
      out.addAll(templates);
      return out;
    }
    String secondPart = parts[1];
    RelativeChange relativeChange = null;
    for (RelativeChange item : RelativeChange.values()) {
      if (item.equals(RelativeChange.NA)) {
        continue;
      }
      if (item.name().toLowerCase().startsWith(secondPart)) {
        relativeChange = item;
      }
    }
    if (relativeChange != null) {
      String object = toString(parts, 2, parts.length);
      GoalModel fromInput = builder.copyOf().withRelativeChange(relativeChange).withObject(object).build();
      out.add(fromInput);
      for (GoalModel template : templates) {
        if (relativeChange.equals(template.relativeChange)) {
          if (!template.equals(fromInput) && (object.isEmpty() || template.object.startsWith(object))) {
            out.add(template);
          }
        }
      }
      return out;
    }
    out.add(builder.withObject(toString(parts, 1, parts.length)).build());
    return out;
  }

  // Examples
  //  - journal four times per week
  //  - meditate once per {day, week month}
  private List<GoalModel> forUnknownAction(GoalModel.Builder builder, String[] parts, int numInstanceSuggestions) {
    if (parts.length == 1) {
      return withSuggestedInstances(builder);
    }
    int startOfInstanceIndex = startOfInstanceIndex(parts, builder);
    if (startOfInstanceIndex > -1) {
      // Text between action and start of instance (either once, one, 1 [time(s)])
      String object = toString(parts, 1, startOfInstanceIndex);
      builder.withObject(object);
      List<GoalModel> out = new ArrayList<>();
      Set<Period> periods = Period.matches(parts[parts.length - 1]);
      if (periods.isEmpty()) {
        periods = Sets.newHashSet(Period.values());
      }
      for (Period period : periods) {
        out.add(builder.copyOf().withPeriod(period).build());
      }
      return out;
    }
    int index = parts.length - 1;
    String lastPart = parts[index];
    // Skip over "times" or "time" if it's the last part
    if (lastPart.startsWith("time")) {
      index--;
      lastPart = parts[index];
    }
    // Assume text between the last piece and the action is the object
    builder.withObject(toString(parts, 1, index));
    List<GoalModel> out = new ArrayList<>();
    // See if the last part matches any instances
    try {
      Instances instances = Instances.valueOf(Integer.valueOf(lastPart));
      out.add(builder.copyOf().withInstances(instances, true).build());
    } catch (NumberFormatException nfe) {
      for (SpecialInstances specialInstances : SpecialInstances.values()) {
        if (specialInstances.name().startsWith(lastPart)) {
          out.add(builder.copyOf().withSpecialInstances(specialInstances).build());
        }
      }
      for (Instances instances : Instances.values()) {
        if (instances.name().startsWith(lastPart)) {
          out.add(builder.copyOf().withInstances(instances, false).build());
        }
      }
    }
    // Return the filtered list of instances if present
    if (!out.isEmpty()) {
      return out;
    }
    // Otherwise come up with some suggestions and treat all input as the object
    builder.withObject(toString(parts, 1, parts.length));
    return withSuggestedInstances(builder);
  }

  private List<GoalModel> withSuggestedInstances(GoalModel.Builder builder) {
    List<GoalModel> templates = new ArrayList<>();
    //templates.add(builder.build());
    templates.add(builder.copyOf().withSpecialInstances(SpecialInstances.once).build());
    templates.add(builder.copyOf().withSpecialInstances(SpecialInstances.twice).build());
    templates.add(builder.copyOf().withInstances(Instances.three, false).build());
    return templates;
  }

  private int startOfInstanceIndex(String[] parts, GoalModel.Builder builder) {
    int timeIndex = -1;
    for (int i=1; i<parts.length; i++) {
      if (parts[i].startsWith("time")) {
        timeIndex = i;
      }
    }
    if (timeIndex != -1) {
      // verify word to left
      int indexToLeft = timeIndex - 1;
      String partToLeft = parts[indexToLeft];
      try {
        int intVal = Integer.valueOf(partToLeft);
        builder.withInstances(Instances.valueOf(intVal), true);
        return indexToLeft;
      } catch (NumberFormatException nfe) {
        for (Instances instances : Instances.values()) {
          if (instances.name().equals(partToLeft)) {
            builder.withInstances(instances, false);
            return indexToLeft;
          }
        }
      }
    } else {
      for (int i=1; i<parts.length; i++) {
        for (SpecialInstances specialInstances : SpecialInstances.values()) {
          if (parts[i].equals(specialInstances.name())) {
            builder.specialInstances = specialInstances;
            return i;
          }
        }
      }
      return -1;
    }
    return -1;
  }

  private Set<String> getActions(String[] parts) {
    Preconditions.checkArgument(parts.length > 0);
    Set<String> actions = new HashSet<>();
    for (GoalModel template : mRegisteredGoalModels) {
      if (template.action.startsWith(parts[0])) {
        actions.add(template.action);
      }
    }
    if (actions.isEmpty()) {
      actions.add(parts[0]);
    }
    return actions;
  }

  private String toString(String[] parts, int start, int endNotInclusive) {
    Preconditions.checkArgument(start >= 0);
    Preconditions.checkArgument(endNotInclusive <= parts.length);
    List<String> strs = new ArrayList<>();
    for (int i = start; i < endNotInclusive; i++) {
      strs.add(parts[i]);
    }
    return Joiner.on(" ").join(strs);
  }
}
