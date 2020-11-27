package com.bloomcyclecare.cmcc.data.models.training;

import com.bloomcyclecare.cmcc.data.models.instructions.Instructions;
import com.bloomcyclecare.cmcc.data.models.stickering.StickerColor;
import com.google.common.collect.ImmutableMap;

import java.util.LinkedHashMap;
import java.util.Optional;

public class TrainingCycle {

  public final Instructions instructions;
  private final LinkedHashMap<TrainingEntry, Optional<StickerExpectations>> entries = new LinkedHashMap<>();
  private String title = "";
  private String subtitle = "";

  private TrainingCycle(Instructions instructions) {
    this.instructions = instructions;
  }

  public static TrainingCycle withInstructions(Instructions instructions) {
    return new TrainingCycle(instructions);
  }

  public TrainingCycle withTitle(String title) {
    this.title = title;
    return this;
  }

  public TrainingCycle withSubtitle(String subtitle) {
    this.subtitle = subtitle;
    return this;
  }

  public TrainingCycle addEntry(TrainingEntry entry, StickerExpectations expectations) {
    entries.put(entry, Optional.of(expectations));
    return this;
  }

  public TrainingCycle addEntry(TrainingEntry entry) {
    entries.put(entry, Optional.empty());
    return this;
  }

  public TrainingCycle addEntriesFrom(TrainingCycle other) {
    entries.putAll(other.entries());
    return this;
  }

  public ImmutableMap<TrainingEntry, Optional<StickerExpectations>> entries() {
    return ImmutableMap.copyOf(entries);
  }

  public static class StickerExpectations {

    public final StickerColor backgroundColor;
    public boolean shouldHaveBaby = false;
    public boolean shouldHaveIntercourse = false;
    // TODO: refactor this to use StickerText
    public String peakText = "";

    private StickerExpectations(StickerColor backgroundColor) {
      this.backgroundColor = backgroundColor;
    }

    public static StickerExpectations redSticker() {
      return new StickerExpectations(StickerColor.RED);
    }

    public static StickerExpectations greenSticker() {
      return new StickerExpectations(StickerColor.GREEN);
    }

    public static StickerExpectations yellowSticker() {
      return new StickerExpectations(StickerColor.YELLOW);
    }

    public static StickerExpectations whiteSticker() {
      return new StickerExpectations(StickerColor.WHITE);
    }

    public static StickerExpectations greySticker() {
      return new StickerExpectations(StickerColor.GREY);
    }

    public StickerExpectations withPeakText(String text) {
      peakText = text;
      return this;
    }

    public StickerExpectations withBaby() {
      shouldHaveBaby = true;
      return this;
    }

    public StickerExpectations withIntercourse() {
      shouldHaveIntercourse = true;
      return this;
    }
  }
}
