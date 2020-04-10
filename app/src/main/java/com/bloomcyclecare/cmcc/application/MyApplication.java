package com.bloomcyclecare.cmcc.application;

import android.app.Application;
import android.content.Intent;

import com.bloomcyclecare.cmcc.BuildConfig;
import com.bloomcyclecare.cmcc.R;
import com.bloomcyclecare.cmcc.data.db.AppDatabase;
import com.bloomcyclecare.cmcc.data.drive.DriveServiceHelper;
import com.bloomcyclecare.cmcc.data.repos.cycle.CycleRepos;
import com.bloomcyclecare.cmcc.data.repos.cycle.RWCycleRepo;
import com.bloomcyclecare.cmcc.data.repos.entry.ChartEntryRepos;
import com.bloomcyclecare.cmcc.data.repos.entry.RWChartEntryRepo;
import com.bloomcyclecare.cmcc.data.repos.instructions.InstructionsRepos;
import com.bloomcyclecare.cmcc.data.repos.instructions.RWInstructionsRepo;
import com.bloomcyclecare.cmcc.data.repos.pregnancy.PregnancyRepos;
import com.bloomcyclecare.cmcc.data.repos.pregnancy.RWPregnancyRepo;
import com.bloomcyclecare.cmcc.logic.PreferenceRepo;
import com.bloomcyclecare.cmcc.logic.chart.ObservationParser;
import com.bloomcyclecare.cmcc.logic.drive.BackupWorker;
import com.bloomcyclecare.cmcc.logic.drive.PublishWorker;
import com.bloomcyclecare.cmcc.logic.drive.UpdateTrigger;
import com.bloomcyclecare.cmcc.notifications.ChartingReceiver;
import com.bloomcyclecare.cmcc.utils.RxUtil;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Range;
import com.google.firebase.FirebaseApp;

import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.joda.time.DateTime;
import org.joda.time.LocalDate;

import java.security.Security;
import java.util.List;
import java.util.concurrent.TimeUnit;

import androidx.preference.PreferenceManager;
import androidx.room.Room;
import androidx.room.migration.Migration;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.subjects.PublishSubject;
import io.reactivex.subjects.SingleSubject;
import timber.log.Timber;

/**
 * Created by parkeroth on 5/15/17.
 */

public class MyApplication extends Application {

  private static MyApplication INSTANCE;

  private static ViewModelFactory mViewModelFactory;

  private final CompositeDisposable mDisposables = new CompositeDisposable();
  private final SingleSubject<Optional<DriveServiceHelper>> mDriveSubject = SingleSubject.create();
  private final PublishSubject<Boolean> mManualSyncTriggers = PublishSubject.create();

  private RWInstructionsRepo mInstructionsRepo;
  private RWCycleRepo mCycleRepo;
  private RWChartEntryRepo mChartEntryRepo;
  private PreferenceRepo mPreferenceRepo;
  private RWPregnancyRepo mPregnancyRepo;

  public void registerDriveService(Optional<DriveServiceHelper> driveService) {
    mDriveSubject.onSuccess(driveService);
  }

  public static MyApplication getInstance() {
    return INSTANCE;
  };

  public void triggerSync() {
    mManualSyncTriggers.onNext(true);
  }

  @Override
  public void onCreate() {
    super.onCreate();
    FirebaseApp.initializeApp(this);
    //FirebaseDatabase.getInstance().setPersistenceEnabled(true);
    Security.addProvider(new BouncyCastleProvider());
    PreferenceManager.setDefaultValues(this, R.xml.pref_settings, false);

    if (BuildConfig.DEBUG) {
      Timber.plant(new Timber.DebugTree());
    }

    Migration[] migrations = new Migration[AppDatabase.MIGRATIONS.size()];
    AppDatabase.MIGRATIONS.toArray(migrations);
    //.fallbackToDestructiveMigration()  // I'm sure this will bite me in the end...
    AppDatabase db = Room.databaseBuilder(getApplicationContext(), AppDatabase.class, "room-db")
        .addMigrations(migrations)
        //.fallbackToDestructiveMigration()  // I'm sure this will bite me in the end...
        .build();

    mInstructionsRepo = InstructionsRepos.forRoomDB(db);
    mChartEntryRepo = ChartEntryRepos.forRoomDB(db);
    mCycleRepo = CycleRepos.forRoomDB(db);
    mPreferenceRepo = PreferenceRepo.create(this);
    mPregnancyRepo = PregnancyRepos.forRoomDb(db, mCycleRepo);

    mViewModelFactory = new ViewModelFactory();

    Timber.i("Sending charting reminder restart intent");
    Intent chartingRestartIntent = new Intent(this, ChartingReceiver.class);
    sendBroadcast(chartingRestartIntent);


    Flowable<UpdateTrigger> triggerStream = Flowable.merge(
        cycleRepo().updateEvents().map(e -> new UpdateTrigger(e.updateTime, e.dateRange)).doOnNext(t -> Timber.v("New cycle update")) ,
        entryRepo().updateEvents().flatMap(e -> cycleRepo()
            .getCycleForDate(e.updateTarget).toSingle()
            .map(cycle -> Range.closed(cycle.startDate, Optional.fromNullable(cycle.endDate).or(LocalDate.now())))
            .map(range -> new UpdateTrigger(e.updateTime, range))
            .toFlowable()).doOnNext(t -> Timber.v("New entry update")),
        instructionsRepo().updateEvents().map(e -> new UpdateTrigger(e.updateTime, e.dateRange)).doOnNext(t -> Timber.v("New instruction update")))
        .share();

    Flowable<List<UpdateTrigger>> batchedTriggers = Flowable.merge(
        triggerStream.doOnNext(t -> Timber.v("New update event"))
            .buffer(Flowable.combineLatest(
            // Watch the trigger stream
            triggerStream,
            // And a rebroadcast of the stream
            triggerStream.delay(30, TimeUnit.SECONDS),
            // Looking for when the rebroadcast == the trigger stream
            (latest, delayed) -> latest.triggerTime == delayed.triggerTime)
            .doOnNext(b -> Timber.v("Separator update: %b", b))
            // Filter for only these cases
            .filter(v -> v))
            .doOnNext(t -> Timber.v("New update batch")),
        mManualSyncTriggers.map(b -> ImmutableList.of(new UpdateTrigger(DateTime.now(), Range.singleton(LocalDate.now())))).toFlowable(BackpressureStrategy.BUFFER))
        .share();

    Flowable<Range<LocalDate>> mergedTrigger = batchedTriggers
        .map(triggers -> {
          LocalDate start = null;
          LocalDate end = null;
          for (UpdateTrigger trigger : triggers) {
            if (trigger.dateRange.hasLowerBound() && (start == null || start.isAfter(trigger.dateRange.lowerEndpoint()))) {
              start = trigger.dateRange.lowerEndpoint();
            }
            if (trigger.dateRange.hasUpperBound() && (end == null || end.isBefore(trigger.dateRange.upperEndpoint()))) {
              end = trigger.dateRange.upperEndpoint();
            }
          }
          if (start == null) {
            throw new IllegalArgumentException();
          }
          return Range.closed(start, Optional.fromNullable(end).or(LocalDate.now()));
        });

    mDisposables.add(mergedTrigger
        .compose(RxUtil.takeWhile(mPreferenceRepo.summaries(), PreferenceRepo.PreferenceSummary::backupEnabled))
        .map(dateRange -> new OneTimeWorkRequest.Builder(PublishWorker.class)
            .setInputData(PublishWorker.createInputData(dateRange))
            .build())
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(request -> {
          Timber.d("Received work request for publish");
          WorkManager.getInstance(getApplicationContext()).enqueue(request);
        }, Timber::e));

    mDisposables.add(batchedTriggers
        //.startWith(ImmutableList.<UpdateTrigger>of())
        .compose(RxUtil.onceAvailable(driveService()))
        .map(trigger -> new OneTimeWorkRequest.Builder(BackupWorker.class).build())
        .compose(RxUtil.takeWhile(mPreferenceRepo.summaries(), PreferenceRepo.PreferenceSummary::backupEnabled))
        .observeOn(AndroidSchedulers.mainThread())
        .subscribe(request -> {
          Timber.d("Received work request for backup");
          WorkManager.getInstance(getApplicationContext()).enqueue(request);
        }, Timber::e));

    mDisposables.add(mDriveSubject.subscribe(
        s -> Timber.d("DriveServiceHelper initialized."),
        t -> Timber.e(t, "Error initializing DriveServiceHelper")));

    INSTANCE = this;
  }

  public SingleSubject<Optional<DriveServiceHelper>> driveService() {
    return mDriveSubject;
  }

  public RWInstructionsRepo instructionsRepo(ViewMode viewMode) {
    switch (viewMode) {
      case TRAINING:
        return InstructionsRepos.forTraining();
      case CHARTING:
        return mInstructionsRepo;
      default:
        Timber.e("Unknown ViewMode %s, returning charting instruction repo", viewMode.name());
        return mInstructionsRepo;
    }
  }

  @Deprecated
  public RWInstructionsRepo instructionsRepo() {
    return instructionsRepo(ViewMode.CHARTING);
  }

  public RWCycleRepo cycleRepo(ViewMode viewMode) {
    switch (viewMode) {
      case TRAINING:
        return CycleRepos.forTraining();
      case CHARTING:
        return mCycleRepo;
      default:
        Timber.e("Unknown ViewMode %s, returning charting cycle repo", viewMode.name());
        return mCycleRepo;
    }
  }

  @Deprecated
  public RWCycleRepo cycleRepo() {
    return cycleRepo(ViewMode.CHARTING);
  }

  public RWChartEntryRepo entryRepo(ViewMode viewMode) {
    switch (viewMode) {
      case TRAINING:
        try {
          return ChartEntryRepos.forTraining();
        } catch (ObservationParser.InvalidObservationException ioe) {
          Timber.wtf(ioe, "Exception creating training repo, returning charting repo");
        }
      case CHARTING:
        return mChartEntryRepo;
      default:
        Timber.e("Unknown ViewMode %s, returning charting entry repo", viewMode.name());
        return mChartEntryRepo;
    }
  }

  @Deprecated
  public RWChartEntryRepo entryRepo() {
    return entryRepo(ViewMode.CHARTING);
  }

  public PreferenceRepo preferenceRepo() {
    return mPreferenceRepo;
  }

  public RWPregnancyRepo pregnancyRepo(ViewMode viewMode) {
    switch (viewMode) {
      case TRAINING:
        return PregnancyRepos.forTraining();
      case CHARTING:
        return mPregnancyRepo;
      default:
        Timber.e("Unknown ViewMode %s, returning charting pregnancy repo", viewMode.name());
        return mPregnancyRepo;
    }
  }

  @Deprecated
  public RWPregnancyRepo pregnancyRepo() {
    return pregnancyRepo(ViewMode.CHARTING);
  }

  public static MyApplication cast(Application app) {
    return (MyApplication) app;
  }

  public static ViewModelFactory viewModelFactory() {
    return mViewModelFactory;
  }
}
