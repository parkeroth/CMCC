package com.bloomcyclecare.cmcc.data.entities;

import org.joda.time.LocalDate;
import org.parceler.Parcel;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Parcel
@Entity
public class Pregnancy {
  @PrimaryKey(autoGenerate = true)
  public long id;

  public LocalDate positiveTestDate;
}
