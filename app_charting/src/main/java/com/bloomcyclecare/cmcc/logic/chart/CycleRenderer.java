package com.bloomcyclecare.cmcc.logic.chart;

import com.bloomcyclecare.cmcc.data.models.charting.Cycle;
import com.bloomcyclecare.cmcc.data.models.instructions.Instructions;
import com.bloomcyclecare.cmcc.data.models.charting.ChartEntry;
import com.bloomcyclecare.cmcc.data.models.instructions.AbstractInstruction;
import com.bloomcyclecare.cmcc.data.models.instructions.BasicInstruction;
import com.bloomcyclecare.cmcc.data.models.instructions.SpecialInstruction;
import com.bloomcyclecare.cmcc.data.models.instructions.YellowStampInstruction;
import com.bloomcyclecare.cmcc.data.models.observation.Flow;
import com.bloomcyclecare.cmcc.data.models.observation.IntercourseTimeOfDay;
import com.bloomcyclecare.cmcc.data.models.observation.Observation;
import com.bloomcyclecare.cmcc.data.models.stickering.StickerColor;
import com.bloomcyclecare.cmcc.data.models.stickering.StickerSelection;
import com.bloomcyclecare.cmcc.utils.DateUtil;
import com.google.auto.value.AutoValue;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

import org.joda.time.Days;
import org.joda.time.LocalDate;
import org.parceler.Parcel;
import org.parceler.ParcelConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import androidx.annotation.NonNull;
import androidx.core.util.Pair;
import timber.log.Timber;

public class CycleRenderer {

  private static final Joiner ON_NEW_LINE = Joiner.on("\n");
  private static final Joiner ON_DOUBLE_NEW_LINE = Joiner.on("\n\n");

  private final Cycle mCycle;
  private final Optional<Cycle> mPreviousCycle;
  private final TreeSet<ChartEntry> mEntries;
  private final TreeSet<Instructions> mInstructions;

  public CycleRenderer(Cycle cycle, Optional<Cycle> previousCycle, Collection<ChartEntry> entries, Collection<Instructions> allInstructions) {
    mCycle = cycle;
    mPreviousCycle = previousCycle;
    mEntries = new TreeSet<>((a, b) -> a.entryDate.compareTo(b.entryDate));
    mEntries.addAll(entries);
    mInstructions = new TreeSet<>((a, b) -> a.startDate.compareTo(b.startDate));
    mInstructions.addAll(allInstructions);
  }

  public Cycle cycle() {
    return mCycle;
  }

  public RenderableCycle render() {
    Timber.v("Rendering cycle %s", mCycle);

    Set<LocalDate> daysWithAnObservation = new HashSet<>();
    TreeSet<LocalDate> entriesEvaluated = new TreeSet<>();
    TreeSet<LocalDate> daysOfFlow = new TreeSet<>();
    Set<LocalDate> daysOfMucus = new HashSet<>();
    TreeSet<LocalDate> daysOfUnusualBleeding = new TreeSet<>();
    TreeSet<LocalDate> peakDays = new TreeSet<>();
    TreeSet<LocalDate> pointsOfChangeToward = new TreeSet<>();
    TreeSet<LocalDate> pointsOfChangeAway = new TreeSet<>();
    Map<LocalDate, Boolean> daysOfIntercourse = new HashMap<>();
    LocalDate mostRecentPeakTypeMucus = null;
    LocalDate lastDayOfThreeOrMoreDaysOfMucus = null;
    int consecutiveDaysOfMucus = 0;
    ChartEntry previousEntry = null;
    boolean hasHadLegitFlow = false;

    List<RenderableEntry> renderableEntries = new ArrayList<>(mEntries.size());

    // For every day before the current entry...
    for (ChartEntry e : mEntries) {
      entriesEvaluated.add(e.entryDate);
      LocalDate yesterday = e.entryDate.minusDays(1);

      State state = new State();
      state.cycle = mCycle;
      state.previousCycle = mPreviousCycle;
      state.entry = e;
      state.entryDate = e.entryDate;
      state.entryNum = Days.daysBetween(mCycle.startDate, e.entryDate).getDays() + 1;
      state.previousEntry = previousEntry;

      // Step 1: Gather basic info which does not depend on the active instructions
      if (e.observationEntry.peakDay) {
        peakDays.add(e.entryDate);
      }
      if (e.observationEntry.pointOfChange) {
        if (pointsOfChangeToward.size() == pointsOfChangeAway.size()) {
          pointsOfChangeToward.add(e.entryDate);
        } else {
          pointsOfChangeAway.add(e.entryDate);
        }
      }
      state.firstPointOfChangeToward = pointsOfChangeToward.isEmpty() ? Optional.empty()
          : Optional.of(pointsOfChangeToward.first());
      state.mostRecentPointOfChangeToward = pointsOfChangeToward.isEmpty() ? Optional.empty()
          : Optional.of(pointsOfChangeToward.last());
      state.mostRecentPointOfChangeAway = pointsOfChangeAway.isEmpty() ? Optional.empty()
          : Optional.of(pointsOfChangeAway.last());
      if (e.observationEntry.unusualBleeding) {
        daysOfUnusualBleeding.add(e.entryDate);
      }
      if (e.observationEntry.hasMucus()) {
        daysOfMucus.add(e.entryDate);
      }
      daysOfIntercourse.put(e.entryDate, e.observationEntry.intercourseTimeOfDay != IntercourseTimeOfDay.NONE);
      boolean todayHasMucus = false;
      state.todayHasBlood = false;
      state.todaysFlow = null;
      if (e.observationEntry.observation == null) {
        consecutiveDaysOfMucus = 0;
      } else {
        daysWithAnObservation.add(e.entryDate);
        Observation observation = e.observationEntry.observation;
        todayHasMucus = observation.hasMucus();
        state.todayHasBlood = observation.dischargeSummary != null && observation.dischargeSummary.hasBlood();
        if (observation.flow != null) {
          state.todaysFlow = observation.flow;
          hasHadLegitFlow |= observation.flow.isLegit();
        }
        if (todayHasMucus) {
          consecutiveDaysOfMucus++;
          if (consecutiveDaysOfMucus >= 3) {
            lastDayOfThreeOrMoreDaysOfMucus = e.entryDate;
          }
          if (observation.dischargeSummary.isPeakType()) {
            mostRecentPeakTypeMucus = e.entryDate;
          }
        } else {
          consecutiveDaysOfMucus = 0;
        }
      }
      state.hasHadLegitFlow = hasHadLegitFlow;
      if (state.todayHasBlood || state.todaysFlow != null) {
        daysOfFlow.add(e.entryDate);
      }
      if (peakDays.isEmpty()) {
        state.firstPeakDay = Optional.empty();
        state.mostRecentPeakDay = Optional.empty();
      } else {
        state.firstPeakDay = Optional.of(peakDays.first());
        state.mostRecentPeakDay = Optional.of(peakDays.last());
      }
      state.hasHadAnyMucus = !daysOfMucus.isEmpty();
      state.consecutiveDaysOfMucus = consecutiveDaysOfMucus;
      state.hadIntercourseYesterday = daysOfIntercourse.containsKey(yesterday) && daysOfIntercourse.get(yesterday);
      if (!peakDays.isEmpty()) {
        state.countsOfThree.put(
            CountOfThreeReason.PEAK_DAY,
            Days.daysBetween(peakDays.last(), e.entryDate).getDays());
      }
      if (lastDayOfThreeOrMoreDaysOfMucus != null) {
        state.countsOfThree.put(
            CountOfThreeReason.CONSECUTIVE_DAYS_OF_MUCUS,
            Days.daysBetween(lastDayOfThreeOrMoreDaysOfMucus, e.entryDate).getDays());
      }
      if (mostRecentPeakTypeMucus != null) {
        state.countsOfThree.put(
            CountOfThreeReason.PEAK_TYPE_MUCUS,
            Days.daysBetween(mostRecentPeakTypeMucus, e.entryDate).getDays());
      }
      if (!daysOfUnusualBleeding.isEmpty()) {
        state.countsOfThree.put(
            CountOfThreeReason.UNUSUAL_BLEEDING,
            Days.daysBetween(daysOfUnusualBleeding.last(), e.entryDate).getDays());
      }
      if (!pointsOfChangeAway.isEmpty()) {
        state.countsOfThree.put(
            CountOfThreeReason.POINT_OF_CHANGE,
            Days.daysBetween(pointsOfChangeAway.last().minusDays(1), e.entryDate).getDays());
      }

      state.isInMenstrualFlow = entriesEvaluated.size() == daysOfFlow.size();
      state.allPreviousDaysHaveHadBlood =
          entriesEvaluated.headSet(yesterday).size() == daysOfFlow.headSet(yesterday).size();

      // Step 2: Evaluate fertility reasons
      Instructions instructions = null;
      for (Instructions i : mInstructions.descendingSet()) {
        if (!e.entryDate.isBefore(i.startDate)) {
          instructions = i;
          break;
        }
      }
      state.instructions = Optional.ofNullable(instructions).orElse(new Instructions(e.entryDate, ImmutableList.of(), ImmutableList.of(), ImmutableList.of()));

      // Basic Instruction fertility reasons (section D)
      if (state.instructions.isActive(BasicInstruction.D_1)
          && state.isInMenstrualFlow) {
        state.fertilityReasons.add(BasicInstruction.D_1);
      }
      if (state.instructions.isActive(BasicInstruction.D_2)
          && todayHasMucus
          && !state.isPostPeakPlus(3)) {
        state.fertilityReasons.add(BasicInstruction.D_2);
        state.countOfThreeReasons.put(BasicInstruction.D_2, CountOfThreeReason.PEAK_DAY);
      }
      if (state.instructions.isActive(BasicInstruction.D_3)
          && state.isPrePeak()
          && consecutiveDaysOfMucus > 0
          && consecutiveDaysOfMucus < 3) {
        state.fertilityReasons.add(BasicInstruction.D_3);
      }
      if (state.instructions.isActive(BasicInstruction.D_4)
          && state.isPrePeak()
          && state.isWithinCountOfThree(CountOfThreeReason.CONSECUTIVE_DAYS_OF_MUCUS)) {
        state.fertilityReasons.add(BasicInstruction.D_4);
        state.countOfThreeReasons.put(BasicInstruction.D_4, CountOfThreeReason.CONSECUTIVE_DAYS_OF_MUCUS);
      }
      if (state.instructions.isActive(BasicInstruction.D_5)
          && state.isWithinCountOfThree(CountOfThreeReason.PEAK_TYPE_MUCUS)) {
        state.fertilityReasons.add(BasicInstruction.D_5);
        state.countOfThreeReasons.put(BasicInstruction.D_5, CountOfThreeReason.PEAK_TYPE_MUCUS);
      }
      if (state.instructions.isActive(BasicInstruction.D_6)
          && state.isWithinCountOfThree(CountOfThreeReason.UNUSUAL_BLEEDING)) {
        state.fertilityReasons.add(BasicInstruction.D_6);
        state.countOfThreeReasons.put(BasicInstruction.D_6, CountOfThreeReason.UNUSUAL_BLEEDING);
      }

      // Basic Instruction infertility reasons (section E)
      if (!todayHasMucus && !state.isInMenstrualFlow && state.isPrePeak()) {
        if (state.instructions.isActive(BasicInstruction.E_1)) {
          state.infertilityReasons.add(BasicInstruction.E_1);
        }
        if (state.instructions.isActive(BasicInstruction.E_2)) {
          state.infertilityReasons.add(BasicInstruction.E_2);
        }
      }
      if (state.instructions.isActive(BasicInstruction.E_3)
          && state.isExactlyPostPeakPlus(4)) {
        state.infertilityReasons.add(BasicInstruction.E_3);
      }
      if (!todayHasMucus && state.isPostPeakPlus(4)) {
        if (state.instructions.isActive(BasicInstruction.E_4)) {
          state.infertilityReasons.add(BasicInstruction.E_4);
        }
        if (state.instructions.isActive(BasicInstruction.E_5)) {
          state.infertilityReasons.add(BasicInstruction.E_5);
        }
        if (state.instructions.isActive(BasicInstruction.E_6)) {
          state.infertilityReasons.add(BasicInstruction.E_6);
        }
      }
      if (!todayHasMucus && state.isInMenstrualFlow && (
          (state.todaysFlow != null && !state.todaysFlow.isLegit()) || state.todayHasBlood)) {
        state.infertilityReasons.add(BasicInstruction.E_7);
      }

      // Basic Instruction yellow stamp reasons (section K)
      Optional<LocalDate> effectivePointOfChange = effectivePointOfChange(pointsOfChangeToward, pointsOfChangeAway);
      if (state.instructions.isActive(BasicInstruction.K_1)
          && state.isPrePeak()
          && !state.isInMenstrualFlow
          && (!effectivePointOfChange.isPresent()
          || state.entryDate.isBefore(effectivePointOfChange.get()))) {
        state.suppressBasicInstructions(BasicInstruction.suppressableByPrePeakYellow, BasicInstruction.K_1);
      }
      if (state.isPostPeakPlus(4)) {
        if (state.instructions.isActive(BasicInstruction.K_2)) {
          state.suppressBasicInstructions(BasicInstruction.suppressableByPostPeakYellow, BasicInstruction.K_2);
        }
        if (state.instructions.isActive(BasicInstruction.K_3)) {
          state.suppressBasicInstructions(BasicInstruction.suppressableByPostPeakYellow, BasicInstruction.K_3);
        }
        if (state.instructions.isActive(BasicInstruction.K_4)) {
          state.suppressBasicInstructions(BasicInstruction.suppressableByPostPeakYellow, BasicInstruction.K_4);
        }
      }

      // Special Instruction Yellow Stamp fertility reasons (section 1)
      if (state.instructions.isActive(YellowStampInstruction.YS_1_A)
          && state.isInMenstrualFlow) {
        state.fertilityReasons.add(YellowStampInstruction.YS_1_A);
      }
      if (state.instructions.isActive(YellowStampInstruction.YS_1_B)
          // This is to catch cases where you have a peak day w/o a point of change...
          // TODO: flag this as an issue?
          && (state.isWithinCountOfThree(CountOfThreeReason.PEAK_DAY)
          || effectivePointOfChange.isPresent()
          && !state.entryDate.isBefore(effectivePointOfChange.get())
          && state.isPrePeak())) {
        state.fertilityReasons.add(YellowStampInstruction.YS_1_B);
        state.countOfThreeReasons.put(YellowStampInstruction.YS_1_B, CountOfThreeReason.PEAK_DAY);
      }
      if (state.instructions.isActive(YellowStampInstruction.YS_1_C)
          && !pointsOfChangeAway.isEmpty()
          // This last condition should check for +3 days but count starts on the Poc...?
          && !state.entryDate.isAfter(pointsOfChangeAway.last().plusDays(2))) {
        state.fertilityReasons.add(YellowStampInstruction.YS_1_C);
        state.countOfThreeReasons.put(YellowStampInstruction.YS_1_C, CountOfThreeReason.POINT_OF_CHANGE);
      }
      if (state.instructions.isActive(YellowStampInstruction.YS_1_D)
          && state.isWithinCountOfThree(CountOfThreeReason.UNUSUAL_BLEEDING)) {
        state.fertilityReasons.add(YellowStampInstruction.YS_1_D);
        state.countOfThreeReasons.put(YellowStampInstruction.YS_1_D, CountOfThreeReason.UNUSUAL_BLEEDING);
      }

      // Special Instruction Yellow Stamp infertility reasons (section 2)
      if (state.instructions.isActive(YellowStampInstruction.YS_2_A)
          && state.isPrePeak()
          && !state.isInMenstrualFlow
          && (!effectivePointOfChange.isPresent()
          || state.entryDate.isBefore(effectivePointOfChange.get()))) {
        state.suppressBasicInstructions(BasicInstruction.suppressableByPrePeakYellow, YellowStampInstruction.YS_2_A);
      }
      if (state.isPostPeakPlus(4)) {
        if (state.instructions.isActive(YellowStampInstruction.YS_2_B)) {
          state.suppressBasicInstructions(BasicInstruction.suppressableByPostPeakYellow, YellowStampInstruction.YS_2_B);
        }
        if (state.instructions.isActive(YellowStampInstruction.YS_2_C)) {
          state.suppressBasicInstructions(BasicInstruction.suppressableByPostPeakYellow, YellowStampInstruction.YS_2_C);
        }
        if (state.instructions.isActive(YellowStampInstruction.YS_2_D)) {
          state.suppressBasicInstructions(BasicInstruction.suppressableByPostPeakYellow, YellowStampInstruction.YS_2_D);
        }
      }

      // Super special infertility instructions...
      if (state.instructions.isActive(SpecialInstruction.BREASTFEEDING_SEMINAL_FLUID_YELLOW_STAMPS)
          && Optional.ofNullable(state.previousEntry).map(pe -> pe.observationEntry.intercourse).orElse(false)
          && state.entry.observationEntry.observation != null
          && state.entry.observationEntry.observation.dischargeSummary.isPeakType()
          && state.entry.observationEntry.isEssentiallyTheSame) {
        state.suppressBasicInstructions(BasicInstruction.suppressableByPrePeakYellow, SpecialInstruction.BREASTFEEDING_SEMINAL_FLUID_YELLOW_STAMPS);
      }

      for (Map.Entry<AbstractInstruction, CountOfThreeReason> mapEntry : state.countOfThreeReasons.entrySet()) {
        Optional<Integer> count = state.getCount(mapEntry.getValue());
        if (!count.isPresent()) {
          continue;
        }
        if (state.effectiveCountOfThree.first == null || count.get() < state.effectiveCountOfThree.first) {
          state.effectiveCountOfThree = Pair.create(count.get(), mapEntry.getKey());
        }
      }
      renderableEntries.add(RenderableEntry.fromState(state));
      previousEntry = e;
    }

    CycleStats.Builder statsBuilder = CycleStats.builder()
        .cycleStartDate(mCycle.startDate)
        .isPregnancy(mCycle.isPregnancy())
        .daysWithAnObservation(daysWithAnObservation.size())
        .mcs(MccScorer.getScore(mEntries, peakDays.isEmpty() ? Optional.empty() : Optional.of(peakDays.last())));
    if (!peakDays.isEmpty()) {
      statsBuilder.daysPrePeak(Optional.of(Days.daysBetween(mCycle.startDate, peakDays.last()).getDays()));
      if (mCycle.endDate != null) {
        statsBuilder.daysPostPeak(Optional.of(Days.daysBetween(peakDays.last(), mCycle.endDate).getDays()));
      }
    }

    return RenderableCycle.builder()
        .cycle(mCycle)
        .entries(renderableEntries)
        .stats(statsBuilder.build())
        .build();
  }

  private static Optional<LocalDate> effectivePointOfChange(TreeSet<LocalDate> toward, TreeSet<LocalDate> away) {
    if (toward.isEmpty() || toward.size() == away.size()) {
      return Optional.empty();
    }
    return Optional.of(toward.last());
  }

  public static class State {
    public Cycle cycle;
    public Optional<Cycle> previousCycle;
    public ChartEntry entry;
    public LocalDate entryDate;
    public Instructions instructions;
    public int entryNum;
    public Optional<LocalDate> firstPeakDay;
    public Optional<LocalDate> mostRecentPeakDay;
    public boolean isInMenstrualFlow;
    public boolean hasHadLegitFlow;
    public boolean allPreviousDaysHaveHadBlood;
    public Flow todaysFlow;
    public boolean todayHasBlood;
    public Optional<LocalDate> firstPointOfChangeToward;
    public Optional<LocalDate> mostRecentPointOfChangeToward;
    public Optional<LocalDate> mostRecentPointOfChangeAway;
    public boolean hasHadAnyMucus;
    public int consecutiveDaysOfMucus;
    public boolean hadIntercourseYesterday;
    public Map<CountOfThreeReason, Integer> countsOfThree = new HashMap<>();

    public Set<AbstractInstruction> fertilityReasons = new HashSet<>();
    public Map<AbstractInstruction, AbstractInstruction> suppressedFertilityReasons = new HashMap<>();
    public Set<AbstractInstruction> infertilityReasons = new HashSet<>();

    public Map<AbstractInstruction, CountOfThreeReason> countOfThreeReasons = new HashMap<>();
    public Pair<Integer, AbstractInstruction> effectiveCountOfThree = Pair.create(null, null);
    public ChartEntry previousEntry;

    boolean isPrePeak() {
      return !firstPeakDay.isPresent() || entryDate.isBefore(firstPeakDay.get());
    }

    public boolean isPeakDay() {
      return mostRecentPeakDay.isPresent() && mostRecentPeakDay.get().equals(entryDate);
    }

    boolean isPostPeak() {
      return isPostPeakPlus(0);
    }

    boolean isPostPeakPlus(int numDays) {
      return mostRecentPeakDay.isPresent() && entryDate.isAfter(mostRecentPeakDay.get().plusDays(numDays));
    }

    boolean isExactlyPostPeakPlus(int numDays) {
      return mostRecentPeakDay.isPresent() && entryDate.equals(mostRecentPeakDay.get().plusDays(numDays));
    }

    boolean isPocTowardFertility() {
      return mostRecentPointOfChangeToward.isPresent() && mostRecentPointOfChangeToward.get().equals(entryDate);
    }

    boolean isPocAwayFromFertility() {
      return mostRecentPointOfChangeAway.isPresent() && mostRecentPointOfChangeAway.get().equals(entryDate);
    }

    public Optional<Integer> getCount(CountOfThreeReason reason) {
      return Optional.ofNullable(countsOfThree.get(reason));
    }

    boolean isWithinCountOfThree(CountOfThreeReason reason) {
      Optional<Integer> count = getCount(reason);
      return count.isPresent() && count.get() < 4;
    }

    void suppressBasicInstructions(Collection<BasicInstruction> instructionsToSuppress,
                                          AbstractInstruction suppressionReason) {
      for (BasicInstruction instruction : instructionsToSuppress) {
        if (fertilityReasons.remove(instruction)) {
          countOfThreeReasons.remove(instruction);
          suppressedFertilityReasons.put(instruction, suppressionReason);
        }
      }
      infertilityReasons.add(suppressionReason);
    }

    String peakDayText() {
      if (!entry.hasObservation()) {
        return "";
      }
      if (isPeakDay()) {
        return "P";
      }
      if (fertilityReasons.isEmpty()) {
        return "";
      }
      if (effectiveCountOfThree.first == null || effectiveCountOfThree.first == 0) {
        return "";
      }
      if (effectiveCountOfThree.first > 0) {
        return String.valueOf(effectiveCountOfThree.first);
      }
      return "";
    }

    String getInstructionSummary() {
      if (entry.observationEntry.observation == null) {
        return "Please provide an observation by clicking edit below.";
      }
      List<String> instructionSummaryLines = new ArrayList<>();
      List<String> subsectionLines = new ArrayList<>();
      instructionSummaryLines.add(String.format("Status: %s",
          fertilityReasons.isEmpty() ? "Infertile" : "Fertile"));
      if (!fertilityReasons.isEmpty()) {
        subsectionLines.add("Fertility Reasons:");
        for (AbstractInstruction i : fertilityReasons) {
          subsectionLines.add(String.format(" - %s", AbstractInstruction.summary(i)));
        }
        instructionSummaryLines.add(ON_NEW_LINE.join(subsectionLines));
        subsectionLines.clear();
      }
      if (!infertilityReasons.isEmpty()) {
        subsectionLines.add("Infertility Reasons:");
        for (AbstractInstruction i : infertilityReasons) {
          subsectionLines.add(String.format(" - %s", AbstractInstruction.summary(i)));
        }
        instructionSummaryLines.add(ON_NEW_LINE.join(subsectionLines));
        subsectionLines.clear();
      }
      if (!suppressedFertilityReasons.isEmpty()) {
        subsectionLines.add("Impact of Yellow Stamps:");
        for (Map.Entry<AbstractInstruction, AbstractInstruction> e : suppressedFertilityReasons.entrySet()) {
          subsectionLines.add(String.format(" - %s inhibited by %s",
              AbstractInstruction.summary(e.getKey()), AbstractInstruction.summary(e.getValue())));
        }
        instructionSummaryLines.add(ON_NEW_LINE.join(subsectionLines));
        subsectionLines.clear();
      }
      if (!subsectionLines.isEmpty()) {
        Timber.w("Leaking strings!");
      }
      return ON_DOUBLE_NEW_LINE.join(instructionSummaryLines);
    }

    StickerColor getBackgroundColor() {
      if (instructions == null) {
        return StickerColor.GREY;
      }
      Observation observation = entry.observationEntry.observation;
      if (observation == null) {
        return StickerColor.GREY;
      }
      if (observation.hasBlood()) {
        return StickerColor.RED;
      }
      if (!observation.hasMucus()) {
        return StickerColor.GREEN;
      }
      // All entries have mucus at this point
      if (!infertilityReasons.isEmpty()) {
        return StickerColor.YELLOW;
      }
      if (instructions.anyActive(BasicInstruction.K_2, BasicInstruction.K_3, BasicInstruction.K_4)
          && isPostPeak() && !isPostPeakPlus(4)) {
        return StickerColor.YELLOW;
      }
      return StickerColor.WHITE;
    }

    boolean shouldAskEssentialSameness() {
      if (instructions == null) {
        return false;
      }
      if (instructions.isActive(SpecialInstruction.BREASTFEEDING_SEMINAL_FLUID_YELLOW_STAMPS)
          && Optional.ofNullable(previousEntry).map(e -> e.observationEntry.intercourse).orElse(false)) {
        return true;
      }
      if (instructions.isActive(BasicInstruction.K_1) && isPrePeak() && (!isInMenstrualFlow || hasHadLegitFlow)) {
        return true;
      }
      return false;
    }

    boolean shouldAskDoublePeakQuestions() {
      return instructions.isActive(BasicInstruction.G_1) && isExactlyPostPeakPlus(3);
    }

    boolean shouldShowBaby() {
      if (!entry.hasObservation()) {
        return false;
      }
      if (fertilityReasons.isEmpty()) {
        return infertilityReasons.isEmpty();
      }
      return !entry.observationEntry.hasBlood();
    }

    EntryModificationContext entryModificationContext() {
      EntryModificationContext modificationContext = new EntryModificationContext(cycle, entry);
      modificationContext.hasPreviousCycle = false;
      modificationContext.previousCycleIsPregnancy = previousCycle.map(Cycle::isPregnancy).orElse(false);
      modificationContext.allPreviousDaysHaveHadBlood = allPreviousDaysHaveHadBlood;
      modificationContext.isFirstEntry = entryNum == 1;
      modificationContext.shouldAskEssentialSamenessIfMucus = shouldAskEssentialSameness();
      modificationContext.shouldAskDoublePeakQuestions = shouldAskDoublePeakQuestions();
      return modificationContext;
    }
  }

  public enum CountOfThreeReason {
    UNUSUAL_BLEEDING, PEAK_DAY, CONSECUTIVE_DAYS_OF_MUCUS, PEAK_TYPE_MUCUS, POINT_OF_CHANGE;
  }

  @AutoValue
  public abstract static class RenderableCycle {
    public abstract Cycle cycle();
    public abstract List<RenderableEntry> entries();
    public abstract CycleStats stats();

    public static Builder builder() {
      return new AutoValue_CycleRenderer_RenderableCycle.Builder();
    }

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder entries(List<RenderableEntry> entries);

      public abstract Builder stats(CycleStats stats);

      public abstract Builder cycle(Cycle cycle);

      public abstract RenderableCycle build();
    }
  }

  @AutoValue
  public abstract static class CycleStats implements Comparable<CycleStats> {
    public abstract LocalDate cycleStartDate();
    public abstract Integer daysWithAnObservation();
    public abstract boolean isPregnancy ();
    public abstract Optional<Float> mcs();
    public abstract Optional<Integer> daysPrePeak();
    public abstract Optional<Integer> daysPostPeak();

    @Override
    public int compareTo(CycleStats other) {
      return cycleStartDate().compareTo(other.cycleStartDate());
    }

    public static Builder builder() {
      return new AutoValue_CycleRenderer_CycleStats.Builder()
          .mcs(Optional.empty())
          .isPregnancy(false)
          .daysWithAnObservation(0)
          .daysPrePeak(Optional.empty())
          .daysPostPeak(Optional.empty());
    }

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder cycleStartDate(LocalDate cycleStartDate);

      public abstract Builder daysWithAnObservation(Integer daysWithAnObservation);

      public abstract Builder isPregnancy(boolean isPregnancy);

      public abstract Builder mcs(Optional<Float> mcs);

      public abstract Builder daysPrePeak(Optional<Integer> daysPrePeak);

      public abstract Builder daysPostPeak(Optional<Integer> daysPostPeak);

      public abstract CycleStats build();
    }
  }

  @AutoValue
  public static abstract class RenderableEntry {
    public abstract Optional<StickerSelection> manualStickerSelection();
    public abstract boolean hasObservation();
    public abstract Set<AbstractInstruction> fertilityReasons();
    public abstract String entrySummary();
    public abstract StickerColor backgroundColor();
    public abstract int entryNum();
    public abstract String dateSummary();
    public abstract String dateSummaryShort();
    public abstract String peakDayText();
    public abstract String instructionSummary();
    public abstract String essentialSamenessSummary();
    public abstract boolean showBaby();
    public abstract IntercourseTimeOfDay intercourseTimeOfDay();
    public abstract String pocSummary();
    public abstract EntryModificationContext modificationContext();
    public abstract String trainingMarker();

    // TODO: add EoD / any time of day accounting for double peak Q's

    public static RenderableEntry fromState(State state) {
      String pocSummary;
      if (state.isPocTowardFertility()) {
        pocSummary = "POC↑";
      } else if (state.isPocAwayFromFertility()) {
        pocSummary = "POC↓";
      } else {
        pocSummary = "";
      }
      String essentialSamenessSummary;
      if (state.entryModificationContext().shouldAskEssentialSamenessIfMucus
          && state.entry.observationEntry.hasMucus()) {
        essentialSamenessSummary = state.entry.observationEntry.isEssentiallyTheSame ? "yes" : "no";
      } else {
        essentialSamenessSummary = "";
      }
      RenderableEntry renderableEntry = builder()
          .manualStickerSelection(Optional.ofNullable(state.entry.stickerSelection))
          .hasObservation(state.entry.hasObservation())
          .entryNum(state.entryNum)
          .fertilityReasons(state.fertilityReasons)
          .dateSummary(DateUtil.toNewUiStr(state.entry.entryDate))
          .dateSummaryShort(DateUtil.toPrintUiStr(state.entryDate))
          .entrySummary(state.entry.observationEntry.getListUiText())
          .backgroundColor(state.getBackgroundColor())
          .showBaby(state.shouldShowBaby())
          .peakDayText(state.peakDayText())
          .intercourseTimeOfDay(Optional.ofNullable(state.entry.observationEntry.intercourseTimeOfDay)
              .orElse(IntercourseTimeOfDay.NONE))
          .pocSummary(pocSummary)
          .instructionSummary(state.getInstructionSummary())
          .modificationContext(state.entryModificationContext())
          .essentialSamenessSummary(essentialSamenessSummary)
          .trainingMarker(state.entry.marker)
          .build();
      return renderableEntry;
    }

    @NonNull
    @Override
    public String toString() {
      return String.format("%s: %s", dateSummary(), entrySummary());
    }

    public static Builder builder() {
      return new AutoValue_CycleRenderer_RenderableEntry.Builder();
    }

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder entrySummary(String entrySummary);

      public abstract Builder backgroundColor(StickerColor backgroundColor);

      public abstract Builder entryNum(int entryNum);

      public abstract Builder dateSummary(String dateSummary);

      public abstract Builder peakDayText(String peakDayText);

      public abstract Builder instructionSummary(String instructionSummary);

      public abstract Builder essentialSamenessSummary(String essentialSamenessSummary);

      public abstract Builder showBaby(boolean showBaby);

      public abstract Builder intercourseTimeOfDay(IntercourseTimeOfDay intercourseTimeOfDay);

      public abstract Builder pocSummary(String pocSummary);

      public abstract Builder modificationContext(EntryModificationContext modificationContext);

      public abstract Builder trainingMarker(String trainingMarker);

      public abstract Builder dateSummaryShort(String dateSummaryShort);

      public abstract Builder fertilityReasons(Set<AbstractInstruction> fertilityReasons);

      public abstract Builder hasObservation(boolean hasObservation);

      public abstract Builder manualStickerSelection(Optional<StickerSelection> manualStickerSelection);

      public abstract RenderableEntry build();
    }
  }

  @Parcel
  public static class EntryModificationContext {
    @NonNull
    public Cycle cycle;
    @NonNull
    public ChartEntry entry;
    public boolean hasPreviousCycle;
    public boolean previousCycleIsPregnancy;
    public boolean isFirstEntry;
    public boolean shouldAskDoublePeakQuestions;
    public boolean allPreviousDaysHaveHadBlood;
    public boolean shouldAskEssentialSamenessIfMucus;

    @ParcelConstructor
    public EntryModificationContext(@NonNull Cycle cycle, @NonNull ChartEntry entry) {
      this.cycle = cycle;
      this.entry = entry;
    }
  }
}