package com.roamingroths.cmcc.ui.entry.detail;

import android.os.Parcel;
import android.os.Parcelable;

import com.roamingroths.cmcc.logic.Cycle;

import java.util.ArrayList;

/**
 * Created by parkeroth on 11/19/17.
 */
public class EntrySaveResult implements Parcelable {
  public ArrayList<Cycle> droppedCycles = new ArrayList<>();
  public ArrayList<Cycle> newCycles = new ArrayList<>();
  public Cycle cycle;

  public EntrySaveResult(Cycle cycle) {
    this.cycle = cycle;
  }

  protected EntrySaveResult(Parcel in) {
    droppedCycles = in.createTypedArrayList(Cycle.CREATOR);
    newCycles = in.createTypedArrayList(Cycle.CREATOR);
    cycle = in.readParcelable(Cycle.class.getClassLoader());
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeTypedList(droppedCycles);
    dest.writeTypedList(newCycles);
    dest.writeParcelable(cycle, flags);
  }

  @Override
  public int describeContents() {
    return 0;
  }

  public static final Creator<EntrySaveResult> CREATOR = new Creator<EntrySaveResult>() {
    @Override
    public EntrySaveResult createFromParcel(Parcel in) {
      return new EntrySaveResult(in);
    }

    @Override
    public EntrySaveResult[] newArray(int size) {
      return new EntrySaveResult[size];
    }
  };
}