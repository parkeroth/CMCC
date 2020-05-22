package com.bloomcyclecare.cmcc.ui.cycle.vertical;

import android.app.Application;
import android.util.Range;

import com.bloomcyclecare.cmcc.application.MyApplication;
import com.bloomcyclecare.cmcc.application.ViewMode;
import com.bloomcyclecare.cmcc.data.entities.Cycle;
import com.bloomcyclecare.cmcc.data.models.StickerSelection;
import com.bloomcyclecare.cmcc.data.repos.cycle.ROCycleRepo;
import com.bloomcyclecare.cmcc.data.repos.entry.ROChartEntryRepo;
import com.bloomcyclecare.cmcc.data.repos.instructions.ROInstructionsRepo;
import com.bloomcyclecare.cmcc.data.repos.sticker.RWStickerSelectionRepo;
import com.bloomcyclecare.cmcc.logic.PreferenceRepo;
import com.bloomcyclecare.cmcc.logic.chart.CycleRenderer;
import com.google.auto.value.AutoValue;

import org.joda.time.LocalDate;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.LiveDataReactiveStreams;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import timber.log.Timber;

class EntryListViewModel extends AndroidViewModel {

  private final Subject<ViewState> mViewState = BehaviorSubject.create();
  private final Subject<ScrollState> mScrollEventsFromUI = BehaviorSubject.create();

  private final RWStickerSelectionRepo mStickerSelectionRepo;

  private EntryListViewModel(@NonNull Application application, ViewMode viewMode, Cycle cycle) {
    super(application);
    MyApplication myApp = MyApplication.cast(application);
    ROCycleRepo cycleRepo = myApp.cycleRepo(viewMode);
    ROInstructionsRepo instructionsRepo = myApp.instructionsRepo(viewMode);
    ROChartEntryRepo entryRepo = myApp.entryRepo(viewMode);
    PreferenceRepo preferenceRepo = myApp.preferenceRepo();
    mStickerSelectionRepo = myApp.stickerSelectionRepo(viewMode);

    Flowable<CycleRenderer.RenderableCycle> cycleStream = Flowable.combineLatest(
        cycleRepo.getPreviousCycle(cycle).map(Optional::of).defaultIfEmpty(Optional.empty()).toFlowable(),
        instructionsRepo.getAll(),
        entryRepo.getStreamForCycle(Flowable.just(cycle)),
        (previousCycle, instructions, entries) -> new CycleRenderer(cycle, previousCycle, entries, instructions))
        .map(CycleRenderer::render);

    Flowable<ScrollState> scrollStateFlowable = mScrollEventsFromUI
        .sample(500, TimeUnit.MILLISECONDS)
        .toFlowable(BackpressureStrategy.BUFFER);

    Flowable<Boolean> autoStickeringStream =
        preferenceRepo.summaries().map(PreferenceRepo.PreferenceSummary::autoStickeringEnabled);

    Flowable<Map<LocalDate, StickerSelection>> stickerSelectionStream =
        mStickerSelectionRepo.getSelections(Range.create(cycle.startDate, Optional.ofNullable(cycle.endDate).orElse(LocalDate.now())));

    Flowable.combineLatest(
        cycleStream.distinctUntilChanged().doOnNext(rc -> Timber.v("Got new RenderableCycle")),
        scrollStateFlowable.distinctUntilChanged().doOnNext(ss -> Timber.v("Got new ScrollState")),
        autoStickeringStream.distinctUntilChanged(),
        (renderableCycle, scrollState, autoStickeringEnabled) -> new ViewState(
            cycle, renderableCycle, scrollState, viewMode, autoStickeringEnabled))
        .toObservable()
        .subscribeOn(Schedulers.computation())
        .subscribe(mViewState);
  }

  Completable updateStickerSelection(LocalDate date, StickerSelection selection) {
    return mStickerSelectionRepo.recordSelection(selection, date);
  }

  void updateScrollState(ScrollState scrollState) {
    mScrollEventsFromUI.onNext(scrollState);
  }

  public LiveData<ViewState> viewState() {
    return LiveDataReactiveStreams.fromPublisher(mViewState
        .subscribeOn(Schedulers.computation())
        .observeOn(AndroidSchedulers.mainThread())
        .toFlowable(BackpressureStrategy.BUFFER));
  }

  public static class ViewState {
    final Cycle cycle;
    final CycleRenderer.RenderableCycle renderableCycle;
    final ScrollState scrollState;
    final ViewMode viewMode;
    final boolean autoStickeringEnabled;

    private ViewState(
        Cycle cycle,
        CycleRenderer.RenderableCycle renderableCycle,
        ScrollState scrollState,
        ViewMode viewMode,
        boolean autoStickeringEnabled) {
      this.cycle = cycle;
      this.renderableCycle = renderableCycle;
      this.scrollState = scrollState;
      this.viewMode = viewMode;
      this.autoStickeringEnabled = autoStickeringEnabled;
    }
  }

  @AutoValue
  public static abstract class ScrollState {
    abstract int firstVisibleDay();
    abstract int offsetPixels();

    @Override
    public String toString() {
      return ("First visible: " + firstVisibleDay() + " Offset: " + offsetPixels());
    }

    public static ScrollState create(int firstVisibleDay, int offsetPixels) {
      return new AutoValue_EntryListViewModel_ScrollState(firstVisibleDay, offsetPixels);
    }
  }

  public static class Factory implements ViewModelProvider.Factory {

    private final Application mApplication;
    private final ViewMode viewMode;
    private final Cycle currentCycle;

    public Factory(Application application, ViewMode viewMode, Cycle currentCycle) {
      mApplication = application;
      this.viewMode = viewMode;
      this.currentCycle = currentCycle;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return (T) new EntryListViewModel(mApplication, viewMode, currentCycle);
    }
  }
}
