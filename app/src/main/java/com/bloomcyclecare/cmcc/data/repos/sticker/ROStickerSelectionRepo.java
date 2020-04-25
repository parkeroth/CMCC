package com.bloomcyclecare.cmcc.data.repos.sticker;

import android.util.Range;

import com.bloomcyclecare.cmcc.data.models.StickerSelection;
import com.google.auto.value.AutoValue;

import org.joda.time.LocalDate;

import java.util.Map;
import java.util.Optional;

import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;

public interface ROStickerSelectionRepo {

  @AutoValue
  abstract class UpdateEvent {
    public abstract LocalDate date();
    public abstract StickerSelection selection();

    static UpdateEvent create(LocalDate date, StickerSelection selection) {
      return new AutoValue_ROStickerSelectionRepo_UpdateEvent(date, selection);
    }
  }

  Observable<UpdateEvent> updateStream();

  Flowable<Map<LocalDate, StickerSelection>> getSelections(Range<LocalDate> dateRange);

  Single<Optional<StickerSelection>> getSelection(LocalDate date);
}
