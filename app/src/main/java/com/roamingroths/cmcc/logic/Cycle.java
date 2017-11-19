package com.roamingroths.cmcc.logic;

import android.os.Parcel;
import android.os.Parcelable;

import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.firebase.database.DataSnapshot;
import com.roamingroths.cmcc.crypto.AesCryptoUtil;
import com.roamingroths.cmcc.utils.DateUtil;

import org.joda.time.LocalDate;

import java.util.Comparator;

import javax.crypto.SecretKey;

import io.reactivex.annotations.NonNull;
import io.reactivex.functions.Function;

/**
 * Created by parkeroth on 4/30/17.
 */

public class Cycle implements Parcelable {

  public final String id;
  public final String previousCycleId;
  public String nextCycleId;
  public final LocalDate startDate;
  public LocalDate endDate;
  public final String startDateStr;
  public transient Keys keys;

  public static Cycle fromSnapshot(DataSnapshot snapshot, Keys keys) {
    String previousCycleId = snapshot.child("previous-cycle-id").getValue(String.class);
    String nextCycleId = snapshot.child("next-cycle-id").getValue(String.class);
    LocalDate startDate = DateUtil.fromWireStr(snapshot.child("start-date").getValue(String.class));
    LocalDate endDate = null;
    if (snapshot.hasChild("end-date")) {
      endDate = DateUtil.fromWireStr(snapshot.child("end-date").getValue(String.class));
    }
    return new Cycle(snapshot.getKey(), previousCycleId, nextCycleId, startDate, endDate, keys);
  }

  public static Function<Keys, Cycle> fromSnapshot(final DataSnapshot snapshot) {
    return new Function<Keys, Cycle>() {
      @Override
      public Cycle apply(@NonNull Keys keys) throws Exception {
        return fromSnapshot(snapshot, keys);
      }
    };
  }

  public static Comparator<Cycle> comparator() {
    return new Comparator<Cycle>() {
      @Override
      public int compare(Cycle c1, Cycle c2) {
        if (c1.equals(c2)) {
          return 0;
        }
        return c1.startDate.isBefore(c2.startDate) ? -1 : 1;
      }
    };
  }

  public Cycle(String id, String previousCycleId, String nextCycleId, LocalDate startDate, LocalDate endDate, Keys keys) {
    Preconditions.checkNotNull(startDate);
    this.id = id;
    this.previousCycleId = previousCycleId;
    this.nextCycleId = nextCycleId;
    this.startDate = startDate;
    this.startDateStr = DateUtil.toWireStr(this.startDate);
    this.endDate = endDate;
    this.keys = keys;
  }

  protected Cycle(Parcel in) {
    this(
        in.readString(),
        in.readString(),
        in.readString(),
        Preconditions.checkNotNull(DateUtil.fromWireStr(in.readString())),
        DateUtil.fromWireStr(in.readString()),
        new Keys(
            AesCryptoUtil.parseKey(in.readString()),
            AesCryptoUtil.parseKey(in.readString()),
            AesCryptoUtil.parseKey(in.readString())));
  }

  public static final Creator<Cycle> CREATOR = new Creator<Cycle>() {
    @Override
    public Cycle createFromParcel(Parcel in) {
      return new Cycle(in);
    }

    @Override
    public Cycle[] newArray(int size) {
      return new Cycle[size];
    }
  };

  @Override
  public int describeContents() {
    return 0;
  }

  @Override
  public void writeToParcel(Parcel dest, int flags) {
    dest.writeString(id);
    dest.writeString(previousCycleId);
    dest.writeString(nextCycleId);
    dest.writeString(startDateStr);
    dest.writeString(DateUtil.toWireStr(endDate));
    dest.writeString(AesCryptoUtil.serializeKey(keys.chartKey));
    dest.writeString(AesCryptoUtil.serializeKey(keys.wellnessKey));
    dest.writeString(AesCryptoUtil.serializeKey(keys.symptomKey));
  }

  @Override
  public boolean equals(Object o) {
    if (o instanceof Cycle) {
      Cycle that = (Cycle) o;
      return Objects.equal(this.id, that.id) &&
          Objects.equal(this.startDate, that.startDate) &&
          Objects.equal(this.endDate, that.endDate);
    }
    return false;
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(id, startDate, endDate);
  }

  public void setKeys(Keys keys) {
    this.keys = keys;
  }

  public static class Keys {
    public SecretKey chartKey;
    public SecretKey wellnessKey;
    public SecretKey symptomKey;

    public Keys(SecretKey chartKey, SecretKey wellnessKey, SecretKey symptomKey) {
      this.chartKey = chartKey;
      this.wellnessKey = wellnessKey;
      this.symptomKey = symptomKey;
    }
  }

  @Override
  public String toString() {
    return id;
  }
}
