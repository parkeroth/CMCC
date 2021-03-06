package com.bloomcyclecare.cmcc.ui.cycle.vertical;

import android.app.Application;
import android.util.Range;

import com.bloomcyclecare.cmcc.ViewMode;
import com.bloomcyclecare.cmcc.apps.charting.ChartingApp;
import com.bloomcyclecare.cmcc.data.models.charting.Cycle;
import com.bloomcyclecare.cmcc.data.models.stickering.StickerSelection;
import com.bloomcyclecare.cmcc.data.repos.cycle.ROCycleRepo;
import com.bloomcyclecare.cmcc.data.repos.entry.ROChartEntryRepo;
import com.bloomcyclecare.cmcc.data.repos.instructions.ROInstructionsRepo;
import com.bloomcyclecare.cmcc.data.repos.sticker.RWStickerSelectionRepo;
import com.bloomcyclecare.cmcc.logic.PreferenceRepo;
import com.bloomcyclecare.cmcc.logic.chart.CycleRenderer;
import com.bloomcyclecare.cmcc.ui.cycle.CycleListViewModel;
import com.bloomcyclecare.cmcc.ui.cycle.RenderedEntry;
import com.google.auto.value.AutoValue;

import org.joda.time.LocalDate;

import java.util.ArrayList;
import java.util.List;
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
import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.Subject;
import timber.log.Timber;

class EntryListViewModel extends AndroidViewModel {

  private final Subject<ViewState> mViewState = BehaviorSubject.create();
  private final Subject<ScrollState> mScrollEventsFromUI = BehaviorSubject.create();
  public final Subject<Boolean> mShowMachineReadings = BehaviorSubject.create();

  private final RWStickerSelectionRepo mStickerSelectionRepo;
  private final ViewMode mViewMode;

  private EntryListViewModel(@NonNull Application application, CycleListViewModel cycleListViewModel, ViewMode viewMode, Cycle cycle) {
    super(application);
    ChartingApp myApp = ChartingApp.cast(application);
    ROCycleRepo cycleRepo = myApp.cycleRepo(viewMode);
    ROInstructionsRepo instructionsRepo = myApp.instructionsRepo(viewMode);
    ROChartEntryRepo entryRepo = myApp.entryRepo(viewMode);
    PreferenceRepo preferenceRepo = myApp.preferenceRepo();
    mStickerSelectionRepo = myApp.stickerSelectionRepo(viewMode);
    mViewMode = viewMode;

    Flowable<CycleRenderer.RenderableCycle> cycleStream  = cycleListViewModel.viewStateStream().map(vs -> {
      if (vs.viewMode() != viewMode) {
        throw new IllegalStateException(String.format("View model in wrong view mode %s != %s", vs.viewMode().name(), viewMode.name()));
      }
      for (CycleRenderer.RenderableCycle rc : vs.renderableCycles()) {
        if (rc.cycle().equals(cycle)) {
          return rc;
        }
      }
      throw new IllegalStateException("Couldn't find renderable cycle for cycle starting " + cycle.startDate);
    });

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
        instructionsRepo.getAll(),
        mShowMachineReadings.toFlowable(BackpressureStrategy.BUFFER),
        (renderableCycle, scrollState, autoStickeringEnabled, instructionsList, showMachineReadings) -> {
          boolean showcaseStickerSelection = false;
          Optional<LocalDate> entryShowcaseDate = Optional.empty();
          boolean inhibitShowcase = cycle.endDate != null/* || instructionsList.isEmpty()*/;

          List<RenderedEntry> renderedEntries = new ArrayList<>();
          for (CycleRenderer.RenderableEntry re : renderableCycle.entries()) {
            RenderedEntry renderedEntry = RenderedEntry.create(re, autoStickeringEnabled, viewMode, showMachineReadings);
            if (!inhibitShowcase && renderedEntry.hasObservation()) {
              showcaseStickerSelection = true;
              entryShowcaseDate = Optional.of(renderedEntry.entryDate());
            }
            renderedEntries.add(renderedEntry);
          }
          if (!inhibitShowcase && !entryShowcaseDate.isPresent() && !renderedEntries.isEmpty()) {
            RenderedEntry lastEntry = renderedEntries.get(renderedEntries.size() - 1);
            entryShowcaseDate = Optional.of(lastEntry.entryDate());
          }
          return new ViewState(
              cycle, renderedEntries, scrollState, viewMode, autoStickeringEnabled, entryShowcaseDate, showcaseStickerSelection);
        })
        .toObservable()
        .subscribeOn(Schedulers.computation())
        .doOnError(t -> Timber.i("ViewState raised exception, suppressing: %s", t.getMessage()))
        .onErrorResumeNext(Observable.empty())
        .subscribe(mViewState);
  }

  public ViewMode viewMode() {
    return mViewMode;
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
    final List<RenderedEntry> renderedEntries;
    final ScrollState scrollState;
    final ViewMode viewMode;
    final Optional<LocalDate> entryShowcaseDate;
    final boolean showcaseStickerSelection;

    private ViewState(
        Cycle cycle,
        List<RenderedEntry> renderedEntries,
        ScrollState scrollState,
        ViewMode viewMode,
        boolean autoStickeringEnabled,
        Optional<LocalDate> entryShowcaseDate,
        boolean showcaseStickerSelection) {
      Timber.v("Creating new ViewState");
      this.cycle = cycle;
      this.renderedEntries = renderedEntries;
      this.scrollState = scrollState;
      this.viewMode = viewMode;
      this.entryShowcaseDate = entryShowcaseDate;
      this.showcaseStickerSelection = showcaseStickerSelection;
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
    private final CycleListViewModel cycleListViewModel;
    private final ViewMode viewMode;
    private final Cycle currentCycle;

    public Factory(Application application, CycleListViewModel cycleListViewModel, ViewMode viewMode, Cycle currentCycle) {
      mApplication = application;
      this.cycleListViewModel = cycleListViewModel;
      this.viewMode = viewMode;
      this.currentCycle = currentCycle;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return (T) new EntryListViewModel(mApplication, cycleListViewModel, viewMode, currentCycle);
    }
  }
}
