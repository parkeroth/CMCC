package com.bloomcyclecare.cmcc.ui.cycle;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.net.Uri;

import com.bloomcyclecare.cmcc.ViewMode;
import com.bloomcyclecare.cmcc.apps.charting.ChartingApp;
import com.bloomcyclecare.cmcc.backup.AppStateExporter;
import com.bloomcyclecare.cmcc.data.models.stickering.StickerSelection;
import com.bloomcyclecare.cmcc.data.models.training.Exercise;
import com.bloomcyclecare.cmcc.data.repos.cycle.ROCycleRepo;
import com.bloomcyclecare.cmcc.data.repos.entry.ROChartEntryRepo;
import com.bloomcyclecare.cmcc.data.repos.sticker.RWStickerSelectionRepo;
import com.bloomcyclecare.cmcc.data.utils.GsonUtil;
import com.bloomcyclecare.cmcc.logic.PreferenceRepo;
import com.bloomcyclecare.cmcc.logic.chart.CycleRenderer;
import com.bloomcyclecare.cmcc.utils.RxUtil;
import com.google.auto.value.AutoValue;
import com.google.common.base.Charsets;
import com.google.common.io.Files;

import org.joda.time.LocalDate;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import androidx.annotation.NonNull;
import androidx.core.app.ShareCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Completable;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.reactivex.Single;
import io.reactivex.schedulers.Schedulers;
import io.reactivex.subjects.BehaviorSubject;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.Subject;
import timber.log.Timber;

public class CycleListViewModel extends AndroidViewModel {

  private final Subject<RWStickerSelectionRepo> mStickerSelectionRepoSubject = BehaviorSubject.create();
  private final Subject<ViewState> mViewStateSubject = BehaviorSubject.create();
  private final Subject<Boolean> mToggles = PublishSubject.create();
  private final Subject<Boolean> mShowMonitorReadingsToggles = BehaviorSubject.create();
  private final Subject<Boolean> mShowMonitorReadings = BehaviorSubject.create();

  private final Activity mActivity;
  private final ChartingApp mApplication;

  public static CycleListViewModel forFragment(Fragment fragment, ViewMode initialViewMode, Optional<Exercise.ID> exerciseID) {
    Factory factory = new Factory(fragment.requireActivity(), initialViewMode, exerciseID);
    return new ViewModelProvider(fragment, factory).get(CycleListViewModel.class);
  }

  public CycleListViewModel(@NonNull Application application,
                            @NonNull Activity activity,
                            @NonNull ViewMode initialViewMode,
                            @NonNull Optional<Exercise.ID> exerciseID) {
    super(application);
    mApplication = ChartingApp.cast(application);
    mActivity = activity;

    Observable<ViewMode> viewModeStream = mToggles
        .scan(initialViewMode, (previousVideMode, toggle) -> {
          if (previousVideMode == ViewMode.CHARTING) {
            return ViewMode.DEMO;
          } else {
            return ViewMode.CHARTING;
          }
        })
        .doOnNext(vm -> Timber.d("Switching to ViewMode = %s", vm.name()))
        .cache();

    viewModeStream.map(mApplication::stickerSelectionRepo).subscribe(mStickerSelectionRepoSubject);

    Optional<Exercise> exercise = Exercise.forID(exerciseID.orElse(Exercise.ID.CYCLE_REVIEW_REGULAR_CYCLES));

    mShowMonitorReadingsToggles
        .scan(1, (v, t) -> ++v)
        .doOnNext(i -> Timber.v("SHOW: %d", i))
        .map(i -> i % 2 == 0)
        .doOnNext(v -> Timber.v("Show monitor readings %b", v))
        .subscribe(mShowMonitorReadings);

    viewModeStream.flatMap(viewMode -> {
      if (viewMode == ViewMode.TRAINING && !exerciseID.isPresent()) {
        Timber.w("Need exercise ID for TRAINING mode!, defaulting to regular cycle");
      }
      ROChartEntryRepo entryRepo = viewMode == ViewMode.TRAINING
          ? mApplication.entryRepo(exercise.orElse(null)) : mApplication.entryRepo(viewMode);
      ROCycleRepo cycleRepo = viewMode == ViewMode.TRAINING
          ? mApplication.cycleRepo(exercise.orElse(null)) : mApplication.cycleRepo(viewMode);

      Flowable<Boolean> autoStickeringStream = mApplication.preferenceRepo()
          .summaries().map(PreferenceRepo.PreferenceSummary::autoStickeringEnabled)
          .distinctUntilChanged();

      Flowable<Map<LocalDate, StickerSelection>> stickerSelectionStream = mApplication.stickerSelectionRepo(viewMode)
          .getSelections().distinctUntilChanged();

      Flowable<List<CycleRenderer.RenderableCycle>> renderableCycleStream = Flowable.merge(Flowable.combineLatest(
          mApplication.instructionsRepo(viewMode).getAll()
              .distinctUntilChanged(),
          cycleRepo.getStream()
              .distinctUntilChanged(),
          (instructions, cycles) -> Flowable.merge(Flowable
              .fromIterable(cycles)
              .observeOn(Schedulers.computation())
              .parallel()
              .map(cycle -> Flowable.combineLatest(
                  entryRepo.getStreamForCycle(Flowable.just(cycle))
                      .doOnNext(ces -> Timber.v("Got new stream for cycle starting %s", cycle.startDate)),
                  cycleRepo.getPreviousCycle(cycle)
                      .map(Optional::of).defaultIfEmpty(Optional.empty())
                      .toFlowable(),
                  (entries, previousCycle) -> new CycleRenderer(cycle, previousCycle, entries, instructions))
                  .doOnNext(r -> Timber.v("Triggering render for cycle starting %s", r.cycle().startDate))
                  .map(CycleRenderer::render)
              )
              .sequential()
              .toList()
              .toFlowable()
              .map(RxUtil::combineLatest))));

      return Flowable.combineLatest(
          renderableCycleStream,
          autoStickeringStream,
          mApplication.preferenceRepo().summaries()
              .map(PreferenceRepo.PreferenceSummary::clearblueMachineMeasurementEnabled),
          showMonitorReadings(),
          stickerSelectionStream,
          (renderableCycles, autoStickeringEnabled, monitorReadingsEnabled, showMonitorReadings, stickerSelections) -> ViewState.create(
              viewMode, renderableCycles, autoStickeringEnabled, monitorReadingsEnabled, showMonitorReadings, stickerSelections))
          .toObservable();
    }).subscribe(mViewStateSubject);
  }

  public Flowable<ViewState> viewStateStream() {
    return mViewStateSubject.toFlowable(BackpressureStrategy.BUFFER);
  }

  public ViewMode currentViewMode() {
    return mViewStateSubject.blockingFirst().viewMode();
  }

  public ViewState currentViewState() {
    return mViewStateSubject.blockingFirst();
  }

  public Flowable<Boolean> showMonitorReadings() {
    return mShowMonitorReadings.toFlowable(BackpressureStrategy.BUFFER);
  }

  public Completable updateStickerSelection(LocalDate date, StickerSelection selection) {
    return mStickerSelectionRepoSubject
        .flatMapCompletable(repo -> repo.recordSelection(selection, date));
  }

  Completable clearData() {
    return Completable.mergeArray(
        mApplication.cycleRepo(ViewMode.CHARTING).deleteAll(),
        mApplication.instructionsRepo(ViewMode.CHARTING).deleteAll(),
        mApplication.entryRepo(ViewMode.CHARTING).deleteAll());
  }

  @AutoValue
  public static abstract class ViewState {
    public abstract ViewMode viewMode();
    public abstract List<CycleRenderer.RenderableCycle> renderableCycles();
    public abstract boolean autoStickeringEnabled();
    public abstract boolean monitorReadingsEnabled();
    public abstract boolean showMonitorReadings();
    public abstract Map<LocalDate, StickerSelection> stickerSelections();

    public static ViewState create(ViewMode viewMode, List<CycleRenderer.RenderableCycle> renderableCycles, boolean autoStickeringEnabled, boolean monitorReadingsEnabled, boolean showMonitorReadings, Map<LocalDate, StickerSelection> stickerSelections) {
      return new AutoValue_CycleListViewModel_ViewState(viewMode, renderableCycles, autoStickeringEnabled, monitorReadingsEnabled, showMonitorReadings, stickerSelections);
    }

  }

  public void toggleShowMonitorReadings() {
    mShowMonitorReadingsToggles.onNext(true);
  }

  public void toggleViewMode() {
    mToggles.onNext(true);
  }

  Single<Intent> export() {
    AppStateExporter exporter = new AppStateExporter(ChartingApp.cast(getApplication()));
    return exporter.getShareIntent(mActivity);
  }

  private static class Factory implements ViewModelProvider.Factory {

    private final Activity mActivity;
    private final ViewMode mInitialViewMode;
    private final Optional<Exercise.ID> mExerciseId;

    public Factory(Activity activity, ViewMode initialViewMode, Optional<Exercise.ID> exerciseID) {
      mActivity = activity;
      mInitialViewMode = initialViewMode;
      mExerciseId = exerciseID;
    }

    @NonNull
    @Override
    public <T extends ViewModel> T create(@NonNull Class<T> modelClass) {
      return (T) new CycleListViewModel(mActivity.getApplication(), mActivity, mInitialViewMode, mExerciseId);
    }
  }
}
